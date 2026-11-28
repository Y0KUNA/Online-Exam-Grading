package vn.edu.examgrading.submission.messaging;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.examgrading.submission.anonymize.PageMasker;
import vn.edu.examgrading.submission.domain.SubmissionStatus;
import vn.edu.examgrading.submission.repository.ExamFileRepository;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository;
import vn.edu.examgrading.submission.repository.ProcessingJobRepository;
import vn.edu.examgrading.submission.repository.SubmissionEventRepository;
import vn.edu.examgrading.submission.repository.SubmissionRepositoryMarker;
import vn.edu.examgrading.submission.storage.ObjectKeyFactory;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway;
import vn.edu.examgrading.submission.storage.config.StorageProperties;
import vn.edu.examgrading.submission.client.ResultClient;

/**
 * Worker consumer for ANONYMIZE jobs (TICKET 03, spec §7/§8).
 * On receipt performs T06 (VALIDATING → ANONYMIZING), downloads ORIGINAL,
 * cuts configured edge strips via {@link PageMasker}, writes the ANONYMIZED object to the
 * anonymized bucket and moves the submission to READY_FOR_GRADING (T09).
 * Technical errors retry via {@code submission.anonymize.retry.30s|2m|10m}
 * (max 3 retries), then FAILED with {@code ANONYMIZE_RETRY_EXHAUSTED};
 * template errors go to REVIEW_REQUIRED without retry (T11).
 */
@Component
public class AnonymizationWorker {

