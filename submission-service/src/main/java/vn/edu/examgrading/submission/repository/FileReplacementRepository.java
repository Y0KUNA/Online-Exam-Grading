package vn.edu.examgrading.submission.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code file_replacement} rows (§4.4): a pending scan-replacement request.
 * At most one PENDING row per submission (partial unique index).
 */
public interface FileReplacementRepository {

    /** Insert a PENDING replacement; throws on unique violation (race → 409). */
    void insertPending(UUID replacementId, UUID submissionId, int version, String objectKey,
        long declaredSizeBytes, String reason, Instant uploadExpiresAt, UUID requestedBy);

    /** The submission's live PENDING replacement (not yet expired). */
    Optional<Replacement> findPending(UUID submissionId, Instant now);

    /** Any replacement row by id (complete validates it belongs to the submission). */
    Optional<Replacement> findById(UUID replacementId);

    /** PENDING → COMPLETED with completed_at; returns affected rows. */
    int markCompleted(UUID replacementId, Instant completedAt);

    /** PENDING → CANCELLED (FILE_TOO_LARGE / EO cancel); returns affected rows. */
    int markCancelled(UUID replacementId);

    /** PENDING → EXPIRED once upload_expires_at passed; returns affected rows. */
    int markExpired(UUID replacementId);

    /**
     * Lazily expire every overdue PENDING row of a submission (§4.4 cleanup
     * runs on a scheduler, but a stale PENDING still holds the partial unique
     * slot — free it at request time so a new replacement can be created).
     */
    int expireOverdue(UUID submissionId, Instant now);

    record Replacement(UUID replacementId, UUID submissionId, int version, String objectKey,
        long declaredSizeBytes, String reason, String status, Instant uploadExpiresAt,
        UUID requestedBy, Instant createdAt, Instant completedAt) {}
}
