package vn.edu.examgrading.submission.repository;

import java.time.Instant;
import java.util.UUID;

/** Processing-job rows contract (impl in TICKET 03, enqueued on complete in TICKET 06). */
public interface ProcessingJobRepository {

    void insertQueued(UUID jobId, UUID submissionId, String step, int fileVersion, UUID templateId);

    /** Update job status and increment attempt. */
    void updateJobStatus(UUID jobId, String status, int attempt);

    /** Update job with error details for retry scheduling. */
    void updateJobError(UUID jobId, String errorCode, String errorDetail, Instant nextRetryAt);

    /** Find job by submission and step. */
    java.util.Optional<JobInfo> findJobBySubmissionAndStep(UUID submissionId, String step);

    /** Find job by its id (spec §8 idempotency is keyed on jobId). */
    java.util.Optional<JobInfo> findJobById(UUID jobId);

    record JobInfo(UUID jobId, UUID submissionId, String step, String status, int attempt,
                   String errorCode, String errorDetail, Instant nextRetryAt,
                   int fileVersion, boolean force, boolean overwrite, UUID templateId) {}
}
