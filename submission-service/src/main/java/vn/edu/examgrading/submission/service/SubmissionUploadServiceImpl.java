package vn.edu.examgrading.submission.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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

/**
 * JDBC-backed implementation of {@link SubmissionServiceMarker} (TICKET 04).
 * Refactored from the slice-era {@code SubmissionUploadService} to use repositories
 * instead of direct JDBC. Guards translate to 422/404/409; DuplicateKeyException
 * surfaces as 409 CANDIDATE_ALREADY_BOUND. Transaction boundary lives here.
 */
@Service
public class SubmissionUploadServiceImpl implements SubmissionServiceMarker {

    private static final String PDF_CONTENT_TYPE = "application/pdf";
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
    public List<Map<String, Object>> listForExam(UUID examId) {
        return submissionRepo.findByExam(examId).stream()
            .map(this::submissionToMap)
            .collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> detail(UUID submissionId) {
        return submissionRepo.findById(submissionId)
            .map(this::submissionToMap)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SubmissionError.INVALID_STATE));
    }

    private Map<String, Object> submissionToMap(Submission s) {
        // Uploading submissions legitimately have null fileSizeBytes/failureCode.
        // Map.of/Map.entry rejects null and used to turn the list API into HTTP 500.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("submissionId", s.submissionId());
        result.put("examId", s.examId());
        result.put("templateId", s.templateId());
        result.put("status", s.status().name());
        result.put("currentFileVersion", s.currentFileVersion());
        result.put("progress", s.progress());
        result.put("uploadExpiresAt", s.uploadExpiresAt());
        result.put("declaredFileSizeBytes", s.declaredFileSizeBytes());
        result.put("fileSizeBytes", s.fileSizeBytes());
        result.put("objectKey", s.objectKey());
        result.put("failureCode", s.failureCode());
        result.put("version", s.version());
        result.put("updatedAt", s.updatedAt());
        return result;
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
}
