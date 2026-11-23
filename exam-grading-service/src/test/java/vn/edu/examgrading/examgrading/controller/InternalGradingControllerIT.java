package vn.edu.examgrading.examgrading.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.examgrading.common.security.HmacSigner;
import vn.edu.examgrading.examgrading.service.AssignmentService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link InternalGradingController} (TICKET 05 task 9).
 * Runs against the compose PostgreSQL at localhost:5434 on the dedicated
 * {@code exam_grading_it} database; Flyway rebuilds the schema from scratch on
 * every run. Requires the docker-compose stack to be up — same convention as
 * the MinIO worker tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InternalGradingControllerIT {

    private static final String SECRET = "it-hmac-secret";
    private static final String CALLER = "submission-service";

    private JdbcTemplate jdbc;
    private InternalGradingController controller;
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
            "jdbc:postgresql://localhost:5434/exam_grading_it");
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
        controller = new InternalGradingController(jdbc, SECRET, new AssignmentService(jdbc));
        jdbc.update("insert into exam(exam_id,exam_code,name,subject) values(?,?,?,?)",
            examId, "IT-" + examId.toString().substring(0, 8), "IT Exam", "Math");
    }

    @BeforeEach
    void clean() {
        jdbc.update("delete from grading_attempt where assignment_id in (select assignment_id from grading_assignment where exam_id=?)", examId);
        jdbc.update("delete from grading_assignment where exam_id=?", examId);
        jdbc.update("delete from idempotency_record where idempotency_key like 'it-%'");
    }

    private UUID newAssignment(UUID submissionId, String status) {
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("insert into grading_assignment(assignment_id,exam_id,submission_id,grader_id,status) values(?,?,?,?,?)",
            assignmentId, examId, submissionId, UUID.randomUUID(), status);
        return assignmentId;
    }

    private void newAttempt(UUID assignmentId, String status) {
        jdbc.update("insert into grading_attempt(attempt_id,assignment_id,status) values(?,?,?)",
            UUID.randomUUID(), assignmentId, status);
    }

    private static String sign(String caller, UUID requestId, Instant timestamp) {
        return HmacSigner.sign(SECRET, caller + "\n" + requestId + "\n" + timestamp.toEpochMilli());
    }

    private static UUID rid() { return UUID.randomUUID(); }
    private static Instant ts() { return Instant.now(); }

    @Test
    void attemptSummary_countsAcrossAssignments() {
        UUID submissionId = UUID.randomUUID();
        UUID a1 = newAssignment(submissionId, "COMPLETED");
        UUID a2 = newAssignment(submissionId, "IN_PROGRESS");
        newAssignment(submissionId, "ASSIGNED");
        newAttempt(a1, "CONFIRMED");
        newAttempt(a1, "DISCARDED");
        newAttempt(a2, "DRAFT");

        UUID requestId = rid(); Instant timestamp = ts();
        Map<String, Object> res = controller.attemptSummary(submissionId, CALLER, requestId, timestamp,
            sign(CALLER, requestId, timestamp));

        assertEquals(1L, res.get("confirmedCount"));
        assertEquals(1L, res.get("draftCount"));
        assertEquals(2L, res.get("openAssignmentCount"));
    }

    @Test
    void discardOpenWork_transitionsAndIsIdempotent() {
        UUID submissionId = UUID.randomUUID();
        UUID a1 = newAssignment(submissionId, "ASSIGNED");
        UUID a2 = newAssignment(submissionId, "IN_PROGRESS");
        newAssignment(submissionId, "COMPLETED");
        newAttempt(a1, "DRAFT");
        newAttempt(a2, "DRAFT");
        newAttempt(a1, "DISCARDED");

        UUID requestId = rid(); Instant timestamp = ts();
        Map<String, Object> res = controller.discardOpenWork(submissionId, "it-repl-1", CALLER, requestId, timestamp,
            sign(CALLER, requestId, timestamp));
        assertEquals(2, res.get("discardedAttempts"));
        assertEquals(2, res.get("cancelledAssignments"));

        // DB state: DRAFT→DISCARDED, open→CANCELLED, COMPLETED untouched
        // (3 = 2 transitioned + 1 DISCARDED fixture row)
        assertEquals(3, jdbc.queryForObject(
            "select count(*) from grading_attempt t join grading_assignment ga on ga.assignment_id=t.assignment_id "
            + "where ga.submission_id=? and t.status='DISCARDED'", Integer.class, submissionId));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from grading_attempt t join grading_assignment ga on ga.assignment_id=t.assignment_id "
            + "where ga.submission_id=? and t.status='DRAFT'", Integer.class, submissionId));
        assertEquals(2, jdbc.queryForObject(
            "select count(*) from grading_assignment where submission_id=? and status='CANCELLED'",
            Integer.class, submissionId));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from grading_assignment where submission_id=? and status='COMPLETED'",
            Integer.class, submissionId));

        // replay with same Idempotency-Key returns stored response, no double count
        Map<String, Object> replay = controller.discardOpenWork(submissionId, "it-repl-1", CALLER, requestId, timestamp,
            sign(CALLER, requestId, timestamp));
        assertEquals(2, replay.get("discardedAttempts"));
        assertEquals(2, replay.get("cancelledAssignments"));
    }

    @Test
    void discardOpenWork_confirmedAttempt_throws409() {
        UUID submissionId = UUID.randomUUID();
        UUID a1 = newAssignment(submissionId, "COMPLETED");
        newAttempt(a1, "CONFIRMED");
        newAssignment(submissionId, "ASSIGNED");

        UUID requestId = rid(); Instant timestamp = ts();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.discardOpenWork(submissionId, "it-repl-2", CALLER, requestId, timestamp,
                sign(CALLER, requestId, timestamp)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("ATTEMPTS_EXIST", ex.getReason());

        // nothing was mutated
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from grading_assignment where submission_id=? and status='ASSIGNED'",
            Integer.class, submissionId));
    }

    @Test
    void discardOpenWork_noConfirmed_isNaturallyIdempotent() {
        UUID submissionId = UUID.randomUUID();
        newAssignment(submissionId, "ASSIGNED");

        UUID requestId = rid(); Instant timestamp = ts();
        // no Idempotency-Key: second call finds no open work → zeros, no error
        controller.discardOpenWork(submissionId, null, CALLER, requestId, timestamp,
            sign(CALLER, requestId, timestamp));
        Map<String, Object> res = controller.discardOpenWork(submissionId, null, CALLER, requestId, timestamp,
            sign(CALLER, requestId, timestamp));
        assertEquals(0, res.get("discardedAttempts"));
        assertEquals(0, res.get("cancelledAssignments"));
    }

    // ---------- §9.3 retention purge ----------

    private Map<String, Object> purge(UUID submissionId, String caller) {
        UUID requestId = rid(); Instant timestamp = ts();
        return controller.purgeDetail(submissionId, caller, requestId, timestamp,
            sign(caller, requestId, timestamp));
    }

    @Test
    void purgeDetail_removesSubmissionGradingData_isIdempotent_and403ForWrongCaller() {
        UUID submissionId = UUID.randomUUID();
        UUID assignmentId = newAssignment(submissionId, "COMPLETED");
        UUID attemptId = UUID.randomUUID();
        jdbc.update("insert into grading_attempt(attempt_id,assignment_id,status) values(?,?,?)",
            attemptId, assignmentId, "CONFIRMED");
        jdbc.update("insert into question_score(attempt_id,question_id,score) values(?,?,?)",
            attemptId, UUID.randomUUID(), 7.5);
        // another submission's data must survive
        UUID otherSubmission = UUID.randomUUID();
        UUID otherAssignment = newAssignment(otherSubmission, "ASSIGNED");

        Map<String, Object> res = purge(submissionId, "result-review-service");
        assertEquals("DONE", res.get("status"));

        for (String table : new String[]{"question_score", "grading_attempt", "grading_assignment"}) {
            assertEquals(0, jdbc.queryForObject("select count(*) from " + table
                + (table.equals("question_score")
                    ? " where attempt_id=?" : table.equals("grading_attempt")
                        ? " where assignment_id=?" : " where submission_id=?"),
                Integer.class,
                table.equals("question_score") ? attemptId
                    : table.equals("grading_attempt") ? assignmentId : submissionId),
                table + " rows for purged submission must be gone");
        }
        // exam-level data + other submission untouched
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from exam where exam_id=?", Integer.class, examId));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from grading_assignment where submission_id=?", Integer.class, otherSubmission));

        // replay → DONE
        assertEquals("DONE", purge(submissionId, "result-review-service").get("status"));

        // non-purge caller → 403 even though HMAC is valid
        UUID requestId = rid(); Instant timestamp = ts();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.purgeDetail(otherSubmission, CALLER, requestId, timestamp,
                sign(CALLER, requestId, timestamp)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }
}
