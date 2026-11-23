package vn.edu.examgrading.examgrading.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentMatchers;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.examgrading.common.security.HmacSigner;
import vn.edu.examgrading.examgrading.service.AssignmentService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link InternalGradingController} (TICKET 05 tasks 5-6):
 * attempt-summary and discard-open-work, incl. HMAC enforcement,
 * ATTEMPTS_EXIST and Idempotency-Key replay.
 */
@ExtendWith(MockitoExtension.class)
class InternalGradingControllerTest {

    private static final String SECRET = "test-hmac-secret";
    private static final String CALLER = "submission-service";

    @Mock private JdbcTemplate jdbc;
    @Mock private AssignmentService assignmentService;

    private InternalGradingController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalGradingController(jdbc, SECRET, assignmentService);
    }

    private static String sign(String caller, UUID requestId, Instant timestamp) {
        return HmacSigner.sign(SECRET, caller + "\n" + requestId + "\n" + timestamp.toEpochMilli());
    }

    private static UUID rid() { return UUID.randomUUID(); }
    private static Instant ts() { return Instant.now(); }

    // ---------- GET attempt-summary ----------

    @Test
    void attemptSummary_returnsCounts() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        when(jdbc.queryForMap(anyString(), eq(id), eq(id), eq(id)))
            .thenReturn(Map.of("confirmedCount", 2L, "draftCount", 1L, "openAssignmentCount", 3L));

        Map<String, Object> res = controller.attemptSummary(id, CALLER, requestId, timestamp,
            sign(CALLER, requestId, timestamp));
        assertEquals(2L, res.get("confirmedCount"));
        assertEquals(1L, res.get("draftCount"));
        assertEquals(3L, res.get("openAssignmentCount"));
    }

    @Test
    void attemptSummary_badSignature_throws403() {
        UUID id = UUID.randomUUID();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.attemptSummary(id, CALLER, rid(), ts(), "bad"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void attemptSummary_wrongCaller_throws403() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.attemptSummary(id, "result-review-service", requestId, timestamp,
                sign("result-review-service", requestId, timestamp)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    // ---------- POST discard-open-work ----------

    @Test
    void discardOpenWork_success_returnsCountsAndStoresIdempotency() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Map<String, Object>>>any(), any(Object[].class)))
            .thenReturn(List.of());
        when(assignmentService.discardOpenWork(id))
            .thenReturn(Map.of("discardedAttempts", 2, "cancelledAssignments", 3));

        Map<String, Object> res = controller.discardOpenWork(id, "repl-123", CALLER, requestId, timestamp,
            sign(CALLER, requestId, timestamp));
        assertEquals(2, res.get("discardedAttempts"));
        assertEquals(3, res.get("cancelledAssignments"));
        verify(jdbc).update(contains("idempotency_record"), any(), any(), any(), any());
    }

    @Test
    void discardOpenWork_attemptsExist_throws409() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Map<String, Object>>>any(), any(Object[].class)))
            .thenReturn(List.of());
        when(assignmentService.discardOpenWork(id)).thenThrow(new IllegalStateException("ATTEMPTS_EXIST"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.discardOpenWork(id, "repl-123", CALLER, requestId, timestamp,
                sign(CALLER, requestId, timestamp)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("ATTEMPTS_EXIST", ex.getReason());
    }

    @Test
    void discardOpenWork_replay_returnsStoredResponse() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        Map<String, Object> stored = new java.util.HashMap<>();
        stored.put("discardedAttempts", 5);
        stored.put("cancelledAssignments", 4);
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Map<String, Object>>>any(), any(Object[].class)))
            .thenReturn(List.of(stored));

        Map<String, Object> res = controller.discardOpenWork(id, "repl-123", CALLER, requestId, timestamp,
            sign(CALLER, requestId, timestamp));
        assertEquals(5, res.get("discardedAttempts"));
        assertEquals(4, res.get("cancelledAssignments"));
        verifyNoInteractions(assignmentService);
    }

    @Test
    void discardOpenWork_noKey_stillExecutes() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        when(assignmentService.discardOpenWork(id))
            .thenReturn(Map.of("discardedAttempts", 0, "cancelledAssignments", 0));

        Map<String, Object> res = controller.discardOpenWork(id, null, CALLER, requestId, timestamp,
            sign(CALLER, requestId, timestamp));
        assertEquals(0, res.get("discardedAttempts"));
        verify(jdbc, never()).update(contains("idempotency_record"), any(), any(), any(), any());
    }

    // ---------- DELETE /submissions/{id}/detail (retention purge §9.3) ----------

    @Test
    void purgeDetail_deletesQuestionScoresAttemptsAssignments_returnsDone() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        String caller = "result-review-service";

        Map<String, Object> res = controller.purgeDetail(id, caller, requestId, timestamp,
            sign(caller, requestId, timestamp));

        assertEquals("DONE", res.get("status"));
        verify(jdbc).update(contains("delete from question_score"), eq(id));
        verify(jdbc).update(contains("delete from grading_attempt"), eq(id));
        verify(jdbc).update("delete from grading_assignment where submission_id = ?", id);
        verifyNoInteractions(assignmentService);
    }

    @Test
    void purgeDetail_submissionServiceCaller_throws403() {
        UUID id = UUID.randomUUID();
        UUID requestId = rid(); Instant timestamp = ts();
        // CALLER (submission-service) is valid for other endpoints but NOT purge.
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.purgeDetail(id, CALLER, requestId, timestamp,
                sign(CALLER, requestId, timestamp)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(jdbc, never()).update(contains("delete from"), any(Object[].class));
    }
}
