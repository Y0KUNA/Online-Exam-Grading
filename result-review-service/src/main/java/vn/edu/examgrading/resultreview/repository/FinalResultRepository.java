package vn.edu.examgrading.resultreview.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code final_result} rows that drive the §9.3 retention purge. */
public interface FinalResultRepository {

    /** Current results past their retention deadline eligible for purge. */
    List<PurgeCandidate> findPurgeCandidates(Instant now, int limit);

    /**
     * Conditional claim PENDING|FAILED → IN_PROGRESS (guards the race with a
     * remark setting retention_hold). Returns false when another runner or a
     * remark window won.
     */
    boolean claimForPurge(UUID resultId);

    /** All 4 PurgeReceipts DONE: mark PURGED and detach the submission link. */
    void markPurged(UUID resultId);

    /** Any purge step failed — retried on the next scheduler run (§9.3). */
    void markPurgeFailed(UUID resultId);

    record PurgeCandidate(UUID resultId, UUID submissionId) { }
}
