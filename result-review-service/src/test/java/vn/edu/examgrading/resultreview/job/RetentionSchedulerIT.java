package vn.edu.examgrading.resultreview.job;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vn.edu.examgrading.resultreview.client.PurgeClient;
import vn.edu.examgrading.resultreview.repository.JdbcFinalResultRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test for {@link RetentionScheduler} (§9.3 purge) against the
 * compose PostgreSQL {@code result_review_it} database — Flyway rebuilds the
 * schema every run. {@link PurgeClient} is mocked: the real HTTP calls are
 * covered by {@code PurgeClientTest} and the receiving purge endpoints by
 * {@code SchedulerJobsIT} (submission) / {@code InternalGradingControllerIT}
 * (grading).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetentionSchedulerIT {

    private JdbcTemplate jdbc;
    private JdbcFinalResultRepository repo;
    private PurgeClient purgeClient;
    private RetentionScheduler scheduler;
    private final UUID examId = UUID.randomUUID();

    private static String dbPassword() {
        String pw = System.getenv("POSTGRES_PASSWORD");
        if (pw != null && !pw.isBlank()) return pw;
        try {
            Path env = Path.of("..", ".env");
            if (Files.exists(env)) {
                for (String line : Files.readAllLines(env)) {
                    if (line.startsWith("POSTGRES_PASSWORD=")) {
                        return line.substring(line.indexOf('=') + 1).trim();
                    }
                }
            }
        } catch (Exception ignored) { }
        return "change-me";
    }

    @BeforeAll
    void migrate() {
        String url = System.getenv().getOrDefault("IT_DB_URL",
            "jdbc:postgresql://localhost:5435/result_review_it");
        String user = System.getenv().getOrDefault("IT_DB_USER", "exam_grading");
        String password = System.getenv().getOrDefault("IT_DB_PASSWORD", dbPassword());

        DriverManagerDataSource admin = new DriverManagerDataSource();
        admin.setDriverClassName("org.postgresql.Driver");
        admin.setUrl(url);
        admin.setUsername(user);
        admin.setPassword(password);
        JdbcTemplate reset = new JdbcTemplate(admin);
        reset.update("drop schema public cascade");
        reset.update("create schema public");

        Flyway.configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .load()
            .migrate();

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        jdbc = new JdbcTemplate(ds);
        repo = new JdbcFinalResultRepository(jdbc);
        purgeClient = mock(PurgeClient.class);
        scheduler = new RetentionScheduler(repo, purgeClient, jdbc, Clock.systemUTC());
    }

    @BeforeEach
    void clean() {
        Mockito.reset(purgeClient);
        jdbc.update("delete from audit_log where entity_id in (select submission_id from final_result where exam_id=?)", examId);
        jdbc.update("delete from audit_log where entity_id in (select review_id from review where exam_id=?)", examId);
        jdbc.update("delete from council_decision where review_id in (select review_id from review where exam_id=?)", examId);
        jdbc.update("delete from review_note where review_id in (select review_id from review where exam_id=?)", examId);
        jdbc.update("delete from review where exam_id=?", examId);
        jdbc.update("delete from final_result where exam_id=?", examId);
    }

    private UUID newResult(UUID submissionId, boolean hold, Instant deadline, String purgeStatus) {
        UUID resultId = UUID.randomUUID();
        jdbc.update("""
            insert into final_result(result_id,submission_id,exam_id,final_score,decision_source,
                finalized_at,retention_deadline,retention_hold,purge_status,is_current)
            values(?,?,?,?,?,?,?,?,?,true)""",
            resultId, submissionId, examId, 8.5, "COUNCIL",
            Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)),
            Timestamp.from(deadline), hold, purgeStatus);
        return resultId;
    }

    private void seedReviewData(UUID submissionId) {
        UUID reviewId = UUID.randomUUID();
        jdbc.update("""
            insert into review(review_id,submission_id,exam_id,reason_type,reason,status)
            values(?,?,?,'SCORE','it reason','RESOLVED')""",
            reviewId, submissionId, examId);
        jdbc.update("insert into review_note(note_id,review_id,note) values(?,?,?)",
            UUID.randomUUID(), reviewId, "it note");
        jdbc.update("""
            insert into council_decision(decision_id,review_id,decision_type,final_score,reason)
            values(?,?,'FINAL_SCORE',9.0,'it decision')""",
            UUID.randomUUID(), reviewId);
        jdbc.update("""
            insert into audit_log(id,actor_id,action,entity_type,entity_id)
            values(?,?,?, ?, ?)""",
            UUID.randomUUID(), null, "REVIEW_OPENED", "submission", submissionId);
    }

    private Map<String, Object> resultRow(UUID resultId) {
        return jdbc.queryForMap(
            "select purge_status, submission_id from final_result where result_id=?", resultId);
    }

    @Test
    void sweep_purgesEligibleResult_runsAllReceiptsAndDeletesOwnData() {
        UUID submissionId = UUID.randomUUID();
        UUID resultId = newResult(submissionId, false,
            Instant.now().minus(1, ChronoUnit.DAYS), "PENDING");
        seedReviewData(submissionId);

        scheduler.sweep();

        Map<String, Object> row = resultRow(resultId);
        assertEquals("PURGED", row.get("purge_status"));
        assertNull(row.get("submission_id"));

        InOrder order = inOrder(purgeClient);
        order.verify(purgeClient).purgeSubmission(resultId, submissionId);
        order.verify(purgeClient).purgeGrading(resultId, submissionId);
        order.verify(purgeClient).purgeIdentity(resultId, submissionId);
        verifyNoMoreInteractions(purgeClient);

        assertEquals(0, jdbc.queryForObject(
            "select count(*) from review where submission_id=?", Integer.class, submissionId));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from audit_log where entity_id=?", Integer.class, submissionId));
    }

    @Test
    void sweep_retriesFailedStatus() {
        UUID submissionId = UUID.randomUUID();
        UUID resultId = newResult(submissionId, false,
            Instant.now().minus(1, ChronoUnit.DAYS), "FAILED");

        scheduler.sweep();

        assertEquals("PURGED", resultRow(resultId).get("purge_status"));
        verify(purgeClient).purgeSubmission(resultId, submissionId);
    }

    @Test
    void sweep_skipsIneligibleRows() {
        UUID heldSub = UUID.randomUUID();
        UUID held = newResult(heldSub, true,
            Instant.now().minus(1, ChronoUnit.DAYS), "PENDING");
        UUID futureSub = UUID.randomUUID();
        UUID future = newResult(futureSub, false,
            Instant.now().plus(1, ChronoUnit.DAYS), "PENDING");
        UUID inProgressSub = UUID.randomUUID();
        UUID inProgress = newResult(inProgressSub, false,
            Instant.now().minus(1, ChronoUnit.DAYS), "IN_PROGRESS");

        scheduler.sweep();

        for (UUID resultId : new UUID[]{held, future, inProgress}) {
            Map<String, Object> row = resultRow(resultId);
            assertNotEquals("PURGED", row.get("purge_status"), "resultId=" + resultId);
            assertNotNull(row.get("submission_id"), "resultId=" + resultId);
        }
        verifyNoInteractions(purgeClient);
    }

    @Test
    void sweep_purgeFailure_marksFailedAndContinuesWithNextCandidate() {
        UUID failSub = UUID.randomUUID();
        UUID failResult = newResult(failSub, false,
            Instant.now().minus(2, ChronoUnit.DAYS), "PENDING");
        UUID okSub = UUID.randomUUID();
        UUID okResult = newResult(okSub, false,
            Instant.now().minus(1, ChronoUnit.DAYS), "PENDING");
        doThrow(new RuntimeException("submission-service down"))
            .when(purgeClient).purgeSubmission(eq(failResult), eq(failSub));

        scheduler.sweep();

        assertEquals("FAILED", resultRow(failResult).get("purge_status"));
        assertEquals("PURGED", resultRow(okResult).get("purge_status"));
        verify(purgeClient).purgeSubmission(okResult, okSub);
        // failed candidate must not have proceeded to later receipts
        verify(purgeClient, never()).purgeGrading(eq(failResult), any());
    }

    @Test
    void sweep_nullSubmissionId_closesOutWithoutPurgeCalls() {
        UUID resultId = newResult(null, false,
            Instant.now().minus(1, ChronoUnit.DAYS), "PENDING");

        scheduler.sweep();

        Map<String, Object> row = resultRow(resultId);
        assertEquals("PURGED", row.get("purge_status"));
        assertNull(row.get("submission_id"));
        verifyNoInteractions(purgeClient);
    }
}
