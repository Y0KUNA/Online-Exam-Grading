package vn.edu.examgrading.submission.repository;

import java.time.Instant;
import java.util.UUID;

/** Processing-job rows contract (impl in TICKET 03, enqueued on complete in TICKET 06). */
public interface ProcessingJobRepository {

    void insertQueued(UUID jobId, UUID submissionId, String step, int fileVersion, UUID templateId);

    /** T17/T18 retry jobs carry force/overwrite flags read back by the workers. */
    void insertQueued(UUID jobId, UUID submissionId, String step, int fileVersion,
            UUID templateId, boolean force, boolean overwrite);

    /** Update job status and increment attempt. */
    void updateJobStatus(UUID jobId, String status, int attempt);

    /** Update job with error details for retry scheduling. */
    void updateJobError(UUID jobId, String errorCode, String errorDetail, Instant nextRetryAt);

    /** Find job by submission and step. */
    java.util.Optional<JobInfo> findJobBySubmissionAndStep(UUID submissionId, String step);

    /** Find job by its id (spec §8 idempotency is keyed on jobId). */
    java.util.Optional<JobInfo> findJobById(UUID jobId);

    /**
     * QUEUED jobs untouched since {@code staleBefore} whose retry time (if any)
     * has already passed — feeds the reconciler that republishes lost messages.
     * Joins submission for examId; capped at {@code limit} rows per sweep.
     */
    java.util.List<StaleJob> findStaleQueued(Instant staleBefore, Instant now, int limit);

    record JobInfo(UUID jobId, UUID submissionId, String step, String status, int attempt,
                   String errorCode, String errorDetail, Instant nextRetryAt,
                   int fileVersion, boolean force, boolean overwrite, UUID templateId) {}

    /** Stale QUEUED job plus the owning exam, for message republish. */
    record StaleJob(UUID jobId, UUID submissionId, UUID examId, String step, int attempt,
                    int fileVersion, boolean force, boolean overwrite, UUID templateId) {}
}
