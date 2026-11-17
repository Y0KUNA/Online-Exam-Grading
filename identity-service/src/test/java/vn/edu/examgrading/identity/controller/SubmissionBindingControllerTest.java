package vn.edu.examgrading.identity.controller;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.examgrading.identity.dto.SubmissionBindingReleaseRequest;
import vn.edu.examgrading.identity.repository.SubmissionBindingRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for {@link SubmissionBindingController} (TICKET 01).
 * Since controller has private methods calling JdbcTemplate directly,
 * this test focuses on the releaseBinding endpoint which is simpler.
 */
@ExtendWith(MockitoExtension.class)
class SubmissionBindingControllerTest {

    @Mock
    private SubmissionBindingRepository bindingRepository;

    private SubmissionBindingController controller;

    private final UUID submissionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new SubmissionBindingController(bindingRepository);
    }

    @Test
    void releaseBinding_withValidReason_returns204() {
        SubmissionBindingReleaseRequest request = new SubmissionBindingReleaseRequest("UPLOAD_ABANDONED");

        ResponseEntity<Void> response = controller.releaseBinding(submissionId, request);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(bindingRepository).releaseBinding(submissionId, "UPLOAD_ABANDONED");
    }

    @Test
    void releaseBinding_withBlankReason_throws422() {
        SubmissionBindingReleaseRequest request = new SubmissionBindingReleaseRequest("");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.releaseBinding(submissionId, request));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertEquals("INVALID_REASON", ex.getReason());
    }

    @Test
    void releaseBinding_withNullReason_throws422() {
        SubmissionBindingReleaseRequest request = new SubmissionBindingReleaseRequest(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.releaseBinding(submissionId, request));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertEquals("INVALID_REASON", ex.getReason());
    }

    @Test
    void releaseBinding_isIdempotent() {
        SubmissionBindingReleaseRequest request = new SubmissionBindingReleaseRequest("FILE_TOO_LARGE");

        assertDoesNotThrow(() -> controller.releaseBinding(submissionId, request));
        assertDoesNotThrow(() -> controller.releaseBinding(submissionId, request));

        verify(bindingRepository, times(2)).releaseBinding(submissionId, "FILE_TOO_LARGE");
    }
}
