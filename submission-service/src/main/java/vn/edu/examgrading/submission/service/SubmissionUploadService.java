package vn.edu.examgrading.submission.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.examgrading.submission.dto.InitSubmissionRequest;
import vn.edu.examgrading.submission.dto.InitSubmissionResponse;
import vn.edu.examgrading.submission.storage.ObjectKeyFactory;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway;
import vn.edu.examgrading.submission.storage.config.StorageProperties;

/**
 * Upload initialization (TICKET 03). Validates the declared PDF, reserves a
 * {@code UPLOADING} submission row + deterministic object key, then signs the
 * presigned POST ticket. The bytes themselves are pushed by the client straight
 * to storage through the Gateway in TICKET 04; nothing here trusts the client's
 * size/content — the final HEAD check lands in TICKET 05.
 */
@Service
public class SubmissionUploadService {

    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final JdbcTemplate jdbc;
    private final ObjectStorageGateway gateway;
    private final StorageProperties props;
    private final Clock clock;
    private final Duration uploadTtl;

    public SubmissionUploadService(JdbcTemplate jdbc,
                                   ObjectStorageGateway gateway,
                                   StorageProperties props,
                                   Clock clock,
                                   @Value("${app.upload.ttl-minutes:10}") long uploadTtlMinutes) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.props = props;
        this.clock = clock;
        this.uploadTtl = Duration.ofMinutes(uploadTtlMinutes);
    }

    @Transactional
    public InitSubmissionResponse init(UUID examId, InitSubmissionRequest request, UUID actorId) {
        // 1. Content/type + declared-size guards first — reject before touching the DB.
        if (examId == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "MISSING_EXAM_ID");
        }
        if (!PDF_CONTENT_TYPE.equalsIgnoreCase(request.contentType())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_CONTENT_TYPE");
        }
        long declared = request.fileSizeBytes();
        if (declared <= 0 || declared > props.maxPdfBytes()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE_SIZE");
        }

        // 2. The template must exist and be active.
        Integer activeTemplates = jdbc.queryForObject(
                "select count(*) from paper_template where template_id = ? and active = true",
                Integer.class, request.templateId());
        if (activeTemplates == null || activeTemplates == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND");
        }

        // 3. Reserve the submission in UPLOADING with a deterministic, PII-free key.
        // Note: candidateId is NOT stored in submission to enforce identity boundary (FR-022, FR-025).
        UUID submissionId = UUID.randomUUID();
        String objectKey = ObjectKeyFactory.of(examId, submissionId, 1);
        Instant uploadExpiresAt = Instant.now(clock).plus(uploadTtl);
        jdbc.update(
                "insert into submission (submission_id, exam_id, template_id, status, "
                        + "current_file_version, progress, upload_expires_at, updated_at, version, "
                        + "declared_file_size_bytes, object_key) "
                        + "values (?,?,?,'UPLOADING',1,0,?,now(),0,?,?)",
                submissionId, examId, request.templateId(),
                uploadExpiresAt.atOffset(ZoneOffset.UTC), declared, objectKey);

        // 4. Sign the presigned POST ticket (rollback here leaves no orphan row).
        ObjectStorageGateway.UploadTicket ticket = gateway.presignPost(objectKey, declared, uploadTtl);

        jdbc.update("insert into submission_event (submission_id, status, progress) values (?,?,0)",
                submissionId, "UPLOADING");

        return new InitSubmissionResponse(
                submissionId.toString(),
                "UPLOADING",
                new InitSubmissionResponse.Upload(ticket.url(), ticket.fields()),
                uploadExpiresAt,
                "/api/v1/submissions/" + submissionId);
    }
}
