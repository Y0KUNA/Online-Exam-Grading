package vn.edu.examgrading.identity.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.examgrading.common.security.HmacSigner;
import vn.edu.examgrading.identity.domain.UserAccount;
import vn.edu.examgrading.identity.domain.UserRole;
import vn.edu.examgrading.identity.repository.UserAccountRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for {@link UserLookupController} (TICKET 04, task 2).
 * Verifies batch user lookup contract and the narrower caller allowlist
 * (Exam & Grading / Result only — submission-service must be rejected).
 */
@ExtendWith(MockitoExtension.class)
class UserLookupControllerTest {

    private static final String SECRET = "test-hmac-secret";
    private static final String CALLER = "exam-grading-service";

    @Mock private UserAccountRepository userRepository;

    private UserLookupController controller;

    @BeforeEach
    void setUp() {
        controller = new UserLookupController(userRepository, SECRET);
    }

    private static String signature(String caller, UUID requestId, Instant timestamp) {
        return HmacSigner.sign(SECRET, caller + "\n" + requestId + "\n" + timestamp.toEpochMilli());
    }

    private ResponseEntity<UserLookupController.UserLookupResponse> call(
            List<UUID> userIds, String caller, UUID requestId, Instant timestamp, String signature) {
        return controller.lookupUsers(
            new UserLookupController.UserLookupRequest(userIds),
            caller, requestId, timestamp, signature);
    }

    @Test
    void lookup_withValidSignature_returnsItemsWithoutPasswordHash() {
        UserAccount user = new UserAccount("grader01", "hashed-secret", "Nguyen Van A", "a@x.edu", UserRole.GRADER, false);
        UUID requestId = UUID.randomUUID();
        Instant timestamp = Instant.now();
        when(userRepository.findAllById(List.of(user.id()))).thenReturn(List.of(user));

        ResponseEntity<UserLookupController.UserLookupResponse> response = call(
            List.of(user.id()), CALLER, requestId, timestamp, signature(CALLER, requestId, timestamp));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().items().size());
        var item = response.getBody().items().get(0);
        assertEquals(user.id(), item.userId());
        assertEquals("grader01", item.username());
        assertEquals("Nguyen Van A", item.fullName());
        assertEquals(UserRole.GRADER, item.role());
        assertTrue(item.isActive());
    }

    @Test
    void lookup_withEmptyList_returnsEmptyWithoutDbCall() {
        UUID requestId = UUID.randomUUID();
        Instant timestamp = Instant.now();

        ResponseEntity<UserLookupController.UserLookupResponse> response = call(
            List.of(), CALLER, requestId, timestamp, signature(CALLER, requestId, timestamp));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().items().isEmpty());
        verify(userRepository, never()).findAllById(any());
    }

    @Test
    void lookup_withSubmissionServiceCaller_throws403() {
        UUID requestId = UUID.randomUUID();
        Instant timestamp = Instant.now();
        String caller = "submission-service";

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> call(List.of(UUID.randomUUID()), caller, requestId, timestamp,
                signature(caller, requestId, timestamp)));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals("INVALID_SERVICE_SIGNATURE", ex.getReason());
        verify(userRepository, never()).findAllById(any());
    }

    @Test
    void lookup_withBadSignature_throws403() {
        UUID requestId = UUID.randomUUID();
        Instant timestamp = Instant.now();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> call(List.of(UUID.randomUUID()), CALLER, requestId, timestamp, "invalid-signature"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals("INVALID_SERVICE_SIGNATURE", ex.getReason());
    }
}
