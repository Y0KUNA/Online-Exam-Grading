package vn.edu.examgrading.submission.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Exam-file rows contract (impl in TICKET 03, populated on complete in TICKET 06). */
public interface ExamFileRepository {

    boolean existsCurrentOriginal(UUID submissionId);

    void insertOriginal(UUID fileId, UUID submissionId, long sizeBytes, String objectKey);

    /** Current ORIGINAL row (object key + file version) used by the ANONYMIZE worker. */
    Optional<OriginalFile> findCurrentOriginal(UUID submissionId);

    /** Current row of the given type (ORIGINAL|ANONYMIZED); used by the internal file-access endpoint. */
    Optional<OriginalFile> findCurrentByType(UUID submissionId, String type);

    /** Insert the ANONYMIZED row (is_current=true, same file version as ORIGINAL). */
    void insertAnonymized(UUID fileId, UUID submissionId, int version, long sizeBytes, String objectKey, String sha256);

    /** Overwrite the current ANONYMIZED row in place (REPROCESS / MASK_INSUFFICIENT flow). Returns affected rows. */
    int updateAnonymized(UUID submissionId, long sizeBytes, String objectKey, String sha256);

    /** All file versions of a submission (any type, any currency) for the public files list. */
    List<ExamFileVersion> findAllBySubmission(UUID submissionId);

    /** Whether any exam_file row references this object key (orphan cleanup). */
    boolean existsByObjectKey(String objectKey);

    record OriginalFile(UUID fileId, int version, long sizeBytes, String objectKey) {}

    /** Public projection row: never carries object_key. */
    record ExamFileVersion(String type, int version, boolean isCurrent,
            Long sizeBytes, String sha256, Instant createdAt) {}
}
