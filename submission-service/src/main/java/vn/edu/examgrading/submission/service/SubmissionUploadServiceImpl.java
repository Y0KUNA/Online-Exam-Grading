package vn.edu.examgrading.submission.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
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

/**
 * JDBC-backed implementation of {@link SubmissionServiceMarker} (TICKET 04).
 * Refactored from the slice-era {@code SubmissionUploadService} to use repositories
 * instead of direct JDBC. Guards translate to 422/404/409; DuplicateKeyException
 * surfaces as 409 CANDIDATE_ALREADY_BOUND. Transaction boundary lives here.
 */
@Service
public class SubmissionUploadServiceImpl implements SubmissionServiceMarker {

    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final SubmissionRepositoryMarker submissionRepo;
    private final PaperTemplateRepository templateRepo;
    private final SubmissionEventRepository eventRepo;
    private final ExamFileRepository examFileRepo;
    private final ProcessingJobRepository processingJobRepo;
    private final ObjectStorageGateway gateway;
    private final StorageProperties props;
    private final Clock clock;
    private final Duration uploadTtl;

    public SubmissionUploadServiceImpl(
            SubmissionRepositoryMarker submissionRepo,
            PaperTemplateRepository templateRepo,
            SubmissionEventRepository eventRepo,
            ExamFileRepository examFileRepo,
            ProcessingJobRepository processingJobRepo,
            ObjectStorageGateway gateway,
            StorageProperties props,
            Clock clock,
            @Value("${app.upload.ttl-minutes:10}") long uploadTtlMinutes) {
        this.submissionRepo = submissionRepo;
        this.templateRepo = templateRepo;
        this.eventRepo = eventRepo;
        this.examFileRepo = examFileRepo;
        this.processingJobRepo = processingJobRepo;
        this.gateway = gateway;
        this.props = props;
        this.clock = clock;
        this.uploadTtl = Duration.ofMinutes(uploadTtlMinutes);
    }

    @Override
    @Transactional
    public InitSubmissionResponse init(UUID examId, InitSubmissionRequest request, UUID actorId) {
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
        UUID submissionId = UUID.randomUUID();
        String objectKey = ObjectKeyFactory.of(examId, submissionId, 1);
        Instant uploadExpiresAt = Instant.now(clock).plus(uploadTtl);
        try {
            submissionRepo.insertUploading(submissionId, examId, request.candidateId(),
                request.templateId(), uploadExpiresAt, declared, objectKey);
        } catch (DuplicateKeyException ex) {
            // submission_active_candidate partial unique index.
            throw new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.CANDIDATE_ALREADY_BOUND);
        }

        // 4. Sign the presigned POST ticket (rollback here leaves no orphan row).
        ObjectStorageGateway.UploadTicket ticket = gateway.presignPost(objectKey, declared, uploadTtl);

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
        return Map.ofEntries(
            Map.entry("submissionId", s.submissionId()),
            Map.entry("examId", s.examId()),
            Map.entry("candidateId", s.candidateId()),
            Map.entry("templateId", s.templateId()),
            Map.entry("status", s.status().name()),
            Map.entry("currentFileVersion", s.currentFileVersion()),
            Map.entry("progress", s.progress()),
            Map.entry("uploadExpiresAt", s.uploadExpiresAt()),
            Map.entry("declaredFileSizeBytes", s.declaredFileSizeBytes()),
            Map.entry("objectKey", s.objectKey()),
            Map.entry("failureCode", s.failureCode()),
            Map.entry("version", s.version()),
            Map.entry("updatedAt", s.updatedAt())
        );
    }

    @Override
    @Transactional
    public CompleteSubmissionResponse complete(UUID submissionId) {
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
            throw new ResponseStatusException(HttpStatus.CONFLICT, SubmissionError.UPLOAD_NOT_FOUND);
        }

        long actualSize = stored.get().sizeBytes();
        long declaredSize = submission.declaredFileSizeBytes();
        long maxSize = Math.min(declaredSize + props.sizeToleranceBytes(), props.maxPdfBytes());

        // 6. Validate file size
        if (actualSize > maxSize) {
            gateway.delete(objectKey);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, SubmissionError.FILE_TOO_LARGE);
        }

        // 7. Insert exam_file row (idempotent via unique constraint on submission_id+version)
        if (!examFileRepo.existsCurrentOriginal(submissionId)) {
            examFileRepo.insertOriginal(
                UUID.randomUUID(),
                submissionId,
                submission.currentFileVersion(),
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
        processingJobRepo.insertQueued(
            UUID.randomUUID(),
            submissionId,
            "VALIDATE",
            submission.currentFileVersion()
        );

        // 10. Append event
        eventRepo.append(submissionId, SubmissionStatus.UPLOADED, 0);

        return new CompleteSubmissionResponse(submissionId.toString(), SubmissionStatus.UPLOADED.name());
    }
}