    private static final Logger log = LoggerFactory.getLogger(AnonymizationWorker.class);
    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {30_000L, 120_000L, 600_000L};
    private static final String[] RETRY_QUEUES = {
        "submission.anonymize.retry.30s",
        "submission.anonymize.retry.2m",
        "submission.anonymize.retry.10m"
    };
    private static final String DLQ = "submission.anonymize.dlq";
    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final RabbitTemplate rabbitTemplate;
    private final ObjectStorageGateway storageGateway;
    private final SubmissionRepositoryMarker submissionRepo;
    private final ProcessingJobRepository processingJobRepo;
    private final PaperTemplateRepository templateRepo;
    private final ExamFileRepository examFileRepo;
    private final SubmissionEventRepository eventRepo;
    private final ResultClient resultClient;
    private final StorageProperties storageProps;
    private final PageMasker pageMasker;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public AnonymizationWorker(
            RabbitTemplate rabbitTemplate,
            ObjectStorageGateway storageGateway,
            SubmissionRepositoryMarker submissionRepo,
            ProcessingJobRepository processingJobRepo,
            PaperTemplateRepository templateRepo,
            ExamFileRepository examFileRepo,
            SubmissionEventRepository eventRepo,
            ResultClient resultClient,
            StorageProperties storageProps,
            PageMasker pageMasker,
            JdbcTemplate jdbcTemplate,
            Clock clock) {
        this.rabbitTemplate = rabbitTemplate;
        this.storageGateway = storageGateway;
        this.submissionRepo = submissionRepo;
        this.processingJobRepo = processingJobRepo;
        this.templateRepo = templateRepo;
        this.examFileRepo = examFileRepo;
        this.eventRepo = eventRepo;
        this.resultClient = resultClient;
        this.storageProps = storageProps;
        this.pageMasker = pageMasker;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @RabbitListener(queues = "submission.anonymize.v1")
    @Transactional
    public void processAnonymizeJob(AnonymizeJobMessage message) {
        // §8: messages failing schema validation go straight to the DLQ, no retry.
        if (message == null || message.schemaVersion() != AnonymizeJobMessage.SCHEMA_VERSION
                || message.jobId() == null || message.submissionId() == null) {
            log.warn("Invalid anonymize message schema, routing to DLQ");
            if (message != null) {
                rabbitTemplate.convertAndSend(DLQ, message);
            }
            return;
        }
        UUID submissionId = message.submissionId();
        UUID jobId = message.jobId();
        log.debug("RabbitMQ receive: queue=submission.anonymize.v1, jobId={}, submissionId={}, attempt={}",
            jobId, submissionId, message.attempt());

        // §8 idempotency: keyed on jobId; skip when this job already succeeded.
        var job = processingJobRepo.findJobById(jobId).orElse(null);
        if (job == null) {
            log.warn("Anonymize job {} not visible yet; delaying once", jobId);
            rabbitTemplate.convertAndSend(RETRY_QUEUES[0], message);
            return;
        }
        if (!job.submissionId().equals(submissionId) || !"ANONYMIZE".equals(job.step())) {
            log.warn("Anonymize message jobId {} does not match its job row, routing to DLQ", jobId);
            rabbitTemplate.convertAndSend(DLQ, message);
            return;
        }
        if ("SUCCEEDED".equals(job.status()) || "COMPLETED".equals(job.status())) {
            return;
        }

        try {
            var submission = submissionRepo.findById(submissionId)
                .orElseThrow(() -> new IllegalStateException("Submission not found: " + submissionId));

            // §8 stale-message guard: fileVersion must match the current file version.
            // Stale messages are acked without touching the job or submission (§8).
            if (message.fileVersion() != submission.currentFileVersion()) {
                log.info("Skipping anonymize for stale fileVersion {} (current {})",
                    message.fileVersion(), submission.currentFileVersion());
                return;
            }

            // Idempotent replay: already READY_FOR_GRADING and not an overwrite job.
            // §8: overwrite is read from ProcessingJob, not the message.
            if (submission.status() == SubmissionStatus.READY_FOR_GRADING && !job.overwrite()) {
                processingJobRepo.updateJobStatus(jobId, "SUCCEEDED", job.attempt() + 1);
                return;
            }

            // §8: overwrite jobs (T18/T27 reprocess) rebuild the ANONYMIZED file in
            // place. Upstream normally moves the submission to ANONYMIZING first;
            // tolerate READY_FOR_GRADING so a reprocess is never mistaken for a
            // transient error and retried to exhaustion.
            boolean overwriteInPlace = job.overwrite()
                && submission.status() == SubmissionStatus.READY_FOR_GRADING;

            // T06: VALIDATING → ANONYMIZING happens when this worker picks up the job.
            if (submission.status() == SubmissionStatus.VALIDATING) {
                int moved = submissionRepo.transition(submissionId, SubmissionStatus.VALIDATING,
                    SubmissionStatus.ANONYMIZING, 75);
                if (moved > 0) {
                    eventRepo.append(submissionId, SubmissionStatus.ANONYMIZING, 75);
                }
            } else if (submission.status() != SubmissionStatus.ANONYMIZING && !overwriteInPlace) {
                throw new IllegalStateException(
                    "Unexpected submission status for anonymize: " + submission.status());
            }

            processingJobRepo.updateJobStatus(jobId, "PROCESSING", job.attempt() + 1);

            var original = examFileRepo.findCurrentOriginal(submissionId)
                .orElseThrow(() -> new IllegalStateException("ORIGINAL exam_file missing: " + submissionId));

            byte[] originalBytes = storageGateway.get(original.objectKey());
            log.debug("Loaded original PDF for masking: submissionId={}, bytes={}", submissionId, originalBytes.length);
            // §8: templateId comes from ProcessingJob (reprocess may override it).
            UUID maskTemplateId = job.templateId() != null ? job.templateId() : submission.templateId();
            var regions = templateRepo.findMaskRegions(maskTemplateId)
                .orElseThrow(() -> new PageMasker.MaskException(
                    "TEMPLATE_MASK_ERROR", "Template has no mask regions"));

            byte[] anonymizedBytes = pageMasker.anonymize(originalBytes, regions, message.pseudonym());
            String sha256 = calculateSha256(anonymizedBytes);

            String anonymizedKey = ObjectKeyFactory.of(
                submission.examId(), submissionId, original.version());
            storageGateway.put(storageProps.anonymizedBucket(), anonymizedKey,
                anonymizedBytes, PDF_CONTENT_TYPE);

            if (examFileRepo.updateAnonymized(submissionId, anonymizedBytes.length, anonymizedKey, sha256) == 0) {
                examFileRepo.insertAnonymized(UUID.randomUUID(), submissionId, original.version(),
                    anonymizedBytes.length, anonymizedKey, sha256);
            }

            int moved = submissionRepo.transition(submissionId, SubmissionStatus.ANONYMIZING,
                SubmissionStatus.READY_FOR_GRADING, 100);
            if (moved > 0) {
                eventRepo.append(submissionId, SubmissionStatus.READY_FOR_GRADING, 100);
            }
            processingJobRepo.updateJobStatus(jobId, "SUCCEEDED", job.attempt() + 1);
            log.debug("Anonymization completed: submissionId={}, outputBytes={}", submissionId, anonymizedBytes.length);

        } catch (PageMasker.MaskException e) {
            handleTemplateError(submissionId, jobId, e, job.attempt());
        } catch (PageMasker.LimitExceededException e) {
            handleLimitExceeded(submissionId, jobId, e, job.attempt());
        } catch (Exception e) {
            handleTechnicalError(submissionId, jobId, e, job.attempt(), message);
        }
    }

    private void handleTemplateError(UUID submissionId, UUID jobId, PageMasker.MaskException e, int attempt) {
        // Business/template error: no retry → REVIEW_REQUIRED + Review (T11).
        processingJobRepo.updateJobError(jobId, e.getTechnicalCode(), e.getMessage(), null);
        submissionRepo.transition(submissionId, SubmissionStatus.ANONYMIZING,
            SubmissionStatus.REVIEW_REQUIRED, 0);
        eventRepo.append(submissionId, SubmissionStatus.REVIEW_REQUIRED, 0);
        var submission = submissionRepo.findById(submissionId).orElse(null);
        if (submission != null) {
            try {
                resultClient.createReview(submissionId, submission.examId(),
                    e.getTechnicalCode(), e.getMessage(), "Anonymization template error");
                log.debug("Review created for anonymization template error: submissionId={}", submissionId);
            } catch (Exception reviewError) {
                log.error("Review creation failed after marking submission REVIEW_REQUIRED: submissionId={}",
                    submissionId, reviewError);
            }
        }
    }

    private void handleLimitExceeded(UUID submissionId, UUID jobId, PageMasker.LimitExceededException e, int attempt) {
        // §7.5: limit violations are FAILED/PDF_CORRUPT with LIMIT_EXCEEDED, no retry.
        processingJobRepo.updateJobError(jobId, "PDF_CORRUPT", "LIMIT_EXCEEDED: " + e.getMessage(), null);
        processingJobRepo.updateJobStatus(jobId, "FAILED", attempt);
        int moved = submissionRepo.transition(submissionId, SubmissionStatus.ANONYMIZING, SubmissionStatus.FAILED, 0);
        if (moved > 0) {
            updateFailureCode(submissionId, "PDF_CORRUPT");
            eventRepo.append(submissionId, SubmissionStatus.FAILED, 0);
        }
    }

    private void handleTechnicalError(UUID submissionId, UUID jobId, Exception e, int currentAttempt,
                                      AnonymizeJobMessage original) {
        // §8: transient errors go to the delayed retry queue; attempt > 3 → FAILED.
        log.warn("Anonymize job {} technical error (attempt {}): {}", jobId, currentAttempt, e.toString(), e);
        if (currentAttempt < MAX_RETRIES) {
            int retryIndex = Math.min(currentAttempt, RETRY_DELAYS_MS.length - 1);
            Instant nextRetryAt = Instant.now(clock).plusMillis(RETRY_DELAYS_MS[retryIndex]);

            processingJobRepo.updateJobError(jobId, "TECHNICAL_ERROR", e.getMessage(), nextRetryAt);
            // Persist the consumed attempt so retries actually exhaust (§8).
            processingJobRepo.updateJobStatus(jobId, "QUEUED", currentAttempt + 1);

            int nextAttempt = currentAttempt + 1;
            AnonymizeJobMessage retry = new AnonymizeJobMessage(
                original.schemaVersion(), original.jobId(), original.submissionId(),
                original.examId(), original.templateId(), original.fileVersion(),
                original.pseudonym(), nextAttempt, original.correlationId());
            rabbitTemplate.convertAndSend(RETRY_QUEUES[retryIndex], retry, m -> {
                m.getMessageProperties().setHeader("x-attempt", nextAttempt);
                return m;
            });
        } else {
            processingJobRepo.updateJobError(jobId, "ANONYMIZE_RETRY_EXHAUSTED", e.getMessage(), null);
            processingJobRepo.updateJobStatus(jobId, "FAILED", currentAttempt);

            // T10: only mark FAILED when the transition actually applies —
            // never stamp failure_code onto a submission in another state.
            int moved = submissionRepo.transition(submissionId, SubmissionStatus.ANONYMIZING, SubmissionStatus.FAILED, 0);
            if (moved > 0) {
                updateFailureCode(submissionId, "ANONYMIZE_RETRY_EXHAUSTED");
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

    private String calculateSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 calculation failed", e);
        }
    }
}
