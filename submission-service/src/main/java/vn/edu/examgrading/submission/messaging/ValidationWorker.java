package vn.edu.examgrading.submission.messaging;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.examgrading.submission.domain.Submission;
import vn.edu.examgrading.submission.domain.SubmissionStatus;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository;
import vn.edu.examgrading.submission.repository.ProcessingJobRepository;
import vn.edu.examgrading.submission.repository.SubmissionEventRepository;
import vn.edu.examgrading.submission.repository.SubmissionRepositoryMarker;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway;
import vn.edu.examgrading.submission.client.IdentityClient;
import vn.edu.examgrading.submission.client.ResultClient;

/**
 * Worker consumer for VALIDATE jobs (TICKET 02, spec §8).
 * Validates PDF files: readability, page count, size vs template, calculates SHA-256.
 * Technical errors retry via the delayed retry queues
 * {@code submission.validate.retry.30s|2m|10m} (max 3 retries), then FAILED
 * with {@code VALIDATE_RETRY_EXHAUSTED}. Per T05/T06 the submission stays
 * VALIDATING after this worker finishes — the ANONYMIZE worker performs the
 * VALIDATING → ANONYMIZING transition when it picks up the job.
 */
@Component
public class ValidationWorker {

    private static final Logger log = LoggerFactory.getLogger(ValidationWorker.class);

    private static final int MAX_RETRIES = 3;
    // §7.5: each MediaBox edge must not exceed 200in (14400pt); larger → LIMIT_EXCEEDED.
    private static final double PDF_MAX_PAGE_POINTS = 14400;
    private static final long[] RETRY_DELAYS_MS = {30_000L, 120_000L, 600_000L}; // 30s, 2m, 10m
    private static final String[] RETRY_QUEUES = {
        "submission.validate.retry.30s",
        "submission.validate.retry.2m",
        "submission.validate.retry.10m"
    };
    private static final String EXCHANGE = "submission.processing";
    private static final String DLQ = "submission.validate.dlq";

