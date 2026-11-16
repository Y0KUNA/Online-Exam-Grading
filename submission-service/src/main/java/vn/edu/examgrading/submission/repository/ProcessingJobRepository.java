package vn.edu.examgrading.submission.repository;

import java.util.UUID;

/** Processing-job rows contract (impl in TICKET 03, enqueued on complete in TICKET 06). */
public interface ProcessingJobRepository {

    void insertQueued(UUID jobId, UUID submissionId, String step, int fileVersion);
}
