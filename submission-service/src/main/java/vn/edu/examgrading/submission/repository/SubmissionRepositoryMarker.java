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

    /**
     * Keyset page of an exam's submissions, newest {@code updated_at} first.
     * {@code status} null = no filter; {@code cursorUpdatedAt}/{@code cursorSubmissionId}
     * non-null = resume strictly after that position. Caller fetches {@code limit + 1}
     * rows to detect a next page.
     */
    List<Submission> findByExamPage(UUID examId, SubmissionStatus status,
            Instant cursorUpdatedAt, UUID cursorSubmissionId, int limit);

    /** Optimistic status move; returns affected rows (0 when {@code expected} does not match). */
    int transition(UUID submissionId, SubmissionStatus expected, SubmissionStatus target, int progress);

    /** UPLOADING rows whose upload window has elapsed (feeds the cleanup job). */
    List<Submission> findExpiredUploading(Instant now);

    /**
     * Conditional UPLOADING → FAILED with a failure code (cleanup job).
     * Returns affected rows — 0 when the row raced out of UPLOADING.
     */
    int failUploading(UUID submissionId, String failureCode);

    /** Update file_size_bytes after complete() verifies HEAD object. */
    int updateFileSizeBytes(UUID submissionId, long fileSizeBytes);

    /** Delete submission by ID (used for rollback when binding fails). */
    void deleteById(UUID submissionId);

    /** Whether an UPLOADING submission still references this object key (orphan cleanup guard). */
    boolean existsUploadingWithObjectKey(String objectKey);

    /**
     * §4.4 replace-file commit: REVIEW_REQUIRED|FAILED → UPLOADED with the new
     * {@code current_file_version}, failure_code cleared, progress reset.
     * Returns affected rows — 0 when the status raced away.
     */
    int completeReplacement(UUID submissionId, int fileVersion);

    /** Q-15: swap the submission's template for a reprocess retry (T18). */
    int updateTemplateId(UUID submissionId, UUID templateId);
}
