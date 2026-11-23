package vn.edu.examgrading.resultreview.job;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.examgrading.resultreview.client.PurgeClient;
import vn.edu.examgrading.resultreview.repository.FinalResultRepository;
import vn.edu.examgrading.resultreview.repository.FinalResultRepository.PurgeCandidate;

/**
 * Unit tests for {@link RetentionScheduler} (§9.3): claim-then-purge ordering,
 * all-4-receipts gating, FAILED marking and per-candidate isolation.
 */
@ExtendWith(MockitoExtension.class)
class RetentionSchedulerTest {

    @Mock private FinalResultRepository finalResultRepo;
    @Mock private PurgeClient purgeClient;
    @Mock private JdbcTemplate jdbcTemplate;

    private RetentionScheduler scheduler;
    private final Instant now = Instant.parse("2026-02-01T02:00:00Z");

    @BeforeEach
    void setUp() {
        scheduler = new RetentionScheduler(finalResultRepo, purgeClient, jdbcTemplate,
            Clock.fixed(now, ZoneOffset.UTC));
    }

    private PurgeCandidate candidate() {
        return new PurgeCandidate(UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void noCandidates_doesNothing() {
        when(finalResultRepo.findPurgeCandidates(now, 100)).thenReturn(List.of());

        scheduler.sweep();

        verifyNoInteractions(purgeClient, jdbcTemplate);
        verify(finalResultRepo, never()).claimForPurge(any());
    }

    @Test
    void candidate_runsAllFourReceiptsThenMarksPurged() {
        PurgeCandidate c = candidate();
        when(finalResultRepo.findPurgeCandidates(now, 100)).thenReturn(List.of(c));
        when(finalResultRepo.claimForPurge(c.resultId())).thenReturn(true);

        scheduler.sweep();

        InOrder inOrder = inOrder(purgeClient, jdbcTemplate, finalResultRepo);
        inOrder.verify(purgeClient).purgeSubmission(c.resultId(), c.submissionId());
        inOrder.verify(purgeClient).purgeGrading(c.resultId(), c.submissionId());
        inOrder.verify(purgeClient).purgeIdentity(c.resultId(), c.submissionId());
        inOrder.verify(jdbcTemplate, atLeastOnce()).update(contains("delete from"), any(Object[].class));
        inOrder.verify(finalResultRepo).markPurged(c.resultId());
        verify(finalResultRepo, never()).markPurgeFailed(any());
        // own-data deletes cover council_decision, review_note, review, audit_log
        verify(jdbcTemplate).update(contains("council_decision"), eq(c.submissionId()));
        verify(jdbcTemplate).update(contains("review_note"), eq(c.submissionId()));
        verify(jdbcTemplate).update("delete from review where submission_id = ?", c.submissionId());
        verify(jdbcTemplate).update("delete from audit_log where entity_id = ?", c.submissionId());
    }

    @Test
    void claimLostToRemark_skipsPurge() {
        PurgeCandidate c = candidate();
        when(finalResultRepo.findPurgeCandidates(now, 100)).thenReturn(List.of(c));
        when(finalResultRepo.claimForPurge(c.resultId())).thenReturn(false);

        scheduler.sweep();

        verifyNoInteractions(purgeClient);
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(finalResultRepo, never()).markPurged(any());
        verify(finalResultRepo, never()).markPurgeFailed(any());
    }

    @Test
    void purgeFailure_marksFailedAndDoesNotMarkPurged() {
        PurgeCandidate c = candidate();
        when(finalResultRepo.findPurgeCandidates(now, 100)).thenReturn(List.of(c));
        when(finalResultRepo.claimForPurge(c.resultId())).thenReturn(true);
        doThrow(new RuntimeException("submission down"))
            .when(purgeClient).purgeSubmission(c.resultId(), c.submissionId());

        scheduler.sweep();

        verify(purgeClient, never()).purgeGrading(any(), any());
        verify(finalResultRepo).markPurgeFailed(c.resultId());
        verify(finalResultRepo, never()).markPurged(any());
    }

    @Test
    void nullSubmissionId_closesOutWithoutRemoteCalls() {
        PurgeCandidate c = new PurgeCandidate(UUID.randomUUID(), null);
        when(finalResultRepo.findPurgeCandidates(now, 100)).thenReturn(List.of(c));

        scheduler.sweep();

        verifyNoInteractions(purgeClient);
        verify(finalResultRepo).markPurged(c.resultId());
    }

    @Test
    void oneBadCandidate_doesNotBlockTheRest() {
        PurgeCandidate bad = candidate();
        PurgeCandidate good = candidate();
        when(finalResultRepo.findPurgeCandidates(now, 100)).thenReturn(List.of(bad, good));
        when(finalResultRepo.claimForPurge(any())).thenReturn(true);
        doThrow(new RuntimeException("boom")).when(purgeClient)
            .purgeSubmission(eq(bad.resultId()), any());

        scheduler.sweep();

        verify(finalResultRepo).markPurgeFailed(bad.resultId());
        verify(finalResultRepo).markPurged(good.resultId());
    }
}
