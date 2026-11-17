package vn.edu.examgrading.submission.repository;

import java.util.Optional;
import java.util.UUID;

/** Exam-file rows contract (impl in TICKET 03, populated on complete in TICKET 06). */
public interface ExamFileRepository {

    boolean existsCurrentOriginal(UUID submissionId);

    void insertOriginal(UUID fileId, UUID submissionId, long sizeBytes, String objectKey);

    /** Current ORIGINAL row (object key + file version) used by the ANONYMIZE worker. */
    Optional<OriginalFile> findCurrentOriginal(UUID submissionId);

    /** Insert the ANONYMIZED row (is_current=true, same file version as ORIGINAL). */
    void insertAnonymized(UUID fileId, UUID submissionId, int version, long sizeBytes, String objectKey, String sha256);

    /** Overwrite the current ANONYMIZED row in place (REPROCESS / MASK_INSUFFICIENT flow). Returns affected rows. */
    int updateAnonymized(UUID submissionId, long sizeBytes, String objectKey, String sha256);

    record OriginalFile(UUID fileId, int version, long sizeBytes, String objectKey) {}
}
