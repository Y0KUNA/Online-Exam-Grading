package vn.edu.examgrading.submission.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.examgrading.common.security.HmacSigner;
import vn.edu.examgrading.submission.domain.SubmissionStatus;
import vn.edu.examgrading.submission.repository.ExamFileRepository;
import vn.edu.examgrading.submission.repository.SubmissionEventRepository;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway;
import vn.edu.examgrading.submission.storage.config.StorageProperties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link InternalSubmissionController} internal endpoints
 * (TICKET 05 tasks 1-4): GET detail, files access, finalize, status transition.
 * Covers HMAC enforcement, 404/403/409 mapping and idempotent replay.
 */
@ExtendWith(MockitoExtension.class)
class InternalSubmissionControllerTest {

    private static final String SECRET = "test-hmac-secret";
    private static final String CALLER = "result-review-service";

    @Mock private JdbcTemplate jdbc;
    @Mock private ExamFileRepository examFileRepo;
    @Mock private ObjectStorageGateway storage;
    @Mock private SubmissionEventRepository eventRepo;

    private InternalSubmissionController controller;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties("memory", "http://minio:9000", "us-east-1",
            "key", "secret", "original", "anonymized-bucket", "http://gateway:8080/storage",
            52428800L, 1024L);
        controller = new InternalSubmissionController(jdbc, SECRET, examFileRepo, storage, props, eventRepo);
    }

    private static String sign(String caller, UUID requestId, Instant timestamp) {
        return HmacSigner.sign(SECRET, caller + "\n" + requestId + "\n" + timestamp.toEpochMilli());
    }

    private static UUID rid() { return UUID.randomUUID(); }
    private static Instant ts() { return Instant.now(); }

    // ---------- GET /{submissionId} ----------

    @Test
    void get_returnsSnapshot() {
        UUID id = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("submissionId", id);
        row.put("examId", UUID.randomUUID());
        row.put("status", "READY_FOR_GRADING");
        row.put("version", 7);
        when(jdbc.queryForMap(anyString(), eq(id))).thenReturn(row);

        UUID requestId = rid(); Instant timestamp = ts();
        Map<String, Object> res = controller.get(id, CALLER, requestId, timestamp,
            sign(CALLER, requestId, timestamp));
        assertEquals("READY_FOR_GRADING", res.get("status"));
    }

    @Test
    void get_badSignature_throws403() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.get(id, CALLER, requestId, timestamp, "bad-sig"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void get_disallowedCaller_throws403() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.get(id, "submission-worker", requestId, timestamp,
                sign("submission-worker", requestId, timestamp)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void get_missing_throws404() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        when(jdbc.queryForMap(anyString(), eq(id))).thenThrow(new EmptyResultDataAccessException(1));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.get(id, CALLER, requestId, timestamp, sign(CALLER, requestId, timestamp)));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ---------- POST /{id}/files/{type}/access ----------

    @Test
    void fileAccess_anonymized_returnsTicket() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(id))).thenReturn("READY_FOR_GRADING");
        when(examFileRepo.findCurrentByType(id, "ANONYMIZED"))
            .thenReturn(Optional.of(new ExamFileRepository.OriginalFile(UUID.randomUUID(), 1, 100L, "k.pdf")));
        when(storage.presignGet(eq("anonymized-bucket"), eq("k.pdf"), any(Duration.class)))
            .thenReturn(new ObjectStorageGateway.AccessTicket("http://gw/storage/anonymized/k.pdf?sig=1", Instant.now().plusSeconds(300)));

        Map<String, Object> res = controller.fileAccess(id, "ANONYMIZED", CALLER, requestId, timestamp,
            sign(CALLER, requestId, timestamp));
        assertTrue(res.get("url").toString().contains("sig=1"));
        assertNotNull(res.get("expiresAt"));
    }

    @Test
    void fileAccess_original_throws403() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.fileAccess(id, "ORIGINAL", CALLER, requestId, timestamp,
                sign(CALLER, requestId, timestamp)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals("ORIGINAL_FORBIDDEN", ex.getReason());
    }

    @Test
    void fileAccess_reprocessing_throws409FileNotReady() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(id))).thenReturn("ANONYMIZING");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.fileAccess(id, "ANONYMIZED", CALLER, requestId, timestamp,
                sign(CALLER, requestId, timestamp)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("FILE_NOT_READY", ex.getReason());
    }

    @Test
    void fileAccess_noFile_throws404() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(id))).thenReturn("READY_FOR_GRADING");
        when(examFileRepo.findCurrentByType(id, "ANONYMIZED")).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.fileAccess(id, "ANONYMIZED", CALLER, requestId, timestamp,
                sign(CALLER, requestId, timestamp)));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ---------- POST /{id}/finalize ----------

    @Test
    void finalize_fromCompleted_setsFinalized() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        Map<String, Object> current = new HashMap<>();
        current.put("status", "COMPLETED"); current.put("version", 5);
        when(jdbc.queryForMap(anyString(), eq(id))).thenReturn(current);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        Map<String, Object> done = new HashMap<>();
        done.put("status", "FINALIZED"); done.put("version", 6);
        when(jdbc.queryForMap(contains("'FINALIZED'"), eq(id))).thenReturn(done);

        Map<String, Object> res = controller.finalize(id, Map.of("finalizedAt", "2026-11-23T00:00:00Z"),
            CALLER, requestId, timestamp, sign("result-review-service", requestId, timestamp));
        assertEquals("FINALIZED", res.get("status"));
        assertEquals(6, res.get("version"));
        verify(eventRepo).append(eq(id), eq(SubmissionStatus.FINALIZED), eq(100));
    }

    @Test
    void finalize_alreadyFinalized_idempotent200() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        Map<String, Object> current = new HashMap<>();
        current.put("status", "FINALIZED"); current.put("version", 9);
        when(jdbc.queryForMap(anyString(), eq(id))).thenReturn(current);

        Map<String, Object> res = controller.finalize(id, Map.of(), CALLER, requestId, timestamp,
            sign(CALLER, requestId, timestamp));
        assertEquals("FINALIZED", res.get("status"));
        assertEquals(9, res.get("version"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void finalize_wrongStatus_throws409() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        Map<String, Object> current = new HashMap<>();
        current.put("status", "GRADING"); current.put("version", 2);
        when(jdbc.queryForMap(anyString(), eq(id))).thenReturn(current);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.finalize(id, Map.of(), CALLER, requestId, timestamp,
                sign(CALLER, requestId, timestamp)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("ILLEGAL_TRANSITION", ex.getReason());
    }

    // ---------- POST /{id}/status ----------

    private Map<String, Object> statusRow(String status, int version) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", status); m.put("version", version);
        return m;
    }

    @Test
    void status_happyPath_transitionsAndAppendsEvent() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        when(jdbc.queryForMap(contains("status,version"), eq(id))).thenReturn(statusRow("UPLOADED", 3));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForMap(contains("\"submissionId\""), eq(id)))
            .thenReturn(Map.of("submissionId", id, "status", "VALIDATING", "version", 4));

        Map<String, Object> res = controller.change(id,
            Map.of("expectedStatus", "UPLOADED", "newStatus", "VALIDATING", "progress", 40),
            CALLER, requestId, timestamp, sign(CALLER, requestId, timestamp));
        assertEquals("VALIDATING", res.get("status"));
        verify(eventRepo).append(eq(id), eq(SubmissionStatus.VALIDATING), eq(40));
    }

    @Test
    void status_sameDestination_idempotentReplay() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        when(jdbc.queryForMap(contains("status,version"), eq(id))).thenReturn(statusRow("VALIDATING", 4));
        when(jdbc.queryForMap(contains("\"submissionId\""), eq(id)))
            .thenReturn(Map.of("submissionId", id, "status", "VALIDATING", "version", 4));

        Map<String, Object> res = controller.change(id, Map.of("newStatus", "VALIDATING"),
            CALLER, requestId, timestamp, sign(CALLER, requestId, timestamp));
        assertEquals("VALIDATING", res.get("status"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void status_expectedMismatch_throws409() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        when(jdbc.queryForMap(contains("status,version"), eq(id))).thenReturn(statusRow("UPLOADED", 3));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.change(id, Map.of("expectedStatus", "VALIDATING", "newStatus", "ANONYMIZING"),
                CALLER, requestId, timestamp, sign(CALLER, requestId, timestamp)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("STATUS_VERSION_CONFLICT", ex.getReason());
    }

    @Test
    void status_illegalTransition_throws409() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        when(jdbc.queryForMap(contains("status,version"), eq(id))).thenReturn(statusRow("UPLOADING", 1));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.change(id, Map.of("newStatus", "COMPLETED"),
                CALLER, requestId, timestamp, sign(CALLER, requestId, timestamp)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("ILLEGAL_TRANSITION", ex.getReason());
    }

    @Test
    void status_unknownStatus_throws422() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        when(jdbc.queryForMap(contains("status,version"), eq(id))).thenReturn(statusRow("UPLOADED", 3));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.change(id, Map.of("newStatus", "NOPE"),
                CALLER, requestId, timestamp, sign(CALLER, requestId, timestamp)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
    }

    @Test
    void status_lostUpdateRace_throws409() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        when(jdbc.queryForMap(contains("status,version"), eq(id))).thenReturn(statusRow("UPLOADED", 3));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.change(id, Map.of("newStatus", "VALIDATING"),
                CALLER, requestId, timestamp, sign(CALLER, requestId, timestamp)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("STATUS_VERSION_CONFLICT", ex.getReason());
    }

    @Test
    void status_failedWithReasonCode_writesFailureCodeAndAudit() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        when(jdbc.queryForMap(contains("status,version"), eq(id))).thenReturn(statusRow("VALIDATING", 3));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForMap(contains("\"submissionId\""), eq(id)))
            .thenReturn(Map.of("submissionId", id, "status", "FAILED", "version", 4));

        controller.change(id, Map.of("newStatus", "FAILED", "reasonCode", "PDF_CORRUPT", "progress", 0),
            CALLER, requestId, timestamp, sign(CALLER, requestId, timestamp));

        // failure_code arg is the 4th positional param of the conditional update
        org.mockito.ArgumentCaptor<Object[]> captor = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(anyString(), captor.capture());
        boolean found = captor.getAllValues().stream()
            .anyMatch(args -> args.length > 3 && "PDF_CORRUPT".equals(args[3]));
        assertTrue(found);
        verify(jdbc, atLeastOnce()).update(contains("audit_log"), any(Object[].class));
    }

    // ---------- DELETE /{submissionId}/detail (retention purge §9.3) ----------

    @Test
    void purgeDetail_deletesObjectsAndAllRows_returnsDone() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        when(jdbc.query(contains("from exam_file"), any(org.springframework.jdbc.core.RowMapper.class), any()))
            .thenReturn(java.util.List.of(
                new String[]{"ORIGINAL", "orig-key.pdf"},
                new String[]{"ANONYMIZED", "anon-key.pdf"}));
        when(jdbc.query(contains("object_key from submission"), any(org.springframework.jdbc.core.RowMapper.class), any()))
            .thenReturn(java.util.List.of("sub-key.pdf"));

        Map<String, Object> res = controller.purgeDetail(id, CALLER, requestId, timestamp,
            sign(CALLER, requestId, timestamp));

        assertEquals("DONE", res.get("status"));
        // objects: ORIGINAL + submission key → original bucket, ANONYMIZED → anonymized bucket
        verify(storage).delete("original", "orig-key.pdf");
        verify(storage).delete("original", "sub-key.pdf");
        verify(storage).delete("anonymized-bucket", "anon-key.pdf");
        // rows: exam_file, submission_event, processing_job, audit_log, submission
        verify(jdbc).update("delete from exam_file where submission_id=?", id);
        verify(jdbc).update("delete from submission_event where submission_id=?", id);
        verify(jdbc).update("delete from processing_job where submission_id=?", id);
        verify(jdbc).update("delete from audit_log where entity_id=?", id);
        verify(jdbc).update("delete from submission where submission_id=?", id);
    }

    @Test
    void purgeDetail_replayWithNoRows_stillReturnsDone() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        when(jdbc.query(contains("from exam_file"), any(org.springframework.jdbc.core.RowMapper.class), any()))
            .thenReturn(java.util.List.of());
        when(jdbc.query(contains("object_key from submission"), any(org.springframework.jdbc.core.RowMapper.class), any()))
            .thenReturn(java.util.List.of());

        Map<String, Object> res = controller.purgeDetail(id, CALLER, requestId, timestamp,
            sign(CALLER, requestId, timestamp));
        assertEquals("DONE", res.get("status"));
        verify(storage, never()).delete(any(), any());
    }

    @Test
    void purgeDetail_wrongCaller_throws403() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.purgeDetail(id, "submission-worker", requestId, timestamp,
                sign("submission-worker", requestId, timestamp)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }
}