    private final RabbitTemplate rabbitTemplate;
    private final ObjectStorageGateway storageGateway;
    private final SubmissionRepositoryMarker submissionRepo;
    private final ProcessingJobRepository processingJobRepo;
    private final PaperTemplateRepository templateRepo;
    private final SubmissionEventRepository eventRepo;
    private final IdentityClient identityClient;
    private final ResultClient resultClient;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public ValidationWorker(
            RabbitTemplate rabbitTemplate,
            ObjectStorageGateway storageGateway,
            SubmissionRepositoryMarker submissionRepo,
            ProcessingJobRepository processingJobRepo,
            PaperTemplateRepository templateRepo,
            SubmissionEventRepository eventRepo,
            IdentityClient identityClient,
            ResultClient resultClient,
            JdbcTemplate jdbcTemplate,
            Clock clock) {
        this.rabbitTemplate = rabbitTemplate;
        this.storageGateway = storageGateway;
        this.submissionRepo = submissionRepo;
        this.processingJobRepo = processingJobRepo;
        this.templateRepo = templateRepo;
        this.eventRepo = eventRepo;
        this.identityClient = identityClient;
        this.resultClient = resultClient;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @RabbitListener(queues = "submission.validate.v1")
    @Transactional
    public void processValidateJob(ValidateJobMessage message) {
        // §8: messages failing schema validation go straight to the DLQ, no retry.
        if (message == null || message.schemaVersion() != ValidateJobMessage.SCHEMA_VERSION
                || message.jobId() == null || message.submissionId() == null) {
            log.warn("Invalid validate message schema, routing to DLQ");
            if (message != null) {
                rabbitTemplate.convertAndSend(DLQ, message);
            }
            return;
        }

        UUID submissionId = message.submissionId();
        UUID jobId = message.jobId();
        log.debug("RabbitMQ receive: queue=submission.validate.v1, jobId={}, submissionId={}, attempt={}",
            jobId, submissionId, message.attempt());

        // §8 idempotency: keyed on jobId; skip when this job already succeeded.
        var job = processingJobRepo.findJobById(jobId).orElse(null);
        if (job == null) {
            log.warn("Validate job {} not found, acking", jobId);
            return;
        }
        if (!job.submissionId().equals(submissionId) || !"VALIDATE".equals(job.step())) {
            log.warn("Validate message jobId {} does not match its job row, routing to DLQ", jobId);
            rabbitTemplate.convertAndSend(DLQ, message);
            return;
        }
        if ("SUCCEEDED".equals(job.status()) || "COMPLETED".equals(job.status())) {
            return;
        }

        var submission = submissionRepo.findById(submissionId)
            .orElseThrow(() -> new IllegalStateException("Submission not found: " + submissionId));

        // §8 stale-message guard: fileVersion must match the current file version.
        // Stale messages are acked without touching the job or submission (§8).
        if (message.fileVersion() != submission.currentFileVersion()) {
            log.info("Skipping validate for stale fileVersion {} (current {})",
                message.fileVersion(), submission.currentFileVersion());
            return;
        }
        // Only UPLOADED (first delivery) or VALIDATING (retry) may be processed.
        if (submission.status() != SubmissionStatus.UPLOADED
                && submission.status() != SubmissionStatus.VALIDATING) {
            log.info("Skipping validate for submission {} in status {}", submissionId, submission.status());
            processingJobRepo.updateJobStatus(jobId, "SUCCEEDED", job.attempt());
            return;
        }

        try {
            // Update job status to PROCESSING
            processingJobRepo.updateJobStatus(jobId, "PROCESSING", job.attempt() + 1);

            // T05: UPLOADED → VALIDATING on receipt (no-op on retries).
            int moved = submissionRepo.transition(submissionId, SubmissionStatus.UPLOADED, SubmissionStatus.VALIDATING, 50);
            if (moved > 0) {
                eventRepo.append(submissionId, SubmissionStatus.VALIDATING, 50);
            }

            // Get object key
            String objectKey = submission.objectKey();
            if (objectKey == null) {
                throw new ValidationException("OBJECT_KEY_MISSING", "Object key is null");
            }

            // Download PDF from MinIO
            byte[] pdfBytes = storageGateway.get(objectKey);
            log.debug("Loaded original PDF for validation: submissionId={}, bytes={}", submissionId, pdfBytes.length);

            // Calculate SHA-256
            String sha256 = calculateSha256(pdfBytes);
            log.debug("Validation SHA-256 calculated: submissionId={}", submissionId);

            // Update exam_file with sha256
            updateExamFileSha256(submissionId, sha256);
            log.debug("Original file metadata updated: submissionId={}", submissionId);

            // Validate PDF with PDFBox
            int pageCount = validatePdf(pdfBytes);
            log.debug("PDF structure validated: submissionId={}, pageCount={}", submissionId, pageCount);

            // Update submission with page_count
            updatePageCount(submissionId, pageCount);

            // Get template info and validate page size
            var templateInfo = templateRepo.findTemplateInfo(submission.templateId());
            if (templateInfo.isPresent() && templateInfo.get().active()) {
                validatePageSize(pdfBytes, templateInfo.get(), message.force());
                log.debug("PDF dimensions match template: submissionId={}, templateId={}", submissionId, submission.templateId());
            }

            // Call Identity to create pseudonym (deterministic key for idempotent retries, §8/E-14)
            var pseudonymResponse = identityClient.createPseudonym(
                submission.examId(), submissionId, submissionId + ":pseudonym");
            String pseudonym = pseudonymResponse == null ? null : pseudonymResponse.pseudonym();
            log.debug("Pseudonym created and validation passed: submissionId={}", submissionId);

            // VALIDATE done: job SUCCEEDED, publish anonymize job. Submission stays
            // VALIDATING — the ANONYMIZE worker performs T06 (VALIDATING → ANONYMIZING).
            processingJobRepo.updateJobStatus(jobId, "SUCCEEDED", job.attempt() + 1);
            publishAnonymizeJob(submission, pseudonym);

        } catch (ValidationException e) {
            handleValidationError(submissionId, jobId, e, job.attempt(), message, submission);
        } catch (Exception e) {
            handleTechnicalError(submissionId, jobId, e, job.attempt(), message, submission);
        }
    }

    private int validatePdf(byte[] pdfBytes) throws ValidationException {
        try (PDDocument doc = loadPdf(pdfBytes)) {
            int pageCount = doc.getNumberOfPages();
            if (pageCount < 1 || pageCount > 20) {
                throw new ValidationException("PAGE_COUNT_INVALID",
                    "Page count must be 1-20, got: " + pageCount);
            }
            // §7.5: untrusted PDF — reject oversized pages, no retry.
            for (int i = 0; i < pageCount; i++) {
                var box = doc.getPage(i).getMediaBox();
                if (box.getWidth() > PDF_MAX_PAGE_POINTS || box.getHeight() > PDF_MAX_PAGE_POINTS) {
                    throw new ValidationException("PDF_CORRUPT",
                        "LIMIT_EXCEEDED: page " + (i + 1) + " MediaBox exceeds PDF_MAX_PAGE_POINTS");
                }
            }
            return pageCount;
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ValidationException("PDF_CORRUPT", "Failed to read PDF: " + e.getMessage());
        }
    }

    private PDDocument loadPdf(byte[] pdfBytes) throws java.io.IOException {
        return PDDocument.load(new ByteArrayInputStream(pdfBytes), "",
            MemoryUsageSetting.setupTempFileOnly());
    }

    private void validatePageSize(byte[] pdfBytes, PaperTemplateRepository.TemplateInfo template, boolean force)
            throws ValidationException {
        try (PDDocument doc = loadPdf(pdfBytes)) {
            double expectedWidth = template.pageWidth();
            double expectedHeight = template.pageHeight();
            if (template.sourcePdf() != null && template.sourcePdf().length > 0) {
                try (PDDocument source = loadPdf(template.sourcePdf())) {
                    var box = source.getPage(0).getMediaBox();
                    expectedWidth = box.getWidth();
                    expectedHeight = box.getHeight();
                }
            }
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                var page = doc.getPage(i);
                float width = page.getMediaBox().getWidth();
                float height = page.getMediaBox().getHeight();

                double widthDiff = Math.abs(width - expectedWidth) / expectedWidth * 100;
                double heightDiff = Math.abs(height - expectedHeight) / expectedHeight * 100;

                if (!force && (widthDiff > 5.0 || heightDiff > 5.0)) {
                    throw new ValidationException("TEMPLATE_SIZE_MISMATCH",
                        String.format("Page %d size mismatch: actual=%.2fx%.2f, template=%.2fx%.2f, diff=%.1f%%/%.1f%%",
                            i + 1, width, height, expectedWidth, expectedHeight, widthDiff, heightDiff));
                }
            }
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ValidationException("PDF_CORRUPT", "Failed to read PDF for size validation: " + e.getMessage());
        }
    }

