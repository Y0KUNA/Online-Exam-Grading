package vn.edu.examgrading.submission.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import vn.edu.examgrading.submission.repository.JdbcExamFileRepository;
import vn.edu.examgrading.submission.repository.JdbcSubmissionEventRepository;
import vn.edu.examgrading.submission.storage.InMemoryObjectStorage;
import vn.edu.examgrading.submission.storage.config.StorageProperties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link InternalSubmissionController} (TICKET 05 task 9).
 * Runs against the compose PostgreSQL at localhost:5433 on the dedicated
 * {@code submission_it} database (created once via psql); Flyway rebuilds the
 * schema from scratch on every run, including V15 finalize columns.
 * Requires the docker-compose stack to be up — same convention as the MinIO
 * worker tests. Storage is the in-memory double; HMAC uses a test secret.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InternalSubmissionControllerIT {

    private static final String SECRET = "it-hmac-secret";
    private static final String CALLER = "result-review-service";

    private JdbcTemplate jdbc;
    private InternalSubmissionController controller;
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
            "jdbc:postgresql://localhost:5433/submission_it");
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
        StorageProperties props = new StorageProperties("memory", "http://minio:9000", "us-east-1",
            "key", "secret", "original", "anonymized", "http://gateway:8080/storage",
            52428800L, 1024L);
        controller = new InternalSubmissionController(jdbc, SECRET,
            new JdbcExamFileRepository(jdbc), new InMemoryObjectStorage(), props,
            new JdbcSubmissionEventRepository(jdbc));
    }

    @BeforeEach
    void clean() {
        jdbc.update("delete from submission_event where submission_id in (select submission_id from submission where exam_id=?)", examId);
        jdbc.update("delete from exam_file where submission_id in (select submission_id from submission where exam_id=?)", examId);
        jdbc.update("delete from audit_log where entity_id in (select submission_id from submission where exam_id=?)", examId);
        jdbc.update("delete from submission where exam_id=?", examId);
    }

    private UUID newSubmission(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into submission(submission_id,exam_id,status,version) values(?,?,?,0)",
            id, examId, status);
        return id;
    }

    private static String sign(String caller, UUID requestId, Instant timestamp) {
        return HmacSigner.sign(SECRET, caller + "\n" + requestId + "\n" + timestamp.toEpochMilli());
    }

    // ---------- GET ----------

    @Test
    void get_returnsSnapshotAnd404WhenMissing() {
        UUID id = newSubmission("UPLOADED");
        jdbc.update("update submission set version=4 where submission_id=?", id);
        UUID requestId = UUID.randomUUID(); Instant ts = Instant.now();

        Map<String, Object> res = controller.get(id, CALLER, requestId, ts, sign(CALLER, requestId, ts));
        assertEquals(id, res.get("submissionId"));
        assertEquals(examId, res.get("examId"));
        assertEquals("UPLOADED", res.get("status"));
        assertEquals(4, ((Number) res.get("version")).intValue());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.get(UUID.randomUUID(), CALLER, requestId, ts, sign(CALLER, requestId, ts)));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ---------- status ----------

    @Test
    void status_transitionPersistsEventWithExamId() {
        UUID id = newSubmission("UPLOADED");
        UUID requestId = UUID.randomUUID(); Instant ts = Instant.now();

        Map<String, Object> res = controller.change(id,
            Map.of("expectedStatus", "UPLOADED", "newStatus", "VALIDATING", "progress", 50),
            CALLER, requestId, ts, sign(CALLER, requestId, ts));

        assertEquals("VALIDATING", res.get("status"));
        assertEquals(1, ((Number) res.get("version")).intValue());
        // regression: submission_event.exam_id is NOT NULL (V4)
        Integer events = jdbc.queryForObject(
            "select count(*) from submission_event where submission_id=? and exam_id=? and status='VALIDATING'",
            Integer.class, id, examId);
        assertEquals(1, events);
    }

    @Test
    void status_idempotentReplayAndConflicts() {
        UUID id = newSubmission("VALIDATING");
        UUID requestId = UUID.randomUUID(); Instant ts = Instant.now();

        // replay same destination → 200, no extra version bump
        Map<String, Object> res = controller.change(id, Map.of("newStatus", "VALIDATING"),
            CALLER, requestId, ts, sign(CALLER, requestId, ts));
        assertEquals("VALIDATING", res.get("status"));
        assertEquals(0, ((Number) res.get("version")).intValue());

        // expectedStatus mismatch → 409 STATUS_VERSION_CONFLICT
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.change(id, Map.of("expectedStatus", "UPLOADED", "newStatus", "ANONYMIZING"),
                CALLER, requestId, ts, sign(CALLER, requestId, ts)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("STATUS_VERSION_CONFLICT", ex.getReason());

        // illegal transition → 409 ILLEGAL_TRANSITION
        UUID other = newSubmission("UPLOADING");
        ResponseStatusException ex2 = assertThrows(ResponseStatusException.class,
            () -> controller.change(other, Map.of("newStatus", "COMPLETED"),
                CALLER, requestId, ts, sign(CALLER, requestId, ts)));
        assertEquals(HttpStatus.CONFLICT, ex2.getStatusCode());
        assertEquals("ILLEGAL_TRANSITION", ex2.getReason());
    }

    @Test
    void status_failedWritesFailureCodeAndAudit() {
        UUID id = newSubmission("VALIDATING");
        UUID requestId = UUID.randomUUID(); Instant ts = Instant.now();

        controller.change(id, Map.of("newStatus", "FAILED", "reasonCode", "PDF_CORRUPT"),
            CALLER, requestId, ts, sign(CALLER, requestId, ts));

        String failureCode = jdbc.queryForObject(
            "select failure_code from submission where submission_id=?", String.class, id);
        assertEquals("PDF_CORRUPT", failureCode);
        Integer audits = jdbc.queryForObject(
            "select count(*) from audit_log where entity_id=? and action='SUBMISSION_STATUS_CHANGED'",
            Integer.class, id);
        assertEquals(1, audits);
    }

    // ---------- finalize ----------

    @Test
    void finalize_completedSetsRetentionAndIsIdempotent() {
        UUID id = newSubmission("COMPLETED");
        UUID requestId = UUID.randomUUID(); Instant ts = Instant.now();
        Instant finalizedAt = Instant.parse("2026-11-23T10:00:00Z");

        Map<String, Object> res = controller.finalize(id,
            Map.of("finalizedAt", finalizedAt.toString()), CALLER, requestId, ts,
            sign(CALLER, requestId, ts));
        assertEquals("FINALIZED", res.get("status"));

        Map<String, Object> row = jdbc.queryForMap(
            "select finalized_at, retention_deadline, version from submission where submission_id=?", id);
        Instant storedFinalized = ((Timestamp) row.get("finalized_at")).toInstant();
        Instant storedRetention = ((Timestamp) row.get("retention_deadline")).toInstant();
        assertEquals(finalizedAt.truncatedTo(ChronoUnit.MICROS), storedFinalized.truncatedTo(ChronoUnit.MICROS));
        // retention = finalized_at + 1 calendar year
        assertTrue(ChronoUnit.DAYS.between(storedFinalized, storedRetention) > 360);

        // replay → 200, dates untouched, version unchanged
        int versionAfter = ((Number) row.get("version")).intValue();
        Map<String, Object> replay = controller.finalize(id, Map.of(), CALLER, requestId, ts,
            sign(CALLER, requestId, ts));
        assertEquals("FINALIZED", replay.get("status"));
        assertEquals(versionAfter, ((Number) replay.get("version")).intValue());

        // non-COMPLETED/FINALIZED → 409
        UUID grading = newSubmission("GRADING");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.finalize(grading, Map.of(), CALLER, requestId, ts,
                sign(CALLER, requestId, ts)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("ILLEGAL_TRANSITION", ex.getReason());
    }

    // ---------- files access ----------

    @Test
    void fileAccess_anonymizedFlowAndFileNotReady() {
        UUID id = newSubmission("READY_FOR_GRADING");
        jdbc.update("""
            insert into exam_file(file_id,submission_id,type,version,size_bytes,object_key,is_current)
            values(?,?,'ANONYMIZED',1,123,'anonymized-key.pdf',true)""",
            UUID.randomUUID(), id);
        UUID requestId = UUID.randomUUID(); Instant ts = Instant.now();

        Map<String, Object> res = controller.fileAccess(id, "ANONYMIZED", CALLER, requestId, ts,
            sign(CALLER, requestId, ts));
        assertTrue(res.get("url").toString().contains("anonymized-key.pdf"));
        assertNotNull(res.get("expiresAt"));

        // ORIGINAL is never granted internally
        ResponseStatusException forbidden = assertThrows(ResponseStatusException.class,
            () -> controller.fileAccess(id, "ORIGINAL", CALLER, requestId, ts,
                sign(CALLER, requestId, ts)));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());

        // reprocess in flight → 409 FILE_NOT_READY
        jdbc.update("update submission set status='ANONYMIZING' where submission_id=?", id);
        ResponseStatusException notReady = assertThrows(ResponseStatusException.class,
            () -> controller.fileAccess(id, "ANONYMIZED", CALLER, requestId, ts,
                sign(CALLER, requestId, ts)));
        assertEquals(HttpStatus.CONFLICT, notReady.getStatusCode());
        assertEquals("FILE_NOT_READY", notReady.getReason());

        // no anonymized row → 404
        UUID bare = newSubmission("READY_FOR_GRADING");
        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
            () -> controller.fileAccess(bare, "ANONYMIZED", CALLER, requestId, ts,
                sign(CALLER, requestId, ts)));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
    }
}
