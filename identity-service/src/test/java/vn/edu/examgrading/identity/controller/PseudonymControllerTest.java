package vn.edu.examgrading.identity.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentMatchers;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.examgrading.common.security.HmacSigner;
import vn.edu.examgrading.identity.repository.SubmissionBindingRepository;

import java.sql.ResultSet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for {@link PseudonymController} lookup endpoint (TICKET 04, task 1).
 * Verifies batch pseudonym lookup contract: {submissionIds} → {items:[{submissionId,pseudonym}]}
 * and HMAC service-signature enforcement.
 */
@ExtendWith(MockitoExtension.class)
class PseudonymControllerTest {

    private static final String SECRET = "test-hmac-secret";
    private static final String CALLER = "exam-grading-service";

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private SubmissionBindingRepository bindingRepository;
    @Mock private ResultSet resultSet;

    private PseudonymController controller;

    @BeforeEach
    void setUp() {
        controller = new PseudonymController(jdbcTemplate, bindingRepository, SECRET);
    }

    private static String signature(String caller, UUID requestId, Instant timestamp) {
        return HmacSigner.sign(SECRET, caller + "\n" + requestId + "\n" + timestamp.toEpochMilli());
    }

    @Test
    void lookup_withValidSignature_returnsItems() throws Exception {
        UUID submissionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant timestamp = Instant.now();

        when(resultSet.getObject("submission_id", UUID.class)).thenReturn(submissionId);
        when(resultSet.getString("value")).thenReturn("KN5X5XUH");

        when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<PseudonymController.PseudonymLookupItem>>any(), any(Object[].class)))
            .thenAnswer(inv -> {
                RowMapper<PseudonymController.PseudonymLookupItem> mapper = inv.getArgument(1);
                return List.of(mapper.mapRow(resultSet, 0));
            });

        ResponseEntity<PseudonymController.PseudonymLookupResponse> response = controller.lookupPseudonyms(
            new PseudonymController.PseudonymLookupRequest(List.of(submissionId)),
            CALLER, requestId, timestamp, signature(CALLER, requestId, timestamp));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().items().size());
        assertEquals(submissionId, response.getBody().items().get(0).submissionId());
        assertEquals("KN5X5XUH", response.getBody().items().get(0).pseudonym());
    }

    @Test
    void lookup_withEmptyList_returnsEmptyWithoutDbCall() {
        UUID requestId = UUID.randomUUID();
        Instant timestamp = Instant.now();

        ResponseEntity<PseudonymController.PseudonymLookupResponse> response = controller.lookupPseudonyms(
            new PseudonymController.PseudonymLookupRequest(List.of()),
            CALLER, requestId, timestamp, signature(CALLER, requestId, timestamp));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().items().isEmpty());
        verify(jdbcTemplate, never()).query(anyString(), ArgumentMatchers.<RowMapper<PseudonymController.PseudonymLookupItem>>any(), any(Object[].class));
    }

    @Test
    void lookup_withBadSignature_throws403() {
        UUID requestId = UUID.randomUUID();
        Instant timestamp = Instant.now();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.lookupPseudonyms(
                new PseudonymController.PseudonymLookupRequest(List.of(UUID.randomUUID())),
                CALLER, requestId, timestamp, "invalid-signature"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals("INVALID_SERVICE_SIGNATURE", ex.getReason());
        verify(jdbcTemplate, never()).query(anyString(), ArgumentMatchers.<RowMapper<PseudonymController.PseudonymLookupItem>>any(), any(Object[].class));
    }

    @Test
    void purge_withResultReviewCaller_deletesPseudonymAndBinding() {
        UUID submissionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant timestamp = Instant.now();
        String caller = "result-review-service";

        when(jdbcTemplate.update(contains("pseudonym"), eq(submissionId))).thenReturn(1);
        when(jdbcTemplate.update(contains("submission_binding"), eq(submissionId))).thenReturn(1);

        ResponseEntity<PseudonymController.PurgePseudonymResponse> response = controller.purgePseudonym(
            submissionId, caller, requestId, timestamp, signature(caller, requestId, timestamp));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().deletedPseudonyms());
        assertEquals("DONE", response.getBody().status());
        verify(jdbcTemplate).update(contains("DELETE FROM pseudonym"), eq(submissionId));
        verify(jdbcTemplate).update(contains("DELETE FROM submission_binding"), eq(submissionId));
    }

    @Test
    void purge_replayAfterDeletion_stillReturnsDone() {
        UUID submissionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant timestamp = Instant.now();
        String caller = "result-review-service";

        when(jdbcTemplate.update(contains("pseudonym"), eq(submissionId))).thenReturn(0);
        when(jdbcTemplate.update(contains("submission_binding"), eq(submissionId))).thenReturn(0);

        ResponseEntity<PseudonymController.PurgePseudonymResponse> response = controller.purgePseudonym(
            submissionId, caller, requestId, timestamp, signature(caller, requestId, timestamp));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().deletedPseudonyms());
        assertEquals("DONE", response.getBody().status());
    }

    @Test
    void purge_withNonOrchestratorCaller_throws403() {
        UUID submissionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant timestamp = Instant.now();
        String caller = "submission-service";

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.purgePseudonym(submissionId, caller, requestId, timestamp,
                signature(caller, requestId, timestamp)));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(jdbcTemplate, never()).update(anyString(), any(Object.class));
    }

    @Test
    void submissionIdentity_withValidSignature_returnsCandidateAndPseudonym() throws Exception {
        UUID submissionId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant timestamp = Instant.now();
        String caller = "submission-service";

        when(resultSet.getObject("candidate_id", UUID.class)).thenReturn(candidateId);
        when(resultSet.getString("candidate_number")).thenReturn("SBD001");
        when(resultSet.getString("full_name")).thenReturn("Nguyen Van A");
        when(resultSet.getString("class_name")).thenReturn("6A1");
        when(resultSet.getString("pseudonym")).thenReturn("KN5X5XUH");

        when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<PseudonymController.SubmissionIdentityResponse>>any(), eq(submissionId)))
            .thenAnswer(inv -> {
                RowMapper<PseudonymController.SubmissionIdentityResponse> mapper = inv.getArgument(1);
                return List.of(mapper.mapRow(resultSet, 0));
            });

        ResponseEntity<PseudonymController.SubmissionIdentityResponse> response = controller.submissionIdentity(
            submissionId, caller, requestId, timestamp, signature(caller, requestId, timestamp));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(candidateId, response.getBody().candidateId());
        assertEquals("SBD001", response.getBody().candidateNumber());
        assertEquals("Nguyen Van A", response.getBody().fullName());
        assertEquals("6A1", response.getBody().className());
        assertEquals("KN5X5XUH", response.getBody().pseudonym());
    }

    @Test
    void submissionIdentity_withoutBinding_throws404() {
        UUID submissionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant timestamp = Instant.now();
        String caller = "submission-service";

        when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<PseudonymController.SubmissionIdentityResponse>>any(), eq(submissionId)))
            .thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.submissionIdentity(submissionId, caller, requestId, timestamp,
                signature(caller, requestId, timestamp)));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("BINDING_NOT_FOUND", ex.getReason());
    }

    @Test
    void submissionIdentity_withBadSignature_throws403() {
        UUID submissionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant timestamp = Instant.now();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.submissionIdentity(submissionId, CALLER, requestId, timestamp, "invalid-signature"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(jdbcTemplate, never()).query(anyString(), ArgumentMatchers.<RowMapper<PseudonymController.SubmissionIdentityResponse>>any(), any(Object.class));
    }

    private void stubPseudonymRow(UUID submissionId, UUID pseudonymId, UUID examId, String value) {
        when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<Object[]>>any(), eq(submissionId)))
            .thenReturn(List.<Object[]>of(new Object[]{pseudonymId, examId, value}));
    }

    private static PseudonymController.UpdatePseudonymRequest updateRequest(String value, String reason) {
        return new PseudonymController.UpdatePseudonymRequest(value, reason, "user-123");
    }

    @Test
    void updatePseudonym_withValidRequest_updatesValueAndWritesHistory() {
        UUID submissionId = UUID.randomUUID();
        UUID pseudonymId = UUID.randomUUID();
        UUID examId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant timestamp = Instant.now();
        String caller = "submission-service";
        stubPseudonymRow(submissionId, pseudonymId, examId, "OLD12345");

        ResponseEntity<PseudonymController.UpdatePseudonymResponse> response = controller.updatePseudonym(
            submissionId, updateRequest("NEW67890", "wrong phách"),
            caller, requestId, timestamp, signature(caller, requestId, timestamp));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("NEW67890", response.getBody().pseudonym());
        assertEquals(examId, response.getBody().examId());
        verify(jdbcTemplate).update(contains("UPDATE pseudonym SET value"), eq("NEW67890"), eq(pseudonymId));
        verify(jdbcTemplate).update(contains("INSERT INTO pseudonym_history"),
            eq(pseudonymId), eq("OLD12345"), eq("NEW67890"), eq("user-123"), eq("wrong phách"));
    }

    @Test
    void updatePseudonym_sameValue_isIdempotentNoHistory() {
        UUID submissionId = UUID.randomUUID();
        UUID pseudonymId = UUID.randomUUID();
        UUID examId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant timestamp = Instant.now();
        String caller = "submission-service";
        stubPseudonymRow(submissionId, pseudonymId, examId, "SAME1234");

        ResponseEntity<PseudonymController.UpdatePseudonymResponse> response = controller.updatePseudonym(
            submissionId, updateRequest("SAME1234", "no-op"),
            caller, requestId, timestamp, signature(caller, requestId, timestamp));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("SAME1234", response.getBody().pseudonym());
        verify(jdbcTemplate, never()).update(contains("UPDATE pseudonym SET value"), any(), any());
        verify(jdbcTemplate, never()).update(contains("INSERT INTO pseudonym_history"), any(), any(), any(), any(), any());
    }

    @Test
    void updatePseudonym_withoutPseudonym_throws404() {
        UUID submissionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant timestamp = Instant.now();
        String caller = "submission-service";

        when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<Object[]>>any(), eq(submissionId)))
            .thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.updatePseudonym(submissionId, updateRequest("NEW12345", "reason"),
                caller, requestId, timestamp, signature(caller, requestId, timestamp)));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("PSEUDONYM_NOT_FOUND", ex.getReason());
    }

    @Test
    void updatePseudonym_missingReason_throws422() {
        UUID submissionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant timestamp = Instant.now();
        String caller = "submission-service";

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.updatePseudonym(submissionId, updateRequest("NEW12345", "  "),
                caller, requestId, timestamp, signature(caller, requestId, timestamp)));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertEquals("REASON_REQUIRED", ex.getReason());
    }

    @Test
    void updatePseudonym_valueClash_throws409() {
        UUID submissionId = UUID.randomUUID();
        UUID pseudonymId = UUID.randomUUID();
        UUID examId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant timestamp = Instant.now();
        String caller = "submission-service";
        stubPseudonymRow(submissionId, pseudonymId, examId, "OLD12345");
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("dup"))
            .when(jdbcTemplate).update(contains("UPDATE pseudonym SET value"), eq("TAKEN999"), eq(pseudonymId));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.updatePseudonym(submissionId, updateRequest("TAKEN999", "reason"),
                caller, requestId, timestamp, signature(caller, requestId, timestamp)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("PSEUDONYM_TAKEN", ex.getReason());
    }

    @Test
    void lookup_withUnknownCaller_throws403() {
        UUID requestId = UUID.randomUUID();
        Instant timestamp = Instant.now();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.lookupPseudonyms(
                new PseudonymController.PseudonymLookupRequest(List.of(UUID.randomUUID())),
                "unknown-service", requestId, timestamp,
                signature("unknown-service", requestId, timestamp)));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }
}
