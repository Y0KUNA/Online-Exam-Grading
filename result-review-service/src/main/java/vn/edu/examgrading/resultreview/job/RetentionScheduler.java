package vn.edu.examgrading.resultreview.job;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.edu.examgrading.resultreview.client.PurgeClient;
import vn.edu.examgrading.resultreview.repository.FinalResultRepository;
import vn.edu.examgrading.resultreview.repository.FinalResultRepository.PurgeCandidate;

/**
 * Daily retention purge (§9.3, FR-025, D-03): selects current FinalResults past
 * {@code retention_deadline} with {@code purge_status ∈ {PENDING,FAILED}} and
 * no {@code retention_hold}, claims each via a conditional update (a remark in
 * flight wins the race), then runs the 4 PurgeReceipts — submission detail,
 * exam-grading detail, identity pseudonym/binding, and this service's own
 * review/council/audit rows. Only when all succeed does the result become
 * {@code PURGED} with {@code submission_id=NULL}; any failure marks
 * {@code FAILED} and is retried on the next run.
 */
@Component
public class RetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);
    private static final int SWEEP_LIMIT = 100;

    private final FinalResultRepository finalResultRepo;
    private final PurgeClient purgeClient;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public RetentionScheduler(FinalResultRepository finalResultRepo,
            PurgeClient purgeClient, JdbcTemplate jdbcTemplate, Clock clock) {
        this.finalResultRepo = finalResultRepo;
        this.purgeClient = purgeClient;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /** Daily sweep (§9.3): default 02:00 UTC, cron configurable. */
    @Scheduled(cron = "${app.retention.cron:0 0 2 * * *}")
    public void sweep() {
        List<PurgeCandidate> candidates =
            finalResultRepo.findPurgeCandidates(Instant.now(clock), SWEEP_LIMIT);
        if (candidates.isEmpty()) {
            return;
        }
        log.info("Retention purge: {} candidate(s)", candidates.size());
        for (PurgeCandidate candidate : candidates) {
            try {
                purge(candidate);
            } catch (RuntimeException e) {
                log.warn("Retention purge failed for resultId={}: {}",
                    candidate.resultId(), e.toString());
                try {
                    finalResultRepo.markPurgeFailed(candidate.resultId());
                } catch (RuntimeException ignored) { }
            }
        }
    }

    private void purge(PurgeCandidate candidate) {
        UUID resultId = candidate.resultId();
        UUID submissionId = candidate.submissionId();
        if (submissionId == null) {
            // Defensive: a PENDING/FAILED row should always have a submission;
            // nothing left to purge — just close it out.
            finalResultRepo.markPurged(resultId);
            return;
        }
        if (!finalResultRepo.claimForPurge(resultId)) {
            return; // remark window or another runner claimed it first
        }
        purgeClient.purgeSubmission(resultId, submissionId);
        purgeClient.purgeGrading(resultId, submissionId);
        purgeClient.purgeIdentity(resultId, submissionId);
        deleteOwnReviewData(submissionId);
        finalResultRepo.markPurged(resultId);
        log.info("Retention purge DONE: resultId={}, submissionId={}", resultId, submissionId);
    }

    /** 4th PurgeReceipt: this service's review/council/audit detail (§9.3). */
    private void deleteOwnReviewData(UUID submissionId) {
        jdbcTemplate.update("""
            delete from council_decision where review_id in (
                select review_id from review where submission_id = ?)
            """, submissionId);
        jdbcTemplate.update("""
            delete from review_note where review_id in (
                select review_id from review where submission_id = ?)
            """, submissionId);
        jdbcTemplate.update("delete from review where submission_id = ?", submissionId);
        jdbcTemplate.update("delete from audit_log where entity_id = ?", submissionId);
    }
}