    private String calculateSha256(byte[] data) throws ValidationException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new ValidationException("SHA256_CALCULATION_FAILED", "Failed to calculate SHA-256: " + e.getMessage());
        }
    }

    private void updateExamFileSha256(UUID submissionId, String sha256) {
        String sql = """
            UPDATE exam_file
            SET sha256 = ?
            WHERE submission_id = ? AND type = 'ORIGINAL' AND is_current = true
            """;
        jdbcTemplate.update(sql, sha256, submissionId);
    }

    private void updatePageCount(UUID submissionId, int pageCount) {
        String sql = """
            UPDATE submission
            SET page_count = ?, updated_at = now()
            WHERE submission_id = ?
            """;
        jdbcTemplate.update(sql, pageCount, submissionId);
    }

    private void publishAnonymizeJob(Submission submission, String pseudonym) {
        UUID anonymizeJobId = UUID.randomUUID();
        processingJobRepo.insertQueued(anonymizeJobId, submission.submissionId(), "ANONYMIZE",
            submission.currentFileVersion(), submission.templateId());
        AnonymizeJobMessage message = new AnonymizeJobMessage(
            AnonymizeJobMessage.SCHEMA_VERSION, anonymizeJobId, submission.submissionId(),
            submission.examId(), submission.templateId(), submission.currentFileVersion(),
            pseudonym, 0, UUID.randomUUID());
        log.debug("RabbitMQ send: exchange={}, routingKey=anonymize, jobId={}, submissionId={}",
            EXCHANGE, anonymizeJobId, submission.submissionId());
        rabbitTemplate.convertAndSend(EXCHANGE, "anonymize", message);
    }

    private void handleValidationError(UUID submissionId, UUID jobId, ValidationException e, int currentAttempt,
                                       ValidateJobMessage original, Submission submission) {
        // Business validation errors are not retried
        processingJobRepo.updateJobError(jobId, e.getErrorCode(), e.getMessage(), null);

        switch (e.getErrorCode()) {
            case "PDF_CORRUPT":
            case "PAGE_COUNT_INVALID":
                // T07: VALIDATING → FAILED
                int movedFailed = submissionRepo.transition(submissionId, SubmissionStatus.VALIDATING, SubmissionStatus.FAILED, 0);
                if (movedFailed > 0) {
                    updateFailureCode(submissionId, e.getErrorCode());
                    eventRepo.append(submissionId, SubmissionStatus.FAILED, 0);
                }
                break;
            case "TEMPLATE_SIZE_MISMATCH":
                // T08: VALIDATING → REVIEW_REQUIRED + Review
                int movedReview = submissionRepo.transition(submissionId, SubmissionStatus.VALIDATING, SubmissionStatus.REVIEW_REQUIRED, 0);
                if (movedReview > 0) {
                    eventRepo.append(submissionId, SubmissionStatus.REVIEW_REQUIRED, 0);
                }
                if (submission != null) {
                    try {
                        resultClient.createReview(submissionId, submission.examId(), e.getErrorCode(), e.getMessage(), "Template size mismatch");
                        log.debug("Review created for template mismatch: submissionId={}", submissionId);
                    } catch (Exception reviewError) {
                        // The state transition must survive a temporarily unavailable review service.
                        log.error("Review creation failed after marking submission REVIEW_REQUIRED: submissionId={}",
                            submissionId, reviewError);
                    }
                }
                break;
            default:
                // Unknown validation error, treat as technical error
                handleTechnicalError(submissionId, jobId, e, currentAttempt, original, submission);
        }
    }

    private void handleTechnicalError(UUID submissionId, UUID jobId, Exception e, int currentAttempt,
                                      ValidateJobMessage original, Submission submission) {
        // §8: transient errors go to the delayed retry queue; attempt > 3 → FAILED.
        log.warn("Validate job {} technical error (attempt {}): {}", jobId, currentAttempt, e.toString(), e);
        if (currentAttempt < MAX_RETRIES) {
            int retryIndex = Math.min(currentAttempt, RETRY_DELAYS_MS.length - 1);
            Instant nextRetryAt = Instant.now(clock).plusMillis(RETRY_DELAYS_MS[retryIndex]);

            processingJobRepo.updateJobError(jobId, "TECHNICAL_ERROR", e.getMessage(), nextRetryAt);
            // Persist the consumed attempt so retries actually exhaust (§8).
            processingJobRepo.updateJobStatus(jobId, "QUEUED", currentAttempt + 1);

            int nextAttempt = currentAttempt + 1;
            ValidateJobMessage retry = new ValidateJobMessage(
                ValidateJobMessage.SCHEMA_VERSION, jobId, submissionId,
                submission.examId(), submission.templateId(), submission.currentFileVersion(),
                original.force(), nextAttempt, original.correlationId());
            rabbitTemplate.convertAndSend(RETRY_QUEUES[retryIndex], retry, m -> {
                m.getMessageProperties().setHeader("x-attempt", nextAttempt);
                return m;
            });
        } else {
            // Max retries exhausted
            processingJobRepo.updateJobError(jobId, "VALIDATE_RETRY_EXHAUSTED", e.getMessage(), null);
            processingJobRepo.updateJobStatus(jobId, "FAILED", currentAttempt);

            // T07: VALIDATING → FAILED
            int moved = submissionRepo.transition(submissionId, SubmissionStatus.VALIDATING, SubmissionStatus.FAILED, 0);
            if (moved > 0) {
                updateFailureCode(submissionId, "VALIDATE_RETRY_EXHAUSTED");
                eventRepo.append(submissionId, SubmissionStatus.FAILED, 0);
            }
        }
    }

    private void updateFailureCode(UUID submissionId, String failureCode) {
        String sql = """
            UPDATE submission
            SET failure_code = ?, updated_at = now()
            WHERE submission_id = ?
            """;
        jdbcTemplate.update(sql, failureCode, submissionId);
    }

    /**
     * Validate-job message, schema §8:
     * {@code {schemaVersion:1,jobId,submissionId,examId,templateId,fileVersion,force?,attempt,correlationId}}.
     * Never carries pseudonym (validate step) or candidateId/name.
     */
    public record ValidateJobMessage(
            int schemaVersion,
            UUID jobId,
            UUID submissionId,
            UUID examId,
            UUID templateId,
            int fileVersion,
            boolean force,
            int attempt,
            UUID correlationId) {

        public static final int SCHEMA_VERSION = 1;

        public ValidateJobMessage(UUID jobId, UUID submissionId, UUID examId, UUID templateId,
                                  int fileVersion, boolean force, int attempt) {
            this(SCHEMA_VERSION, jobId, submissionId, examId, templateId, fileVersion,
                force, attempt, UUID.randomUUID());
        }
    }

    public static class ValidationException extends Exception {
        private final String errorCode;

        public ValidationException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }
}
