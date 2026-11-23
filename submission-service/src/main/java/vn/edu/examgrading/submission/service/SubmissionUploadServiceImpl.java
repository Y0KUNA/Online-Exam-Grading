package vn.edu.examgrading.submission.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.examgrading.submission.domain.Submission;
import vn.edu.examgrading.submission.domain.SubmissionError;
import vn.edu.examgrading.submission.domain.SubmissionStatus;
import vn.edu.examgrading.submission.dto.CompleteSubmissionResponse;
import vn.edu.examgrading.submission.dto.InitSubmissionRequest;
import vn.edu.examgrading.submission.dto.InitSubmissionResponse;
import vn.edu.examgrading.submission.dto.SubmissionIdentity;
import vn.edu.examgrading.submission.dto.SubmissionPageResponse;
import vn.edu.examgrading.submission.dto.UpdatePseudonymRequest;
import vn.edu.examgrading.submission.repository.ExamFileRepository;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository;
import vn.edu.examgrading.submission.repository.ProcessingJobRepository;
import vn.edu.examgrading.submission.repository.SubmissionEventRepository;
import vn.edu.examgrading.submission.repository.SubmissionRepositoryMarker;
import vn.edu.examgrading.submission.storage.ObjectKeyFactory;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway.StoredObject;
import vn.edu.examgrading.submission.storage.config.StorageProperties;
import vn.edu.examgrading.submission.client.IdentityClient;
import vn.edu.examgrading.submission.messaging.ValidationWorker;
import vn.edu.examgrading.submission.messaging.SubmissionOutbox;

/**
 * JDBC-backed implementation of {@link SubmissionServiceMarker} (TICKET 04).
 * Refactored from the slice-era {@code SubmissionUploadService} to use repositories
 * instead of direct JDBC. Guards translate to 422/404/409; DuplicateKeyException
 * surfaces as 409 CANDIDATE_ALREADY_BOUND. Transaction boundary lives here.
 */
@Service
public class SubmissionUploadServiceImpl implements SubmissionServiceMarker {

    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final int DEFAULT_PAGE_LIMIT = 50;
    private static final int MAX_PAGE_LIMIT = 200;
    private static final Logger log = LoggerFactory.getLogger(SubmissionUploadServiceImpl.class);

    private final SubmissionRepositoryMarker submissionRepo;
    private final PaperTemplateRepository templateRepo;
    private final SubmissionEventRepository eventRepo;
    private final ExamFileRepository examFileRepo;
    private final ProcessingJobRepository processingJobRepo;
    private final ObjectStorageGateway gateway;
    private final StorageProperties props;
    private final Clock clock;
    private final Duration uploadTtl;
    private final IdentityClient identityClient;
    private final RabbitTemplate rabbitTemplate;
    private final SubmissionOutbox outbox;
    private final JdbcTemplate jdbcTemplate;

    public SubmissionUploadServiceImpl(
            SubmissionRepositoryMarker submissionRepo,
            PaperTemplateRepository templateRepo,
            SubmissionEventRepository eventRepo,
            ExamFileRepository examFileRepo,
            ProcessingJobRepository processingJobRepo,
            ObjectStorageGateway gateway,
            StorageProperties props,
            Clock clock,
            IdentityClient identityClient,
            RabbitTemplate rabbitTemplate,
            SubmissionOutbox outbox,
            JdbcTemplate jdbcTemplate,
            @Value("${app.upload.ttl-minutes:10}") long uploadTtlMinutes) {
        this.submissionRepo = submissionRepo;
        this.templateRepo = templateRepo;
        this.eventRepo = eventRepo;
        this.examFileRepo = examFileRepo;
        this.processingJobRepo = processingJobRepo;
        this.gateway = gateway;
        this.props = props;
        this.clock = clock;
        this.identityClient = identityClient;
        this.rabbitTemplate = rabbitTemplate;
        this.outbox = outbox;
        this.jdbcTemplate = jdbcTemplate;
        this.uploadTtl = Duration.ofMinutes(uploadTtlMinutes);
    }

