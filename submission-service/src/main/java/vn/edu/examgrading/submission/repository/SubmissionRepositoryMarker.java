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
     * Inserts a row in UPLOADING. {@code uploadExpiresAt} is bound as UTC by the impl.
     * Note: candidateId is NOT stored in submission to enforce identity boundary (FR-022, FR-025).
     * Candidate-submission mapping is handled by Identity Service via pseudonym.
     */
    void insertUploading(UUID submissionId, UUID examId, UUID templateId,
            Instant uploadExpiresAt, long declaredFileSizeBytes, String objectKey);

    Optional<Submission> findById(UUID submissionId);

    List<Submission> findByExam(UUID examId);

    /** Optimistic status move; returns affected rows (0 when {@code expected} does not match). */
    int transition(UUID submissionId, SubmissionStatus expected, SubmissionStatus target, int progress);

    /** UPLOADING rows whose upload window has elapsed (feeds the cleanup job). */
    List<Submission> findExpiredUploading(Instant now);

    /** Update file_size_bytes after complete() verifies HEAD object. */
    int updateFileSizeBytes(UUID submissionId, long fileSizeBytes);

    /** Clear a previous processing failure when an authorised retry is queued. */
    int clearFailureCode(UUID submissionId);

    /** Delete submission by ID (used for rollback when binding fails). */
    void deleteById(UUID submissionId);
}
