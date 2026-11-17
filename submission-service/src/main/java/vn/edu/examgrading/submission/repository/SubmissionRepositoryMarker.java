package vn.edu.examgrading.submission.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.examgrading.submission.domain.Submission;
import vn.edu.examgrading.submission.domain.SubmissionStatus;

/**
 * Contract for the {@code submission} aggregate (repository layer, TICKET 00).
 * Kept under its original marker name; JDBC-backed impl arrives in TICKET 03.
 * Signatures use plain domain types only — no SQL/HTTP leakage.
 */
public interface SubmissionRepositoryMarker {

    /**
     * Inserts a row in UPLOADING. Implementations surface the unique-index clash
     * (exam, candidate) as a {@code DuplicateKeyException} for the service to map to
     * 409 CANDIDATE_ALREADY_BOUND. {@code uploadExpiresAt} is bound as UTC by the impl.
     */
    void insertUploading(UUID submissionId, UUID examId, UUID candidateId, UUID templateId,
            Instant uploadExpiresAt, long declaredFileSizeBytes, String objectKey);

    Optional<Submission> findById(UUID submissionId);

    List<Submission> findByExam(UUID examId);

    /** Optimistic status move; returns affected rows (0 when {@code expected} does not match). */
    int transition(UUID submissionId, SubmissionStatus expected, SubmissionStatus target, int progress);

    /** UPLOADING rows whose upload window has elapsed (feeds the cleanup job). */
    List<Submission> findExpiredUploading(Instant now);
}
