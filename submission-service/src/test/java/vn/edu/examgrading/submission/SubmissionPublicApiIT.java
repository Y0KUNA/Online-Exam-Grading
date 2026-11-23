package vn.edu.examgrading.submission;

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
import vn.edu.examgrading.submission.client.IdentityClient;
import vn.edu.examgrading.submission.controller.SubmissionController;
import vn.edu.examgrading.submission.domain.SubmissionStatus;
import vn.edu.examgrading.submission.dto.CompleteSubmissionResponse;
import vn.edu.examgrading.submission.dto.InitSubmissionRequest;
import vn.edu.examgrading.submission.dto.InitSubmissionResponse;
import vn.edu.examgrading.submission.dto.SubmissionIdentity;
import vn.edu.examgrading.submission.dto.SubmissionPageResponse;
import vn.edu.examgrading.submission.dto.UpdatePseudonymRequest;
import vn.edu.examgrading.submission.repository.JdbcExamFileRepository;
import vn.edu.examgrading.submission.repository.JdbcPaperTemplateRepository;
import vn.edu.examgrading.submission.repository.JdbcProcessingJobRepository;
import vn.edu.examgrading.submission.repository.JdbcSubmissionEventRepository;
import vn.edu.examgrading.submission.repository.JdbcSubmissionRepository;
import vn.edu.examgrading.submission.repository.SubmissionEventRepository;
import vn.edu.examgrading.submission.service.SubmissionUploadServiceImpl;
import vn.edu.examgrading.submission.storage.InMemoryObjectStorage;
import vn.edu.examgrading.submission.storage.config.StorageProperties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test for the Ticket 06 public submission APIs
 * (spec 05 / E-06): init → upload → complete → detail/list/files/events,
 * identity join, and manual pseudonym edit.
 *
 * Runs against the compose PostgreSQL at localhost:5433 on the dedicated
 * {@code submission_it} database (same convention as
 * {@link vn.edu.examgrading.submission.controller.InternalSubmissionControllerIT});
 * Flyway rebuilds the schema from scratch on every run. Storage is the
 * in-memory double; Identity and RabbitMQ are mocks — this exercises the
 * real SQL/repository/service/controller path end to end.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubmissionPublicApiIT {

    private static final byte[] PDF =
        "%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private JdbcTemplate jdbc;
    private InMemoryObjectStorage storage;
    private JdbcSubmissionEventRepository eventRepo;
    private SubmissionController controller;
    private SubmissionUploadServiceImpl service;
    private IdentityClient identityClient;
    private vn.edu.examgrading.submission.client.ResultClient resultClient;
    private vn.edu.examgrading.submission.client.GradingClient gradingClient;
    private RabbitTemplate rabbitTemplate;
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

        storage = new InMemoryObjectStorage();
        eventRepo = new JdbcSubmissionEventRepository(jdbc);
        identityClient = mock(IdentityClient.class);
        resultClient = mock(vn.edu.examgrading.submission.client.ResultClient.class);
        gradingClient = mock(vn.edu.examgrading.submission.client.GradingClient.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        StorageProperties props = new StorageProperties("memory", "http://minio:9000", "us-east-1",
            "key", "secret", "original", "anonymized", "http://gateway:8080/storage",
            52428800L, 1024L);
        service = new SubmissionUploadServiceImpl(
            new JdbcSubmissionRepository(jdbc),
            new JdbcPaperTemplateRepository(jdbc),
            eventRepo,
            new JdbcExamFileRepository(jdbc),
            new JdbcProcessingJobRepository(jdbc),
            new vn.edu.examgrading.submission.repository.JdbcFileReplacementRepository(jdbc),
            storage, props, Clock.systemUTC(),
            identityClient, resultClient, gradingClient, rabbitTemplate, jdbc, 10);
        controller = new SubmissionController(service);
    }

    @BeforeEach
    void clean() {
        Mockito.reset(identityClient, resultClient, gradingClient, rabbitTemplate);
        jdbc.update("delete from processing_job where submission_id in (select submission_id from submission where exam_id=?)", examId);
        jdbc.update("delete from submission_event where submission_id in (select submission_id from submission where exam_id=?)", examId);
        jdbc.update("delete from file_replacement where submission_id in (select submission_id from submission where exam_id=?)", examId);
        jdbc.update("delete from exam_file where submission_id in (select submission_id from submission where exam_id=?)", examId);
        jdbc.update("delete from audit_log where entity_id in (select submission_id from submission where exam_id=?)", examId);
        jdbc.update("delete from submission where exam_id=?", examId);
    }

    private UUID seedTemplate() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into paper_template(template_id,name,page_width,page_height,mask,active)
            values(?,?,210.00,297.00,?::jsonb,true)""",
            id, "IT Template",
            "{\"mask\":{\"x\":10,\"y\":10,\"width\":100,\"height\":20},"
                + "\"print\":{\"x\":20,\"y\":15,\"width\":80,\"height\":10}}" );
        return id;
    }

    private UUID newSubmission(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into submission(submission_id,exam_id,status,version) values(?,?,?,0)",
            id, examId, status);
        return id;
    }

    private InitSubmissionResponse initUpload(UUID templateId) {
        return controller.init(examId, "Bearer it-token",
            new InitSubmissionRequest(UUID.randomUUID(), templateId, "scan.pdf",
                PDF.length, "application/pdf")).getBody();
    }

    // ---------- init → upload → complete → public projections ----------

    @Test
    void initComplete_detailListFilesAndEvents() {
        UUID templateId = seedTemplate();

        InitSubmissionResponse init = initUpload(templateId);
        UUID submissionId = UUID.fromString(init.submissionId());
        assertEquals("UPLOADING", init.status());
        assertEquals("/api/v1/submissions/" + submissionId, init.statusUrl());

        // Simulate the browser POST: the object key lives in upload.fields.key.
        String objectKey = init.upload().fields().get("key");
        assertNotNull(objectKey);
        storage.put(objectKey, PDF, "application/pdf");

        CompleteSubmissionResponse done = controller.complete(submissionId).getBody();
        assertEquals("UPLOADED", done.status());

        // detail: spec projection only — no objectKey/templateId/version/failureCode
        Map<String, Object> detail = controller.detail(submissionId);
        assertEquals(submissionId, detail.get("submissionId"));
        assertEquals(examId, detail.get("examId"));
        assertEquals("UPLOADED", detail.get("status"));
        assertEquals(0, ((Number) detail.get("progress")).intValue());
        assertNotNull(detail.get("updatedAt"));
        assertFalse(detail.containsKey("objectKey"));
        assertFalse(detail.containsKey("templateId"));
        assertFalse(detail.containsKey("version"));
        assertFalse(detail.containsKey("pageCount")); // null until VALIDATE writes it

        // list: single item, same projection
        SubmissionPageResponse page = controller.list(examId, null, null, null);
        assertEquals(1, page.items().size());
        assertNull(page.nextCursor());
        assertEquals(submissionId, page.items().get(0).get("submissionId"));
        assertFalse(page.items().get(0).containsKey("objectKey"));

        // files: ORIGINAL version listed, never the storage key
        Map<String, Object> files = controller.files(submissionId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) files.get("items");
        assertEquals(1, items.size());
        assertEquals("ORIGINAL", items.get(0).get("type"));
        assertEquals(1, ((Number) items.get(0).get("version")).intValue());
        assertEquals(true, items.get(0).get("isCurrent"));
        assertEquals(PDF.length, ((Number) items.get(0).get("sizeBytes")).longValue());
        assertFalse(items.get(0).containsKey("objectKey"));
        assertFalse(items.get(0).containsKey("object_key"));

        // processing job queued + validate message published
        Integer jobs = jdbc.queryForObject(
            "select count(*) from processing_job where submission_id=? and step='VALIDATE' and status='QUEUED'",
            Integer.class, submissionId);
        assertEquals(1, jobs);
        verify(rabbitTemplate).convertAndSend(eq("submission.processing"), eq("validate"),
            any(vn.edu.examgrading.submission.messaging.ValidationWorker.ValidateJobMessage.class));

        // SSE data source: submission_event holds UPLOADING then UPLOADED in order
        List<SubmissionEventRepository.EventRow> events =
            eventRepo.findByExamAfter(examId, 0, 100);
        assertEquals(2, events.size());
        assertEquals(SubmissionStatus.UPLOADING, events.get(0).status());
        assertEquals(SubmissionStatus.UPLOADED, events.get(1).status());
        assertTrue(events.get(1).eventId() > events.get(0).eventId());

        // idempotent complete replay
        assertEquals("UPLOADED", controller.complete(submissionId).getBody().status());
    }

    // ---------- list pagination (D-06 keyset cursor) ----------

    @Test
    void list_paginatesWithOpaqueCursor() {
        // Seed 5 submissions with strictly increasing updated_at.
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                insert into submission(submission_id,exam_id,status,version,updated_at)
                values(?,?, 'UPLOADED', 0, ?)""",
                id, examId, java.sql.Timestamp.from(base.plus(i, ChronoUnit.HOURS)));
        }

        SubmissionPageResponse p1 = controller.list(examId, null, null, 2);
        assertEquals(2, p1.items().size());
        assertNotNull(p1.nextCursor());
        // newest first
        assertTrue(((Instant) p1.items().get(0).get("updatedAt"))
            .isAfter((Instant) p1.items().get(1).get("updatedAt")));

        SubmissionPageResponse p2 = controller.list(examId, null, p1.nextCursor(), 2);
        assertEquals(2, p2.items().size());
        assertNotNull(p2.nextCursor());

        SubmissionPageResponse p3 = controller.list(examId, null, p2.nextCursor(), 2);
        assertEquals(1, p3.items().size());
        assertNull(p3.nextCursor());

        // no overlap across pages
        java.util.Set<Object> seen = new java.util.HashSet<>();
        for (SubmissionPageResponse p : List.of(p1, p2, p3)) {
            for (Map<String, Object> item : p.items()) {
                assertTrue(seen.add(item.get("submissionId")), "duplicate across pages");
            }
        }
        assertEquals(5, seen.size());
    }

    @Test
    void list_invalidParams_mapTo400() {
        ResponseStatusException badStatus = assertThrows(ResponseStatusException.class,
            () -> controller.list(examId, "NOT_A_STATUS", null, null));
        assertEquals(HttpStatus.BAD_REQUEST, badStatus.getStatusCode());
        assertEquals("INVALID_STATUS", badStatus.getReason());

        ResponseStatusException badCursor = assertThrows(ResponseStatusException.class,
            () -> controller.list(examId, null, "!!!not-base64!!!", null));
        assertEquals(HttpStatus.BAD_REQUEST, badCursor.getStatusCode());
        assertEquals("INVALID_CURSOR", badCursor.getReason());

        ResponseStatusException badLimit = assertThrows(ResponseStatusException.class,
            () -> controller.list(examId, null, null, 0));
        assertEquals(HttpStatus.BAD_REQUEST, badLimit.getStatusCode());
        assertEquals("INVALID_LIMIT", badLimit.getReason());
    }

    // ---------- identity join (FR-019) ----------

    @Test
    void identity_returnsPublicShapeAndAudits() {
        UUID submissionId = newSubmission("UPLOADED");
        UUID candidateId = UUID.randomUUID();
        when(identityClient.getSubmissionIdentity(submissionId)).thenReturn(
            new SubmissionIdentity(candidateId, "SV001", "Nguyen Van A", "CT1", "H1"));

        Map<String, Object> res = controller.identity(submissionId);

        // candidateId is internal-only — stripped from the public projection
        assertFalse(res.containsKey("candidateId"));
        assertEquals("SV001", res.get("candidateNumber"));
        assertEquals("Nguyen Van A", res.get("fullName"));
        assertEquals("CT1", res.get("className"));
        assertEquals("H1", res.get("pseudonym"));

        // FR-019: every join is audited; detail carries no PII
        Integer audits = jdbc.queryForObject(
            "select count(*) from audit_log where entity_id=? and action='SUBMISSION_IDENTITY_VIEWED'",
            Integer.class, submissionId);
        assertEquals(1, audits);
        String detail = jdbc.queryForObject(
            "select detail::text from audit_log where entity_id=? and action='SUBMISSION_IDENTITY_VIEWED'",
            String.class, submissionId);
        assertFalse(detail.contains("Nguyen Van A"));
        assertFalse(detail.contains("H1"));
    }

    @Test
    void identity_missingBinding_404_noAudit() {
        UUID submissionId = newSubmission("UPLOADED");
        when(identityClient.getSubmissionIdentity(submissionId)).thenThrow(
            new ResponseStatusException(HttpStatus.NOT_FOUND, "BINDING_NOT_FOUND"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.identity(submissionId));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());

        Integer audits = jdbc.queryForObject(
            "select count(*) from audit_log where entity_id=? and action='SUBMISSION_IDENTITY_VIEWED'",
            Integer.class, submissionId);
        assertEquals(0, audits);
    }

    // ---------- manual pseudonym edit ----------

    @Test
    void pseudonym_update_forwardsActorAndAudits() {
        UUID submissionId = newSubmission("UPLOADED");
        String jwt = "Bearer eyJhbGciOiJSUzI1NiJ9."
            + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"user-42\"}".getBytes())
            + ".sig";

        Map<String, Object> res = controller.updatePseudonym(submissionId,
            new UpdatePseudonymRequest("  H9  ", "sửa phách"), jwt);

        assertEquals(submissionId, res.get("submissionId"));
        assertEquals("H9", res.get("pseudonym"));
        verify(identityClient).updatePseudonym(submissionId, "H9", "sửa phách", "user-42");

        Integer audits = jdbc.queryForObject(
            "select count(*) from audit_log where entity_id=? and action='SUBMISSION_PSEUDONYM_CHANGED'",
            Integer.class, submissionId);
        assertEquals(1, audits);
    }

    @Test
    void pseudonym_finalized_conflictAndBlankReason_422() {
        UUID finalized = newSubmission("FINALIZED");
        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
            () -> controller.updatePseudonym(finalized,
                new UpdatePseudonymRequest("H9", "reason"), null));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals("INVALID_STATE", conflict.getReason());
        verifyNoInteractions(identityClient);

        UUID open = newSubmission("UPLOADED");
        ResponseStatusException noReason = assertThrows(ResponseStatusException.class,
            () -> controller.updatePseudonym(open,
                new UpdatePseudonymRequest("H9", "  "), null));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, noReason.getStatusCode());
        assertEquals("REASON_REQUIRED", noReason.getReason());
    }

    // ---------- SSE replay source ----------

    @Test
    void events_replayFromLastEventId() {
        UUID templateId = seedTemplate();
        InitSubmissionResponse init = initUpload(templateId);
        UUID submissionId = UUID.fromString(init.submissionId());
        String objectKey = init.upload().fields().get("key");
        storage.put(objectKey, PDF, "application/pdf");
        controller.complete(submissionId);

        List<SubmissionEventRepository.EventRow> all =
            eventRepo.findByExamAfter(examId, 0, 100);
        assertEquals(2, all.size());

        // Last-Event-ID replay: only rows after the cursor come back
        List<SubmissionEventRepository.EventRow> replay =
            eventRepo.findByExamAfter(examId, all.get(0).eventId(), 100);
        assertEquals(1, replay.size());
        assertEquals(SubmissionStatus.UPLOADED, replay.get(0).status());
        assertEquals(submissionId, replay.get(0).submissionId());
        assertNotNull(replay.get(0).updatedAt());
    }

    // ---------- §4.4 replace-file flow ----------

    private UUID failedSubmission(String failureCode, int fileVersion) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into submission(submission_id,exam_id,status,current_file_version,version,
                object_key,failure_code)
            values(?,?,'FAILED',?,?,?,?)""",
            id, examId, fileVersion, 0, "old/v" + fileVersion + ".pdf", failureCode);
        jdbc.update("""
            insert into exam_file(file_id,submission_id,type,version,size_bytes,object_key,is_current)
            values(?,?,'ORIGINAL',?,?,?,true)""",
            UUID.randomUUID(), id, fileVersion, 100L, "old/v" + fileVersion + ".pdf");
        return id;
    }

    @SuppressWarnings("unchecked")
    private UUID requestReplacement(UUID submissionId) {
        Map<String, Object> res = controller.replaceFile(submissionId,
            new vn.edu.examgrading.submission.dto.ReplaceFileRequest(
                "scan nhòe", "rescan.pdf", 100L, "application/pdf"), null).getBody();
        Map<String, Object> replacement = (Map<String, Object>) res.get("replacement");
        return UUID.fromString(replacement.get("replacementId").toString());
    }

    @Test
    void replaceFileFlow_endToEnd_commitsNewVersionAndReplays() {
        UUID submissionId = failedSubmission("PDF_CORRUPT", 1);
        when(gradingClient.attemptSummary(submissionId))
            .thenReturn(Map.of("confirmedCount", 0, "draftCount", 0, "openAssignmentCount", 0));
        doNothing().when(gradingClient).discardOpenWork(eq(submissionId), any());

        UUID replacementId = requestReplacement(submissionId);

        // PENDING row + audit REQUESTED; submission status unchanged
        Map<String, Object> row = jdbc.queryForMap(
            "select status,version,object_key from file_replacement where replacement_id=?", replacementId);
        assertEquals("PENDING", row.get("status"));
        assertEquals(2, ((Number) row.get("version")).intValue());
        String v2Key = row.get("object_key").toString();
        assertTrue(v2Key.endsWith("/v2.pdf"));
        assertEquals("FAILED", jdbc.queryForObject(
            "select status from submission where submission_id=?", String.class, submissionId));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from audit_log where entity_id=? and action='REPLACE_FILE_REQUESTED'",
            Integer.class, submissionId));

        // upload bytes to the replacement key, then complete
        storage.put("original", v2Key, PDF, "application/pdf");
        Map<String, Object> done = controller.completeReplacement(submissionId, replacementId);
        assertEquals("UPLOADED", done.get("status"));
        assertEquals(2, done.get("version"));

        // exam_file: v2 current ORIGINAL, v1 demoted but retained
        List<Map<String, Object>> files = jdbc.queryForList(
            "select version,is_current from exam_file where submission_id=? and type='ORIGINAL' order by version",
            submissionId);
        assertEquals(2, files.size());
        assertEquals(false, files.get(0).get("is_current"));
        assertEquals(true, files.get(1).get("is_current"));
        assertEquals(2, jdbc.queryForObject(
            "select current_file_version from submission where submission_id=?", Integer.class, submissionId));
        assertNull(jdbc.queryForObject(
            "select failure_code from submission where submission_id=?", String.class, submissionId));

        // replacement COMPLETED + VALIDATE job at file_version=2 + events + audit
        assertEquals("COMPLETED", jdbc.queryForObject(
            "select status from file_replacement where replacement_id=?", String.class, replacementId));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from processing_job where submission_id=? and step='VALIDATE' and file_version=2",
            Integer.class, submissionId));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from submission_event where submission_id=? and status='UPLOADED'",
            Integer.class, submissionId));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from audit_log where entity_id=? and action='REPLACE_FILE_COMPLETED'",
            Integer.class, submissionId));
        verify(rabbitTemplate, times(1)).convertAndSend(eq("submission.processing"), eq("validate"),
            argThat((vn.edu.examgrading.submission.messaging.ValidationWorker.ValidateJobMessage m)
                -> m.submissionId().equals(submissionId) && m.fileVersion() == 2));

        // idempotent replay — no second job/event
        Map<String, Object> replay = controller.completeReplacement(submissionId, replacementId);
        assertEquals("UPLOADED", replay.get("status"));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from processing_job where submission_id=? and step='VALIDATE'",
            Integer.class, submissionId));
    }

    @Test
    void replaceFile_ineligibleStates_andCancel() {
        // UPLOADED is not replaceable
        UUID uploaded = newSubmission("UPLOADED");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.replaceFile(uploaded,
                new vn.edu.examgrading.submission.dto.ReplaceFileRequest("r", "f.pdf", 100L, "application/pdf"), null));
        assertEquals("REPLACE_NOT_ALLOWED", ex.getReason());

        // REVIEW_REQUIRED without council authorization
        UUID reviewRequired = newSubmission("REVIEW_REQUIRED");
        when(resultClient.getReplaceAuthorization(reviewRequired))
            .thenReturn(Map.of("authorized", false));
        ex = assertThrows(ResponseStatusException.class,
            () -> controller.replaceFile(reviewRequired,
                new vn.edu.examgrading.submission.dto.ReplaceFileRequest("r", "f.pdf", 100L, "application/pdf"), null));
        assertEquals("REVIEW_NOT_AUTHORIZED", ex.getReason());

        // cancel a pending replacement → CANCELLED + object removed
        UUID submissionId = failedSubmission("PAGE_COUNT_INVALID", 1);
        when(gradingClient.attemptSummary(submissionId))
            .thenReturn(Map.of("confirmedCount", 0));
        UUID replacementId = requestReplacement(submissionId);
        String v2Key = jdbc.queryForObject(
            "select object_key from file_replacement where replacement_id=?", String.class, replacementId);
        storage.put("original", v2Key, PDF, "application/pdf");

        controller.cancelReplacement(submissionId);

        assertEquals("CANCELLED", jdbc.queryForObject(
            "select status from file_replacement where replacement_id=?", String.class, replacementId));
        assertTrue(storage.head("original", v2Key).isEmpty());
        // no pending → 404 on second cancel
        ex = assertThrows(ResponseStatusException.class,
            () -> controller.cancelReplacement(submissionId));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ---------- Ticket 10: anonymization-issues (T27) + retry (T17/T18/T19) ----------

    private UUID statusedSubmission(String status, String failureCode, boolean anonymized) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into submission(submission_id,exam_id,template_id,status,current_file_version,
                version,object_key,failure_code)
            values(?,?,?,?,?,?,?,?)""",
            id, examId, UUID.randomUUID(), status, 1, 0, "old/v1.pdf", failureCode);
        jdbc.update("""
            insert into exam_file(file_id,submission_id,type,version,size_bytes,object_key,is_current)
            values(?,?,'ORIGINAL',1,100,'old/v1.pdf',true)""",
            UUID.randomUUID(), id);
        if (anonymized) {
            jdbc.update("""
                insert into exam_file(file_id,submission_id,type,version,size_bytes,object_key,is_current)
                values(?,?,'ANONYMIZED',1,90,'anon/v1.pdf',true)""",
                UUID.randomUUID(), id);
        }
        return id;
    }

    @Test
    void anonymizationIssue_t27_createsReviewThenReviewRequired_andReplays() {
        UUID submissionId = statusedSubmission("READY_FOR_GRADING", null, true);
        UUID reviewId = UUID.randomUUID();
        when(resultClient.createAnonymizationIssue(eq(submissionId), eq(examId), eq(1),
                any(), eq("vùng che thiếu"), isNull(), eq("idem-1")))
            .thenReturn(Map.of("reviewId", reviewId, "status", "OPEN"));

        var res = controller.createAnonymizationIssue(submissionId,
            Map.of("reason", "vùng che thiếu"), null, "idem-1");

        assertEquals(201, res.getStatusCode().value());
        assertEquals(reviewId, res.getBody().get("reviewId"));
        assertEquals("REVIEW_REQUIRED", jdbc.queryForObject(
            "select status from submission where submission_id=?", String.class, submissionId));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from submission_event where submission_id=? and status='REVIEW_REQUIRED'",
            Integer.class, submissionId));

        // replay on REVIEW_REQUIRED returns the same unclosed review
        when(resultClient.getReplaceAuthorization(submissionId)).thenReturn(Map.of(
            "authorized", false, "reviewId", reviewId,
            "reasonType", "ANONYMIZATION", "status", "OPEN"));
        var replay = controller.createAnonymizationIssue(submissionId,
            Map.of("reason", "vùng che thiếu"), null, "idem-1");
        assertEquals(reviewId, replay.getBody().get("reviewId"));
    }

    @Test
    void anonymizationIssue_wrongState_409() {
        UUID submissionId = statusedSubmission("UPLOADED", null, false);
        var ex = assertThrows(ResponseStatusException.class,
            () -> controller.createAnonymizationIssue(submissionId,
                Map.of("reason", "x"), null, null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        // READY_FOR_GRADING but no ANONYMIZED file → 409
        UUID noAnon = statusedSubmission("READY_FOR_GRADING", null, false);
        ex = assertThrows(ResponseStatusException.class,
            () -> controller.createAnonymizationIssue(noAnon,
                Map.of("reason", "x"), null, null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void retry_failedValidateExhausted_t19_requeuesValidate() {
        UUID submissionId = failedSubmission("VALIDATE_RETRY_EXHAUSTED", 1);

        Map<String, Object> res = controller.retry(submissionId,
            Map.of("step", "VALIDATE"), null);

        assertEquals("VALIDATING", res.get("status"));
        assertEquals("VALIDATING", jdbc.queryForObject(
            "select status from submission where submission_id=?", String.class, submissionId));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from processing_job where submission_id=? and step='VALIDATE' and status='QUEUED'",
            Integer.class, submissionId));
        verify(rabbitTemplate).convertAndSend(eq("submission.processing"), eq("validate"),
            any(vn.edu.examgrading.submission.messaging.ValidationWorker.ValidateJobMessage.class));
    }

    @Test
    void retry_reviewRequired_reprocess_t18_overwriteAndTemplateSwap() {
        UUID submissionId = statusedSubmission("REVIEW_REQUIRED", null, true);
        UUID reviewId = UUID.randomUUID();
        UUID newTemplate = seedTemplate();
        when(resultClient.getReviewState(reviewId)).thenReturn(Map.of(
            "reviewId", reviewId, "submissionId", submissionId.toString(),
            "status", "ACTION_AUTHORIZED", "authorizedAction", "REPROCESS",
            "technicalCode", "MASK_INSUFFICIENT", "reasonType", "ANONYMIZATION"));
        when(gradingClient.attemptSummary(submissionId))
            .thenReturn(Map.of("confirmedCount", 0));
        when(identityClient.createPseudonym(eq(examId), eq(submissionId), anyString()))
            .thenReturn(null);

        Map<String, Object> res = controller.retry(submissionId, Map.of(
            "step", "ANONYMIZE", "reviewId", reviewId.toString(),
            "templateId", newTemplate.toString()), null);

        assertEquals("ANONYMIZING", res.get("status"));
        assertEquals("ANONYMIZING", jdbc.queryForObject(
            "select status from submission where submission_id=?", String.class, submissionId));
        assertEquals(newTemplate, jdbc.queryForObject(
            "select template_id from submission where submission_id=?", UUID.class, submissionId));
        Map<String, Object> job = jdbc.queryForMap(
            "select step,overwrite,template_id from processing_job where submission_id=?", submissionId);
        assertEquals("ANONYMIZE", job.get("step"));
        assertEquals(true, job.get("overwrite"));
        assertEquals(newTemplate, job.get("template_id"));
        verify(resultClient).resolveReviewAction(reviewId, "REPROCESS");
        verify(rabbitTemplate).convertAndSend(eq("submission.processing"), eq("anonymize"),
            any(vn.edu.examgrading.submission.messaging.AnonymizeJobMessage.class));
    }
}
