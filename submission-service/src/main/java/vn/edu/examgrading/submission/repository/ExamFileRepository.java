package vn.edu.examgrading.submission.repository;

import java.util.UUID;

/** Exam-file rows contract (impl in TICKET 03, populated on complete in TICKET 06). */
public interface ExamFileRepository {

    boolean existsCurrentOriginal(UUID submissionId);

    void insertOriginal(UUID fileId, UUID submissionId, int version, long sizeBytes, String objectKey);
}