    @Override
    @Transactional
    public InitSubmissionResponse init(UUID examId, InitSubmissionRequest request, UUID actorId, String authorization) {
        log.debug("Starting submission upload: examId={}, candidateId={}, declaredBytes={}",
                examId, request.candidateId(), request.fileSizeBytes());
        // 1. Content-type + declared-size guards first — reject before touching the DB.
        if (!PDF_CONTENT_TYPE.equalsIgnoreCase(request.contentType())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, SubmissionError.UNSUPPORTED_CONTENT_TYPE);
        }
        long declared = request.fileSizeBytes();
        if (declared <= 0 || declared > props.maxPdfBytes()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, SubmissionError.INVALID_FILE_SIZE);
        }

        // 2. The template must exist and be active.
        if (!templateRepo.existsActive(request.templateId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.TEMPLATE_NOT_FOUND);
        }

        // 3. Reserve the submission in UPLOADING with a deterministic, PII-free key.
        // T01: (1) insert Submission UPLOADING, commit
        UUID submissionId = UUID.randomUUID();
        String objectKey = ObjectKeyFactory.of(examId, submissionId, 1,
                request.examName(), request.examTime(), request.subject(), request.candidateName());
        Instant uploadExpiresAt = Instant.now(clock).plus(uploadTtl);
        submissionRepo.insertUploading(submissionId, examId, request.templateId(),
            uploadExpiresAt, declared, objectKey);

        // T01: (2) call Identity submission-bindings; error 409 → rollback
        try {
            identityClient.createBinding(examId, submissionId, request.candidateId(), authorization);
        } catch (ResponseStatusException e) {
            // CANDIDATE_ALREADY_BOUND or CANDIDATE_EXAM_MISMATCH
            // Delete the submission row just created and propagate error
            submissionRepo.deleteById(submissionId);
            throw e;
        }

        // 4. Sign the presigned POST ticket.
        ObjectStorageGateway.UploadTicket ticket = gateway.presignPost(objectKey, declared, uploadTtl);
        log.debug("Submission upload initialized: submissionId={}, expiresAt={}", submissionId, uploadExpiresAt);

        // 5. Append SSE event.
        eventRepo.append(submissionId, SubmissionStatus.UPLOADING, 0);

        return new InitSubmissionResponse(
            submissionId.toString(),
            SubmissionStatus.UPLOADING.name(),
            new InitSubmissionResponse.Upload(ticket.url(), ticket.fields()),
            uploadExpiresAt,
            "/api/v1/submissions/" + submissionId);
    }

    @Override
    public SubmissionPageResponse listForExam(UUID examId, String status, String cursor, Integer limit) {
        SubmissionStatus statusFilter = parseStatusFilter(status);
        int pageLimit = parseLimit(limit);
        CursorPosition position = decodeCursor(cursor);

        // Fetch one extra row to know whether a next page exists (D-06).
        List<Submission> rows = submissionRepo.findByExamPage(examId, statusFilter,
            position == null ? null : position.updatedAt(),
            position == null ? null : position.submissionId(),
            pageLimit + 1);
        boolean hasMore = rows.size() > pageLimit;
        List<Submission> page = hasMore ? rows.subList(0, pageLimit) : rows;
        String nextCursor = hasMore && !page.isEmpty()
            ? encodeCursor(page.get(page.size() - 1))
            : null;
        List<Map<String, Object>> items = page.stream()
            .map(this::submissionToDetailMap)
            .collect(Collectors.toList());
        return new SubmissionPageResponse(items, nextCursor);
    }

    @Override
    public Map<String, Object> detail(UUID submissionId) {
        return submissionRepo.findById(submissionId)
            .map(this::submissionToDetailMap)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.INVALID_STATE));
    }

    /**
     * Public projection shared by detail and list items (spec 05):
     * {@code {submissionId,examId,status,pageCount?,progress,error?:{code,message},updatedAt}}.
     * Internal fields (objectKey/templateId/version/…) stay out (E-06: EXAM_OFFICE only).
     */
    private Map<String, Object> submissionToDetailMap(Submission s) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("submissionId", s.submissionId());
        result.put("examId", s.examId());
        result.put("status", s.status().name());
        if (s.pageCount() != null) {
            result.put("pageCount", s.pageCount());
        }
        result.put("progress", s.progress());
        if (s.failureCode() != null) {
            result.put("error", Map.of("code", s.failureCode(), "message", s.failureCode()));
        }
        result.put("updatedAt", s.updatedAt());
        return result;
    }

    @Override
    public Map<String, Object> identity(UUID submissionId) {
        Submission submission = submissionRepo.findById(submissionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.INVALID_STATE));
        SubmissionIdentity identity;
        try {
            identity = identityClient.getSubmissionIdentity(submissionId);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.INVALID_STATE);
            }
            throw e;
        }
        if (identity == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.INVALID_STATE);
        }

        // FR-019: ghép phách is a sensitive join — always audit the access.
        // Detail carries no PII (candidate fields/pseudonym stay out of the log).
        jdbcTemplate.update(
            "insert into audit_log(id,actor_id,action,entity_type,entity_id,detail) values (?,null,'SUBMISSION_IDENTITY_VIEWED','submission',?,?::jsonb)",
            UUID.randomUUID(), submissionId,
            "{\"examId\":\"" + submission.examId() + "\"}");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidateNumber", identity.candidateNumber());
        result.put("fullName", identity.fullName());
        result.put("className", identity.className());
        result.put("pseudonym", identity.pseudonym());
        return result;
    }

    private static final Pattern JWT_SUB = Pattern.compile("\"sub\"\\s*:\\s*\"([^\"]+)\"");

    @Override
    public Map<String, Object> updatePseudonym(UUID submissionId, UpdatePseudonymRequest request, String authorization) {
        Submission submission = submissionRepo.findById(submissionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.INVALID_STATE));
        // Spec 10 §4.1: chỉ khi chưa FINALIZED.
        if (submission.status() == SubmissionStatus.FINALIZED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "INVALID_STATE");
        }
        if (request == null || request.pseudonym() == null || request.pseudonym().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PSEUDONYM_REQUIRED");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "REASON_REQUIRED");
        }

        try {
            identityClient.updatePseudonym(submissionId, request.pseudonym().trim(),
                request.reason(), actorIdFromJwt(authorization));
        } catch (ResponseStatusException e) {
            // No pseudonym issued yet (pre-VALIDATE) → conflict; PSEUDONYM_TAKEN
            // (409) propagates with its own reason.
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "INVALID_STATE");
            }
            throw e;
        }

        jdbcTemplate.update(
            "insert into audit_log(id,actor_id,action,entity_type,entity_id,detail) values (?,null,'SUBMISSION_PSEUDONYM_CHANGED','submission',?,?::jsonb)",
            UUID.randomUUID(), submissionId,
            "{\"examId\":\"" + submission.examId() + "\"}");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("submissionId", submissionId);
        result.put("pseudonym", request.pseudonym().trim());
        return result;
    }

    /** Best-effort {@code sub} claim read; the Gateway already verified the JWT. */
    private String actorIdFromJwt(String authorization) {
        try {
            if (authorization != null && authorization.startsWith("Bearer ")) {
                String[] parts = authorization.substring(7).split("\\.");
                if (parts.length >= 2) {
                    String payload = new String(
                        Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                    var matcher = JWT_SUB.matcher(payload);
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                }
            }
        } catch (RuntimeException ignored) { }
        return "EXAM_OFFICE";
    }

    @Override
    public Map<String, Object> files(UUID submissionId) {
        submissionRepo.findById(submissionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.INVALID_STATE));
        List<Map<String, Object>> items = examFileRepo.findAllBySubmission(submissionId).stream()
            .map(f -> {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("type", f.type());
                m.put("version", f.version());
                m.put("isCurrent", f.isCurrent());
                if (f.sizeBytes() != null) {
                    m.put("sizeBytes", f.sizeBytes());
                }
                if (f.sha256() != null) {
                    m.put("sha256", f.sha256());
                }
                m.put("createdAt", f.createdAt());
                return m;
            })
            .collect(Collectors.toList());
        return Map.of("items", items);
    }

    private SubmissionStatus parseStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return SubmissionStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }
    }

    private int parseLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_PAGE_LIMIT;
        }
        if (limit < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT");
        }
        return Math.min(limit, MAX_PAGE_LIMIT);
    }

    private record CursorPosition(Instant updatedAt, UUID submissionId) { }

    // Opaque cursor = base64url("<updatedAt ISO>|<submissionId>"). ISO-8601 keeps
    // full timestamp precision so keyset pagination does not skip/duplicate rows.
    private String encodeCursor(Submission s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            (s.updatedAt() + "|" + s.submissionId()).getBytes(StandardCharsets.UTF_8));
    }

    private CursorPosition decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int sep = decoded.lastIndexOf('|');
            return new CursorPosition(
                Instant.parse(decoded.substring(0, sep)),
                UUID.fromString(decoded.substring(sep + 1)));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR");
        }
    }

    @Override
    @Transactional
    public CompleteSubmissionResponse complete(UUID submissionId) {
        log.debug("Completing submission upload: submissionId={}", submissionId);
        // 1. Fetch submission
        Submission submission = submissionRepo.findById(submissionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.INVALID_STATE));

        // 2. Idempotent: already UPLOADED
        if (submission.status() == SubmissionStatus.UPLOADED) {
            return new CompleteSubmissionResponse(submissionId.toString(), SubmissionStatus.UPLOADED.name());
        }

        // 3. Must be in UPLOADING state
        if (submission.status() != SubmissionStatus.UPLOADING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.INVALID_STATE);
        }

        // 4. Check upload expiry
        Instant now = Instant.now(clock);
        if (submission.uploadExpiresAt() != null && now.isAfter(submission.uploadExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.UPLOAD_EXPIRED);
        }

        // 5. Verify file exists in storage
        String objectKey = submission.objectKey();
        if (objectKey == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.UPLOAD_NOT_FOUND);
        }

        Optional<StoredObject> stored = gateway.head(objectKey);
        if (stored.isEmpty()) {
            log.debug("Submission upload object is not yet available: submissionId={}", submissionId);
            throw new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.UPLOAD_NOT_FOUND);
        }

        long actualSize = stored.get().sizeBytes();
        long declaredSize = submission.declaredFileSizeBytes();
        long maxSize = Math.min(declaredSize + props.sizeToleranceBytes(), props.maxPdfBytes());

        // 6. Validate file size
        if (actualSize > maxSize) {
            log.debug("Submission upload exceeds allowed size: submissionId={}, actualBytes={}, allowedBytes={}", submissionId, actualSize, maxSize);
            gateway.delete(objectKey);
            // T04: Release binding when FILE_TOO_LARGE
            identityClient.releaseBinding(submissionId, "FILE_TOO_LARGE");
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, SubmissionError.FILE_TOO_LARGE);
        }

        // 7. Update file_size_bytes in submission
        submissionRepo.updateFileSizeBytes(submissionId, actualSize);

        // 8. Insert exam_file row (idempotent via unique constraint on submission_id+type)
        if (!examFileRepo.existsCurrentOriginal(submissionId)) {
            examFileRepo.insertOriginal(
                UUID.randomUUID(),
                submissionId,
                actualSize,
                objectKey
            );
        }

        // 8. Transition to UPLOADED
        int updated = submissionRepo.transition(
            submissionId,
            SubmissionStatus.UPLOADING,
            SubmissionStatus.UPLOADED,
            0
        );

        if (updated == 0) {
            // Lost race - check if already UPLOADED
            Submission current = submissionRepo.findById(submissionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.INVALID_STATE));
            if (current.status() == SubmissionStatus.UPLOADED) {
                return new CompleteSubmissionResponse(submissionId.toString(), SubmissionStatus.UPLOADED.name());
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.INVALID_STATE);
        }

        // 9. Enqueue processing job
        UUID jobId = UUID.randomUUID();
        processingJobRepo.insertQueued(jobId, submissionId, "VALIDATE",
            submission.currentFileVersion(), submission.templateId());

        // 10. Publish validate job to RabbitMQ (schema §8 — full message fields)
        ValidationWorker.ValidateJobMessage validateMessage = new ValidationWorker.ValidateJobMessage(
                ValidationWorker.ValidateJobMessage.SCHEMA_VERSION, jobId, submissionId,
                submission.examId(), submission.templateId(), submission.currentFileVersion(),
                false, 0, UUID.randomUUID());
        outbox.enqueueValidate(validateMessage);

        // 11. Append event
        eventRepo.append(submissionId, SubmissionStatus.UPLOADED, 0);
        log.debug("Submission queued for validation: submissionId={}, jobId={}, actualBytes={}", submissionId, jobId, actualSize);

        return new CompleteSubmissionResponse(submissionId.toString(), SubmissionStatus.UPLOADED.name());
    }

    /**
     * Office-controlled recovery for a run which has exhausted automatic retries
     * or needs an authorised template-size override. The original object is
     * retained; a missing object is deliberately not retried because the office
     * must upload a replacement instead.
     */
    @Override
    @Transactional
    public Map<String, Object> retryValidation(UUID submissionId, boolean forceTemplateSize, boolean recoverStalled) {
        Submission submission = submissionRepo.findById(submissionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.INVALID_STATE));
        if (submission.status() == SubmissionStatus.VALIDATING) {
            var queued = processingJobRepo.findJobBySubmissionAndStep(submissionId, "VALIDATE")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.INVALID_STATE));
            if (!"QUEUED".equals(queued.status())) {
                if (!recoverStalled || "SUCCEEDED".equals(queued.status()) || "COMPLETED".equals(queued.status())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.INVALID_STATE);
                }
                processingJobRepo.updateJobStatus(queued.jobId(), "QUEUED", queued.attempt());
            }
            ValidationWorker.ValidateJobMessage recovery = new ValidationWorker.ValidateJobMessage(
                ValidationWorker.ValidateJobMessage.SCHEMA_VERSION, queued.jobId(), submissionId,
                submission.examId(), queued.templateId(), queued.fileVersion(), queued.force(), 0, UUID.randomUUID());
            outbox.enqueueValidate(recovery);
            return Map.of("submissionId", submissionId, "status", SubmissionStatus.VALIDATING.name(),
                "jobId", queued.jobId(), "recovered", true);
        }
        if (submission.status() != SubmissionStatus.FAILED && submission.status() != SubmissionStatus.REVIEW_REQUIRED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.INVALID_STATE);
        }
        if (forceTemplateSize && (submission.status() != SubmissionStatus.REVIEW_REQUIRED
                || !"TEMPLATE_SIZE_MISMATCH".equals(submission.failureCode()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.INVALID_STATE);
        }
        if (submission.objectKey() == null || gateway.head(submission.objectKey()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.UPLOAD_NOT_FOUND);
        }

        int moved = submissionRepo.transition(submissionId, submission.status(), SubmissionStatus.VALIDATING, 50);
        if (moved == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.INVALID_STATE);
        submissionRepo.clearFailureCode(submissionId);
        eventRepo.append(submissionId, SubmissionStatus.VALIDATING, 50);

        UUID jobId = UUID.randomUUID();
        processingJobRepo.insertQueued(jobId, submissionId, "VALIDATE",
            submission.currentFileVersion(), submission.templateId());
        ValidationWorker.ValidateJobMessage message = new ValidationWorker.ValidateJobMessage(
            ValidationWorker.ValidateJobMessage.SCHEMA_VERSION, jobId, submissionId,
            submission.examId(), submission.templateId(), submission.currentFileVersion(),
            forceTemplateSize, 0, UUID.randomUUID());
        outbox.enqueueValidate(message);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("submissionId", submissionId);
        result.put("status", SubmissionStatus.VALIDATING.name());
        result.put("jobId", jobId);
        result.put("forceTemplateSize", forceTemplateSize);
        return result;
    }
}
