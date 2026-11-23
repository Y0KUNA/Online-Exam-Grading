package vn.edu.examgrading.submission.job;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.examgrading.common.security.HmacSigner;
import vn.edu.examgrading.submission.client.IdentityClient;
import vn.edu.examgrading.submission.controller.InternalSubmissionController;
import vn.edu.examgrading.submission.domain.SubmissionStatus;
import vn.edu.examgrading.submission.dto.PseudonymResponse;
import vn.edu.examgrading.submission.messaging.AnonymizeJobMessage;
import vn.edu.examgrading.submission.messaging.ValidationWorker.ValidateJobMessage;
import vn.edu.examgrading.submission.repository.JdbcExamFileRepository;
import vn.edu.examgrading.submission.repository.JdbcPaperTemplateRepository;
import vn.edu.examgrading.submission.repository.JdbcProcessingJobRepository;
import vn.edu.examgrading.submission.repository.JdbcSubmissionEventRepository;
import vn.edu.examgrading.submission.repository.JdbcSubmissionRepository;
import vn.edu.examgrading.submission.storage.InMemoryObjectStorage;
import vn.edu.examgrading.submission.storage.S3ObjectStorage;
import vn.edu.examgrading.submission.storage.config.StorageProperties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for the Ticket 07 schedulers (upload-abandonment sweep,
 * orphan-object scan, processing-job reconciler) and the §9.3 submission purge
 * endpoint — all against the compose PostgreSQL {@code submission_it} database
 * (same convention as {@code SubmissionPublicApiIT}). Identity/RabbitMQ are
 * mocks; object storage is the in-memory double so orphan aging is deterministic.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchedulerJobsIT {

    private static final String SECRET = "it-hmac-secret";
    private static final String PURGE_CALLER = "result-review-service";
    private static final byte[] PDF = "%PDF-1.4 fake".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private JdbcTemplate jdbc;
    private JdbcSubmissionRepository submissionRepo;
    private JdbcSubmissionEventRepository eventRepo;
    private JdbcExamFileRepository examFileRepo;
    private JdbcProcessingJobRepository jobRepo;
    private InMemoryObjectStorage storage;
    private IdentityClient identityClient;
    private RabbitTemplate rabbitTemplate;
    private StorageProperties props;
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

        submissionRepo = new JdbcSubmissionRepository(jdbc);
        eventRepo = new JdbcSubmissionEventRepository(jdbc);
        examFileRepo = new JdbcExamFileRepository(jdbc);
        jobRepo = new JdbcProcessingJobRepository(jdbc);
        storage = new InMemoryObjectStorage();
        identityClient = mock(IdentityClient.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        props = new StorageProperties("memory", "http://minio:9000", "us-east-1",
            "key", "secret", "original", "anonymized", "http://gateway:8080/storage",
            52428800L, 1024L);
    }

    @BeforeEach
    void clean() {
        Mockito.reset(identityClient, rabbitTemplate);
        jdbc.update("delete from processing_job where submission_id in (select submission_id from submission where exam_id=?)", examId);
        jdbc.update("delete from submission_event where submission_id in (select submission_id from submission where exam_id=?)", examId);
        jdbc.update("delete from exam_file where submission_id in (select submission_id from submission where exam_id=?)", examId);
        jdbc.update("delete from audit_log where entity_id in (select submission_id from submission where exam_id=?)", examId);
        jdbc.update("delete from submission where exam_id=?", examId);
    }

    private UUID newSubmission(String status, Instant uploadExpiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into submission(submission_id,exam_id,status,version,upload_expires_at,object_key)
            values(?,?,?,?,?,?)""",
            id, examId, status, 0,
            uploadExpiresAt == null ? null : java.sql.Timestamp.from(uploadExpiresAt),
            "key/" + id);
        return id;
    }

    private void ageJob(UUID jobId) {
        jdbc.update("update processing_job set updated_at = now() - interval '1 hour' where job_id=?", jobId);
    }

    // ---------- UploadCleanupJob ----------

    @Test
    void uploadCleanup_failsExpiredUploading_releasesBinding() {
        UUID expired = newSubmission("UPLOADING", Instant.now().minus(1, ChronoUnit.HOURS));
        UUID fresh = newSubmission("UPLOADING", Instant.now().plus(1, ChronoUnit.HOURS));
        UUID uploaded = newSubmission("UPLOADED", Instant.now().minus(1, ChronoUnit.HOURS));
        UploadCleanupJob job = new UploadCleanupJob(submissionRepo, eventRepo,
            identityClient, jdbc, Clock.systemUTC());

        job.run();

        Map<String, Object> row = jdbc.queryForMap(
            "select status, failure_code from submission where submission_id=?", expired);
        assertEquals("FAILED", row.get("status"));
        assertEquals("UPLOAD_ABANDONED", row.get("failure_code"));
        Integer events = jdbc.queryForObject(
            "select count(*) from submission_event where submission_id=? and status='FAILED'",
            Integer.class, expired);
        assertEquals(1, events);
        Integer audits = jdbc.queryForObject(
            "select count(*) from audit_log where entity_id=? and action='SUBMISSION_STATUS_CHANGED'",
            Integer.class, expired);
        assertEquals(1, audits);
        verify(identityClient).releaseBinding(eq(expired), eq("UPLOAD_ABANDONED"));

        // untouched rows stay as they were
        assertEquals("UPLOADING", jdbc.queryForObject(
            "select status from submission where submission_id=?", String.class, fresh));
        assertEquals("UPLOADED", jdbc.queryForObject(
            "select status from submission where submission_id=?", String.class, uploaded));
        verify(identityClient, never()).releaseBinding(eq(fresh), any());
    }

    // ---------- OrphanObjectCleanupJob ----------

    @Test
    void orphanCleanup_deletesOnlyTrueOrphans() throws Exception {
        // Objects "put" 2h ago via a fixed-past clock.
        InMemoryObjectStorage aged = new InMemoryObjectStorage(
            Clock.fixed(Instant.now().minus(2, ChronoUnit.HOURS), java.time.ZoneOffset.UTC));
        String orphanKey = "orphan/object.pdf";
        String examFileKey = "live/examfile.pdf";
        String uploadingKey = "live/uploading.pdf";
        aged.put(props.bucket(), orphanKey, PDF, "application/pdf");
        aged.put(props.bucket(), examFileKey, PDF, "application/pdf");
        aged.put(props.bucket(), uploadingKey, PDF, "application/pdf");
        // a fresh object (< grace) must survive even though unreferenced
        storage.put(props.bucket(), "fresh/new.pdf", PDF, "application/pdf");

        UUID withFile = newSubmission("UPLOADED", null);
        jdbc.update("""
            insert into exam_file(file_id,submission_id,type,version,size_bytes,object_key,is_current)
            values(?,?,'ORIGINAL',1,?, ?, true)""",
            UUID.randomUUID(), withFile, (long) PDF.length, examFileKey);
        UUID uploading = newSubmission("UPLOADING", Instant.now().plus(1, ChronoUnit.HOURS));
        jdbc.update("update submission set object_key=? where submission_id=?",
            uploadingKey, uploading);

        OrphanObjectCleanupJob job = new OrphanObjectCleanupJob(aged, props, examFileRepo,
            submissionRepo, Clock.systemUTC(), 60);
        job.run();

        assertTrue(aged.head(props.bucket(), orphanKey).isEmpty(), "orphan must be deleted");
        assertTrue(aged.head(props.bucket(), examFileKey).isPresent(), "exam_file object must stay");
        assertTrue(aged.head(props.bucket(), uploadingKey).isPresent(), "UPLOADING object must stay");
        assertTrue(storage.head(props.bucket(), "fresh/new.pdf").isPresent(), "fresh object must stay");
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) return value;
        try {
            Path envFile = Path.of("..", ".env");
            if (Files.exists(envFile)) {
                for (String line : Files.readAllLines(envFile)) {
                    if (line.startsWith(key + "=")) {
                        String v = line.substring(line.indexOf('=') + 1).trim();
                        if (!v.isBlank()) return v;
                    }
                }
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    @Test
    void orphanCleanup_realMinio_deletesUnreferencedKeepsReferenced() throws Exception {
        // .env's S3_INTERNAL_ENDPOINT is the docker-internal hostname; from the
        // host the published port is localhost:9000 (getenv still wins).
        String endpoint = System.getenv("S3_INTERNAL_ENDPOINT") != null
            && !System.getenv("S3_INTERNAL_ENDPOINT").isBlank()
            ? System.getenv("S3_INTERNAL_ENDPOINT") : "http://localhost:9000";
        StorageProperties s3Props = new StorageProperties("s3",
            endpoint,
            env("S3_REGION", "us-east-1"),
            env("S3_ACCESS_KEY", "minioadmin"),
            env("S3_SECRET_KEY", "change-me"),
            "original", "anonymized",
            env("S3_PUBLIC_BASE", "http://localhost:8080/storage"),
            52428800L, 1024L);
        S3ObjectStorage s3 = S3ObjectStorage.create(s3Props, Clock.systemUTC());

        String prefix = "it-orphan/" + UUID.randomUUID() + "/";
        String orphanKey = prefix + "orphan.pdf";
        String examFileKey = prefix + "examfile.pdf";
        String uploadingKey = prefix + "uploading.pdf";
        try {
            s3.put(s3Props.bucket(), orphanKey, PDF, "application/pdf");
            s3.put(s3Props.bucket(), examFileKey, PDF, "application/pdf");
            s3.put(s3Props.bucket(), uploadingKey, PDF, "application/pdf");

            UUID withFile = newSubmission("UPLOADED", null);
            jdbc.update("""
                insert into exam_file(file_id,submission_id,type,version,size_bytes,object_key,is_current)
                values(?,?,'ORIGINAL',1,?, ?, true)""",
                UUID.randomUUID(), withFile, (long) PDF.length, examFileKey);
            UUID uploading = newSubmission("UPLOADING", Instant.now().plus(1, ChronoUnit.HOURS));
            jdbc.update("update submission set object_key=? where submission_id=?",
                uploadingKey, uploading);

            // Negative grace → cutoff in the future: every listed object counts
            // as "old enough"; the exam_file/UPLOADING guards are what is under test.
            OrphanObjectCleanupJob job = new OrphanObjectCleanupJob(s3, s3Props, examFileRepo,
                submissionRepo, Clock.systemUTC(), -1);
            job.run();

            assertTrue(s3.head(s3Props.bucket(), orphanKey).isEmpty(), "orphan must be deleted from MinIO");
            assertTrue(s3.head(s3Props.bucket(), examFileKey).isPresent(), "exam_file object must stay");
            assertTrue(s3.head(s3Props.bucket(), uploadingKey).isPresent(), "UPLOADING object must stay");
        } finally {
            for (String key : new String[]{orphanKey, examFileKey, uploadingKey}) {
                try { s3.delete(s3Props.bucket(), key); } catch (RuntimeException ignored) { }
            }
        }
    }

    // ---------- ProcessingJobReconciler ----------

    @Test
    void reconciler_republishesStaleValidateAndAnonymize() {
        UUID validateSubmission = newSubmission("VALIDATING", null);
        UUID anonymizeSubmission = newSubmission("ANONYMIZING", null);
        UUID validateJobId = UUID.randomUUID();
        UUID anonymizeJobId = UUID.randomUUID();
        jobRepo.insertQueued(validateJobId, validateSubmission, "VALIDATE", 1, null);
        jobRepo.insertQueued(anonymizeJobId, anonymizeSubmission, "ANONYMIZE", 1, null);
        ageJob(validateJobId);
        ageJob(anonymizeJobId);
        when(identityClient.createPseudonym(any(), eq(anonymizeSubmission), any()))
            .thenReturn(new PseudonymResponse(UUID.randomUUID(), "H3", anonymizeSubmission));

        ProcessingJobReconciler reconciler = new ProcessingJobReconciler(jobRepo,
            submissionRepo, identityClient, rabbitTemplate, Clock.systemUTC(), 10);
        reconciler.run();

        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, times(2)).convertAndSend(eq("submission.processing"),
            anyString(), captor.capture());
        List<Object> messages = captor.getAllValues();
        ValidateJobMessage vm = messages.stream().filter(ValidateJobMessage.class::isInstance)
            .map(ValidateJobMessage.class::cast).findFirst().orElseThrow();
        assertEquals(validateJobId, vm.jobId());
        assertEquals(validateSubmission, vm.submissionId());
        assertEquals(examId, vm.examId());

        AnonymizeJobMessage am = messages.stream().filter(AnonymizeJobMessage.class::isInstance)
            .map(AnonymizeJobMessage.class::cast).findFirst().orElseThrow();
        assertEquals(anonymizeJobId, am.jobId());
        assertEquals("H3", am.pseudonym());
        verify(identityClient).createPseudonym(examId, anonymizeSubmission,
            anonymizeSubmission + ":pseudonym");
    }

    @Test
    void reconciler_ignoresFreshAndTerminalJobs() {
        UUID freshSubmission = newSubmission("VALIDATING", null);
        UUID freshJob = UUID.randomUUID();
        jobRepo.insertQueued(freshJob, freshSubmission, "VALIDATE", 1, null); // updated_at = now → not stale
        UUID failedSubmission = newSubmission("FAILED", null);
        UUID failedJob = UUID.randomUUID();
        jobRepo.insertQueued(failedJob, failedSubmission, "VALIDATE", 1, null);
        ageJob(failedJob);

        ProcessingJobReconciler reconciler = new ProcessingJobReconciler(jobRepo,
            submissionRepo, identityClient, rabbitTemplate, Clock.systemUTC(), 10);
        reconciler.run();

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(),
            any(ValidateJobMessage.class));
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(),
            any(AnonymizeJobMessage.class));
    }

    // ---------- submission purge endpoint (§9.3) ----------

    @Test
    void purgeDetail_removesEverythingAndIsIdempotent() {
        InternalSubmissionController controller = new InternalSubmissionController(jdbc, SECRET,
            examFileRepo, storage, props, eventRepo);
        UUID id = newSubmission("FINALIZED", null);
        jdbc.update("""
            insert into exam_file(file_id,submission_id,type,version,size_bytes,object_key,is_current)
            values(?,?,'ORIGINAL',1,?, ?, true)""",
            UUID.randomUUID(), id, (long) PDF.length, "purge/orig.pdf");
        jdbc.update("""
            insert into exam_file(file_id,submission_id,type,version,size_bytes,object_key,sha256,is_current)
            values(?,?,'ANONYMIZED',1,?, ?, 'aa', true)""",
            UUID.randomUUID(), id, (long) PDF.length, "purge/anon.pdf");
        storage.put(props.bucket(), "purge/orig.pdf", PDF, "application/pdf");
        storage.put(props.anonymizedBucket(), "purge/anon.pdf", PDF, "application/pdf");
        eventRepo.append(id, SubmissionStatus.FINALIZED, 100);
        jdbc.update("insert into audit_log(id,actor_id,action,entity_type,entity_id) values(?,null,'X','submission',?)",
            UUID.randomUUID(), id);

        UUID requestId = UUID.randomUUID(); Instant ts = Instant.now();
        String sig = HmacSigner.sign(SECRET,
            PURGE_CALLER + "\n" + requestId + "\n" + ts.toEpochMilli());
        Map<String, Object> res = controller.purgeDetail(id, PURGE_CALLER, requestId, ts, sig);
        assertEquals("DONE", res.get("status"));

        assertEquals(0, jdbc.queryForObject(
            "select count(*) from submission where submission_id=?", Integer.class, id));
        for (String table : new String[]{"exam_file", "submission_event", "processing_job", "audit_log"}) {
            assertEquals(0, jdbc.queryForObject(
                "select count(*) from " + table + " where "
                    + (table.equals("audit_log") ? "entity_id" : "submission_id") + "=?",
                Integer.class, id), table + " rows must be gone");
        }
        assertTrue(storage.head(props.bucket(), "purge/orig.pdf").isEmpty());
        assertTrue(storage.head(props.anonymizedBucket(), "purge/anon.pdf").isEmpty());

        // replay → still DONE
        UUID rid2 = UUID.randomUUID(); Instant ts2 = Instant.now();
        String sig2 = HmacSigner.sign(SECRET,
            PURGE_CALLER + "\n" + rid2 + "\n" + ts2.toEpochMilli());
        assertEquals("DONE", controller.purgeDetail(id, PURGE_CALLER, rid2, ts2, sig2).get("status"));

        // wrong caller → 403
        UUID rid3 = UUID.randomUUID(); Instant ts3 = Instant.now();
        String sig3 = HmacSigner.sign(SECRET,
            "submission-worker\n" + rid3 + "\n" + ts3.toEpochMilli());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.purgeDetail(id, "submission-worker", rid3, ts3, sig3));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }
}
