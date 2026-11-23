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
import vn.edu.examgrading.submission.dto.ReplaceFileRequest;
import vn.edu.examgrading.submission.dto.SubmissionIdentity;
import vn.edu.examgrading.submission.dto.SubmissionPageResponse;
import vn.edu.examgrading.submission.dto.UpdatePseudonymRequest;
import vn.edu.examgrading.submission.repository.ExamFileRepository;
import vn.edu.examgrading.submission.repository.FileReplacementRepository;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository;
import vn.edu.examgrading.submission.repository.ProcessingJobRepository;
import vn.edu.examgrading.submission.repository.SubmissionEventRepository;
import vn.edu.examgrading.submission.repository.SubmissionRepositoryMarker;
import vn.edu.examgrading.submission.storage.ObjectKeyFactory;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway.StoredObject;
import vn.edu.examgrading.submission.storage.config.StorageProperties;
import vn.edu.examgrading.submission.client.GradingClient;
import vn.edu.examgrading.submission.client.IdentityClient;
import vn.edu.examgrading.submission.client.ResultClient;
import vn.edu.examgrading.submission.messaging.AnonymizeJobMessage;
import vn.edu.examgrading.submission.messaging.ValidationWorker;

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
    private final FileReplacementRepository fileReplacementRepo;
    private final ObjectStorageGateway gateway;
    private final StorageProperties props;
    private final Clock clock;
    private final Duration uploadTtl;
    private final IdentityClient identityClient;
    private final ResultClient resultClient;
    private final GradingClient gradingClient;
    private final RabbitTemplate rabbitTemplate;
    private final JdbcTemplate jdbcTemplate;

    public SubmissionUploadServiceImpl(
            SubmissionRepositoryMarker submissionRepo,
            PaperTemplateRepository templateRepo,
            SubmissionEventRepository eventRepo,
            ExamFileRepository examFileRepo,
            ProcessingJobRepository processingJobRepo,
            FileReplacementRepository fileReplacementRepo,
            ObjectStorageGateway gateway,
            StorageProperties props,
            Clock clock,
            IdentityClient identityClient,
            ResultClient resultClient,
            GradingClient gradingClient,
            RabbitTemplate rabbitTemplate,
            JdbcTemplate jdbcTemplate,
            @Value("${app.upload.ttl-minutes:10}") long uploadTtlMinutes) {
        this.submissionRepo = submissionRepo;
        this.templateRepo = templateRepo;
        this.eventRepo = eventRepo;
        this.examFileRepo = examFileRepo;
        this.processingJobRepo = processingJobRepo;
        this.fileReplacementRepo = fileReplacementRepo;
        this.gateway = gateway;
        this.props = props;
        this.clock = clock;
        this.identityClient = identityClient;
        this.resultClient = resultClient;
        this.gradingClient = gradingClient;
        this.rabbitTemplate = rabbitTemplate;
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

    private static final Duration FILE_ACCESS_TTL = Duration.ofMinutes(5);

    @Override
    public Map<String, Object> fileAccess(UUID submissionId, String type, Integer version) {
        Submission submission = submissionRepo.findById(submissionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.INVALID_STATE));

        String bucket;
        ExamFileRepository.OriginalFile file;
        if ("ORIGINAL".equals(type)) {
            bucket = props.bucket();
            if (version == null) {
                file = examFileRepo.findCurrentByType(submissionId, "ORIGINAL")
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.INVALID_STATE));
            } else {
                if (version < 1) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_VERSION");
                }
                file = examFileRepo.findByTypeAndVersion(submissionId, "ORIGINAL", version)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.INVALID_STATE));
            }
        } else if ("ANONYMIZED".equals(type)) {
            // §4.6: during reprocess the stale anonymized object must not be served.
            if (submission.status() == SubmissionStatus.VALIDATING
                    || submission.status() == SubmissionStatus.ANONYMIZING) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "FILE_NOT_READY");
            }
            bucket = props.anonymizedBucket();
            file = examFileRepo.findCurrentByType(submissionId, "ANONYMIZED")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.INVALID_STATE));
        } else {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.INVALID_STATE);
        }

        var ticket = gateway.presignGet(bucket, file.objectKey(), FILE_ACCESS_TTL);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", ticket.url());
        result.put("expiresAt", ticket.expiresAt());
        result.put("version", file.version());
        return result;
    }

    @Override
    public Map<String, Object> createAnonymizationIssue(UUID submissionId, String reason,
            String authorization, String idempotencyKey) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "REASON_REQUIRED");
        }
        Submission submission = submissionRepo.findById(submissionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.INVALID_STATE));

        if (submission.status() == SubmissionStatus.REVIEW_REQUIRED) {
            // Idempotent replay of a completed T27: surface the unclosed
            // ANONYMIZATION review instead of erroring (spec: 201 shape).
            Map<String, Object> auth = resultClient.getReplaceAuthorization(submissionId);
            Object reviewId = auth.get("reviewId");
            if (reviewId != null && "ANONYMIZATION".equals(auth.get("reasonType"))
                    && "OPEN".equals(auth.get("status"))) {
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("reviewId", reviewId);
                res.put("status", "OPEN");
                return res;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.INVALID_STATE);
        }
        if (submission.status() != SubmissionStatus.READY_FOR_GRADING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.INVALID_STATE);
        }
        // Must have a current ANONYMIZED file (T27 precondition).
        examFileRepo.findCurrentByType(submissionId, "ANONYMIZED")
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.INVALID_STATE));

        // Order per spec: review OPEN first (idempotent), then the transition.
        // created_by is a uuid column — only a real JWT sub qualifies.
        UUID actor = actorUuidFromJwt(authorization);
        Map<String, Object> review;
        try {
            review = resultClient.createAnonymizationIssue(submissionId, submission.examId(),
                submission.currentFileVersion(), submission.templateId(), reason.trim(),
                NIL_ACTOR.equals(actor) ? null : actor.toString(), idempotencyKey);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "REVIEW_ALREADY_OPEN");
            }
            throw e;
        }
        int moved = submissionRepo.transition(submissionId,
            SubmissionStatus.READY_FOR_GRADING, SubmissionStatus.REVIEW_REQUIRED, 100);
        if (moved == 0) {
            // Another request won the transition — the review just created is
            // still the correct unclosed review; surface it idempotently.
            Submission now = submissionRepo.findById(submissionId).orElse(submission);
            if (now.status() != SubmissionStatus.REVIEW_REQUIRED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.INVALID_STATE);
            }
        } else {
            eventRepo.append(submissionId, SubmissionStatus.REVIEW_REQUIRED, 100);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("reviewId", review.get("reviewId"));
        res.put("status", "OPEN");
        return res;
    }

    @Override
    public Map<String, Object> retry(UUID submissionId, String step, Boolean force,
            UUID reviewId, UUID templateId, String authorization) {
        if (!"VALIDATE".equals(step) && !"ANONYMIZE".equals(step)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STEP");
        }
        Submission submission = submissionRepo.findById(submissionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.INVALID_STATE));

        if (submission.status() == SubmissionStatus.FAILED) {
            return retryFailed(submission, step);
        }
        if (submission.status() == SubmissionStatus.REVIEW_REQUIRED) {
            return retryReviewRequired(submission, step, force, reviewId, templateId);
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.INVALID_STATE);
    }

    /** T19: FAILED → VALIDATING/ANONYMIZING, only for retry-exhausted codes with matching step. */
    private Map<String, Object> retryFailed(Submission submission, String step) {
        String code = submission.failureCode();
        String expected = "VALIDATE_RETRY_EXHAUSTED".equals(code) ? "VALIDATE"
            : "ANONYMIZE_RETRY_EXHAUSTED".equals(code) ? "ANONYMIZE" : null;
        if (expected == null) {
            // PDF_CORRUPT/PAGE_COUNT_INVALID → replace-file (T25); others → new submission.
            throw new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.INVALID_STATE);
        }
        if (!expected.equals(step)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "STEP_MISMATCH");
        }
        UUID jobId = UUID.randomUUID();
        if ("VALIDATE".equals(step)) {
            submissionRepo.transition(submission.submissionId(), SubmissionStatus.FAILED,
                SubmissionStatus.VALIDATING, 0);
            eventRepo.append(submission.submissionId(), SubmissionStatus.VALIDATING, 0);
            processingJobRepo.insertQueued(jobId, submission.submissionId(), "VALIDATE",
                submission.currentFileVersion(), submission.templateId(), false, false);
            rabbitTemplate.convertAndSend("submission.processing", "validate",
                new ValidationWorker.ValidateJobMessage(ValidationWorker.ValidateJobMessage.SCHEMA_VERSION,
                    jobId, submission.submissionId(), submission.examId(), submission.templateId(),
                    submission.currentFileVersion(), false, 0, UUID.randomUUID()));
        } else {
            submissionRepo.transition(submission.submissionId(), SubmissionStatus.FAILED,
                SubmissionStatus.ANONYMIZING, 0);
            eventRepo.append(submission.submissionId(), SubmissionStatus.ANONYMIZING, 0);
            processingJobRepo.insertQueued(jobId, submission.submissionId(), "ANONYMIZE",
                submission.currentFileVersion(), submission.templateId(), false, false);
            String pseudonym = pseudonymFor(submission);
            rabbitTemplate.convertAndSend("submission.processing", "anonymize",
                new AnonymizeJobMessage(
                    AnonymizeJobMessage.SCHEMA_VERSION, jobId,
                    submission.submissionId(), submission.examId(), submission.templateId(),
                    submission.currentFileVersion(), pseudonym, 0, UUID.randomUUID()));
        }
        return retryResult(submission.submissionId(), step);
    }

    /** T17/T18: consume the council authorization, then requeue the pipeline step. */
    private Map<String, Object> retryReviewRequired(Submission submission, String step,
            Boolean force, UUID reviewId, UUID templateId) {
        if (reviewId == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "REVIEW_ID_REQUIRED");
        }
        Map<String, Object> state;
        try {
            state = resultClient.getReviewState(reviewId);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND");
            }
            throw e;
        }
        if (!submission.submissionId().toString().equals(String.valueOf(state.get("submissionId")))
                || !"ACTION_AUTHORIZED".equals(state.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REVIEW_NOT_AUTHORIZED");
        }
        String action = String.valueOf(state.get("authorizedAction"));
        String technicalCode = String.valueOf(state.get("technicalCode"));
        boolean isForce = "FORCE_ANONYMIZE".equals(action);
        boolean isReprocess = "REPROCESS".equals(action);
        if ((!isForce && !isReprocess)
                || (isForce && !"VALIDATE".equals(step))
                || (isReprocess && !"ANONYMIZE".equals(step))
                || (isForce && !Boolean.TRUE.equals(force))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REVIEW_NOT_AUTHORIZED");
        }
        // templateId is only valid for REPROCESS (Q-15).
        if (templateId != null && !isReprocess) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TEMPLATE_ID");
        }
        UUID effectiveTemplate = submission.templateId();
        if (templateId != null) {
            var info = templateRepo.findTemplateInfo(templateId).orElse(null);
            if (info == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND");
            }
            if (!info.active()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "TEMPLATE_INACTIVE");
            }
            effectiveTemplate = templateId;
        }
        // No confirmed grading attempt may exist (T17/T18 precondition).
        Map<String, Object> summary = gradingClient.attemptSummary(submission.submissionId());
        if (summary != null && ((Number) summary.getOrDefault("confirmedCount", 0)).longValue() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ATTEMPTS_EXIST");
        }

        // Consume the authorization first — a retry after a mid-flight failure
        // replays resolve-action idempotently.
        resultClient.resolveReviewAction(reviewId,
            isForce ? "FORCE_ANONYMIZE" : "REPROCESS");

        UUID jobId = UUID.randomUUID();
        boolean overwrite = isReprocess && "MASK_INSUFFICIENT".equals(technicalCode);
        SubmissionStatus target = isForce ? SubmissionStatus.VALIDATING : SubmissionStatus.ANONYMIZING;
        int moved = submissionRepo.transition(submission.submissionId(),
            SubmissionStatus.REVIEW_REQUIRED, target, 0);
        if (moved == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.INVALID_STATE);
        }
        eventRepo.append(submission.submissionId(), target, 0);
        if (isReprocess && templateId != null) {
            submissionRepo.updateTemplateId(submission.submissionId(), templateId);
        }
        processingJobRepo.insertQueued(jobId, submission.submissionId(), step,
            submission.currentFileVersion(), effectiveTemplate, isForce, overwrite);
        if (isForce) {
            rabbitTemplate.convertAndSend("submission.processing", "validate",
                new ValidationWorker.ValidateJobMessage(ValidationWorker.ValidateJobMessage.SCHEMA_VERSION,
                    jobId, submission.submissionId(), submission.examId(), effectiveTemplate,
                    submission.currentFileVersion(), true, 0, UUID.randomUUID()));
        } else {
            String pseudonym = pseudonymFor(submission);
            rabbitTemplate.convertAndSend("submission.processing", "anonymize",
                new AnonymizeJobMessage(
                    AnonymizeJobMessage.SCHEMA_VERSION, jobId,
                    submission.submissionId(), submission.examId(), effectiveTemplate,
                    submission.currentFileVersion(), pseudonym, 0, UUID.randomUUID()));
        }
        return retryResult(submission.submissionId(), step);
    }

    private String pseudonymFor(Submission submission) {
        var resp = identityClient.createPseudonym(submission.examId(),
            submission.submissionId(), submission.submissionId() + ":pseudonym");
        return resp == null ? null : resp.pseudonym();
    }

    private Map<String, Object> retryResult(UUID submissionId, String step) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("submissionId", submissionId);
        res.put("status", "VALIDATE".equals(step) ? SubmissionStatus.VALIDATING.name()
            : SubmissionStatus.ANONYMIZING.name());
        res.put("step", step);
        return res;
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
        log.debug("RabbitMQ send: exchange=submission.processing, routingKey=validate, jobId={}, submissionId={}",
            jobId, submissionId);
        rabbitTemplate.convertAndSend("submission.processing", "validate", validateMessage);

        // 11. Append event
        eventRepo.append(submissionId, SubmissionStatus.UPLOADED, 0);
        log.debug("Submission queued for validation: submissionId={}, jobId={}, actualBytes={}", submissionId, jobId, actualSize);

        return new CompleteSubmissionResponse(submissionId.toString(), SubmissionStatus.UPLOADED.name());
    }

    private static final java.util.Set<String> REPLACEABLE_FAILURE_CODES =
        java.util.Set.of("PDF_CORRUPT", "PAGE_COUNT_INVALID");
    private static final UUID NIL_ACTOR = new UUID(0L, 0L);

    @Override
    public Map<String, Object> replaceFile(UUID submissionId, ReplaceFileRequest request, String authorization) {
        Submission submission = submissionRepo.findById(submissionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.INVALID_STATE));

        // Payload guards (§4.4): reason mandatory, PDF only, size cap.
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "REASON_REQUIRED");
        }
        if (!PDF_CONTENT_TYPE.equalsIgnoreCase(request.contentType())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, SubmissionError.UNSUPPORTED_CONTENT_TYPE);
        }
        long declared = request.fileSizeBytes() == null ? -1 : request.fileSizeBytes();
        if (declared <= 0 || declared > props.maxPdfBytes()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, SubmissionError.INVALID_FILE_SIZE);
        }

        // Eligibility (checked again at complete): FAILED with a scan-fixable
        // code needs no review; REVIEW_REQUIRED needs an ACTION_AUTHORIZED
        // REPLACE_FILE review (D-19/T24); everything else is rejected.
        assertReplaceEligible(submission, false);

        // Q-13: a CONFIRMED grading attempt blocks replacement forever.
        Map<String, Object> summary = gradingClient.attemptSummary(submissionId);
        if (summary != null && ((Number) summary.getOrDefault("confirmedCount", 0)).longValue() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ATTEMPTS_EXIST");
        }

        Instant now = Instant.now(clock);
        // Free the pending slot from overdue rows the sweeper has not reached yet.
        fileReplacementRepo.expireOverdue(submissionId, now);
        if (fileReplacementRepo.findPending(submissionId, now).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REPLACEMENT_IN_PROGRESS");
        }

        int version = submission.currentFileVersion() + 1;
        String objectKey = ObjectKeyFactory.of(submission.examId(), submissionId, version);
        Instant uploadExpiresAt = now.plus(uploadTtl);
        UUID replacementId = UUID.randomUUID();
        UUID requestedBy = actorUuidFromJwt(authorization);
        try {
            fileReplacementRepo.insertPending(replacementId, submissionId, version, objectKey,
                declared, request.reason().trim(), uploadExpiresAt, requestedBy);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Lost the pending-slot race (partial unique index).
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REPLACEMENT_IN_PROGRESS");
        }

        ObjectStorageGateway.UploadTicket ticket = gateway.presignPost(objectKey, declared, uploadTtl);

        // Audit carries reason/version/actor only — never object key or URL (§4.4).
        jdbcTemplate.update(
            "insert into audit_log(id,actor_id,action,entity_type,entity_id,detail) values (?,?,'REPLACE_FILE_REQUESTED','submission',?,?::jsonb)",
            UUID.randomUUID(), requestedBy.equals(NIL_ACTOR) ? null : requestedBy, submissionId,
            "{\"reason\":\"" + request.reason().trim().replace("\"", "'")
                + "\",\"version\":" + version + "}");

        Map<String, Object> upload = new LinkedHashMap<>();
        upload.put("url", ticket.url());
        upload.put("fields", ticket.fields());
        Map<String, Object> replacement = new LinkedHashMap<>();
        replacement.put("replacementId", replacementId);
        replacement.put("version", version);
        replacement.put("upload", upload);
        replacement.put("uploadExpiresAt", uploadExpiresAt);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("submissionId", submissionId);
        result.put("status", submission.status().name());
        result.put("replacement", replacement);
        return result;
    }

    /** JWT {@code sub} as UUID for {@code requested_by}; nil UUID when absent. */
    private UUID actorUuidFromJwt(String authorization) {
        String sub = actorIdFromJwt(authorization);
        try {
            return UUID.fromString(sub);
        } catch (RuntimeException e) {
            return NIL_ACTOR;
        }
    }

    /**
     * §4.4 step 2. Order matters: remote saga steps (discard-open-work, review
     * resolve) run before any local mutation so a failure leaves a clean retry;
     * the local commit is one transaction; publish happens last.
     */
    @Override
    @Transactional
    public Map<String, Object> completeReplacement(UUID submissionId, UUID replacementId) {
        Submission submission = submissionRepo.findById(submissionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.INVALID_STATE));
        var replacement = fileReplacementRepo.findById(replacementId)
            .filter(r -> r.submissionId().equals(submissionId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REPLACEMENT_NOT_FOUND"));

        // Idempotent replay: already committed.
        if ("COMPLETED".equals(replacement.status())) {
            return replacementResult(submissionId, replacement.version());
        }
        Instant now = Instant.now(clock);
        if (!"PENDING".equals(replacement.status())
                || !replacement.uploadExpiresAt().isAfter(now)) {
            fileReplacementRepo.markExpired(replacementId);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REPLACEMENT_EXPIRED");
        }

        // HEAD the uploaded object in the original bucket.
        Optional<StoredObject> stored = gateway.head(props.bucket(), replacement.objectKey());
        if (stored.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.UPLOAD_NOT_FOUND);
        }
        long actualSize = stored.get().sizeBytes();
        long maxSize = Math.min(replacement.declaredSizeBytes() + props.sizeToleranceBytes(),
            props.maxPdfBytes());
        if (actualSize > maxSize) {
            // Oversized replacement: drop the object, cancel the request, keep
            // the submission's previous state untouched (§4.4).
            try { gateway.delete(props.bucket(), replacement.objectKey()); }
            catch (RuntimeException ignored) { }
            fileReplacementRepo.markCancelled(replacementId);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, SubmissionError.FILE_TOO_LARGE);
        }

        // Re-check eligibility (state may have moved since replace-file).
        assertReplaceEligible(submission, true);
        Map<String, Object> summary = gradingClient.attemptSummary(submissionId);
        if (summary != null && ((Number) summary.getOrDefault("confirmedCount", 0)).longValue() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ATTEMPTS_EXIST");
        }

        // (1) Saga remote steps — before any local write so failures retry cleanly.
        try {
            gradingClient.discardOpenWork(submissionId, replacementId);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GRADING_UNAVAILABLE");
            }
            throw e;
        }
        if (submission.status() == SubmissionStatus.REVIEW_REQUIRED) {
            resultClient.resolveReplaceReview(submissionId);
        }

        // (2) One transaction: new current ORIGINAL version + status + job marker.
        examFileRepo.clearCurrent(submissionId);
        examFileRepo.insertOriginalVersion(UUID.randomUUID(), submissionId,
            replacement.version(), actualSize, replacement.objectKey());
        submissionRepo.updateFileSizeBytes(submissionId, actualSize);
        int moved = submissionRepo.completeReplacement(submissionId, replacement.version());
        if (moved == 0) {
            // Status raced away (e.g. parallel complete) — nothing committed.
            throw new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.INVALID_STATE);
        }
        fileReplacementRepo.markCompleted(replacementId, now);
        eventRepo.append(submissionId, SubmissionStatus.UPLOADED, 0);
        // actor = requested_by of the replacement (spec §4.4 audit fields);
        // detail carries reason/version only — never object key or URL.
        UUID auditActor = NIL_ACTOR.equals(replacement.requestedBy()) ? null : replacement.requestedBy();
        jdbcTemplate.update(
            "insert into audit_log(id,actor_id,action,entity_type,entity_id,detail) values (?,?,'REPLACE_FILE_COMPLETED','submission',?,?::jsonb)",
            UUID.randomUUID(), auditActor, submissionId,
            "{\"reason\":\"" + replacement.reason().replace("\"", "'")
                + "\",\"version\":" + replacement.version() + "}");

        // (3) Publish the VALIDATE job for the new file version.
        UUID jobId = UUID.randomUUID();
        processingJobRepo.insertQueued(jobId, submissionId, "VALIDATE",
            replacement.version(), submission.templateId());
        ValidationWorker.ValidateJobMessage validateMessage = new ValidationWorker.ValidateJobMessage(
            ValidationWorker.ValidateJobMessage.SCHEMA_VERSION, jobId, submissionId,
            submission.examId(), submission.templateId(), replacement.version(),
            false, 0, UUID.randomUUID());
        rabbitTemplate.convertAndSend("submission.processing", "validate", validateMessage);

        return replacementResult(submissionId, replacement.version());
    }

    @Override
    public void cancelReplacement(UUID submissionId) {
        submissionRepo.findById(submissionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.INVALID_STATE));
        var pending = fileReplacementRepo.findPending(submissionId, Instant.now(clock))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REPLACEMENT_NOT_FOUND"));
        fileReplacementRepo.markCancelled(pending.replacementId());
        try {
            gateway.delete(props.bucket(), pending.objectKey());
        } catch (RuntimeException ignored) { }
    }

    private Map<String, Object> replacementResult(UUID submissionId, int version) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("submissionId", submissionId);
        result.put("status", SubmissionStatus.UPLOADED.name());
        result.put("version", version);
        return result;
    }

    /** Shared §4.4 eligibility: FAILED+scan code, or REVIEW_REQUIRED with an authorized (or already-resolved) REPLACE_FILE review. */
    private void assertReplaceEligible(Submission submission, boolean allowResolved) {
        if (submission.status() == SubmissionStatus.FAILED
                && submission.failureCode() != null
                && REPLACEABLE_FAILURE_CODES.contains(submission.failureCode())) {
            return;
        }
        if (submission.status() == SubmissionStatus.REVIEW_REQUIRED) {
            Map<String, Object> auth = resultClient.getReplaceAuthorization(submission.submissionId());
            boolean ok = Boolean.TRUE.equals(auth.get("authorized"))
                || (allowResolved && Boolean.TRUE.equals(auth.get("resolvedReplaceFile")));
            if (!ok) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "REVIEW_NOT_AUTHORIZED");
            }
            return;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "REPLACE_NOT_ALLOWED");
    }
}
