package vn.edu.examgrading.submission.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.edu.examgrading.submission.domain.SubmissionStatus;
import vn.edu.examgrading.submission.repository.SubmissionEventRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for {@link SubmissionEventsController} (TICKET 06, task 6).
 * Verifies Last-Event-ID parsing and that the poll pump replays exam events
 * after the cursor (full SSE wire format is covered by the repository query
 * and Spring's SseEmitter).
 */
@ExtendWith(MockitoExtension.class)
class SubmissionEventsControllerTest {

    @Mock
    private SubmissionEventRepository eventRepo;

    private SubmissionEventsController controller() {
        // Mirror the Spring-configured mapper (JavaTimeModule for Instant).
        return new SubmissionEventsController(eventRepo,
            com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules().build(), 30);
    }

    @Test
    void events_withInvalidLastEventId_throws400() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller().events(UUID.randomUUID(), "not-a-number"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void events_pumpPollsRepoAfterCursorAndAdvances() throws Exception {
        UUID examId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        SubmissionEventsController ctl = controller();

        when(eventRepo.findByExamAfter(eq(examId), eq(41L), eq(200)))
            .thenReturn(List.of(new SubmissionEventRepository.EventRow(
                42L, submissionId, SubmissionStatus.UPLOADED, 100, Instant.now())));

        SseEmitter emitter = ctl.events(examId, "41");
        assertNotNull(emitter);

        // Poll interval is 1s; wait for at least two pump cycles.
        long deadline = System.currentTimeMillis() + 3500;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            try {
                verify(eventRepo, timeout(3000).atLeastOnce())
                    .findByExamAfter(examId, 41L, 200);
                break;
            } catch (AssertionError ignored) { }
        }
        verify(eventRepo, atLeastOnce()).findByExamAfter(examId, 41L, 200);
        // Cursor must advance past the emitted event so the next poll uses 42.
        verify(eventRepo, timeout(3000).atLeastOnce())
            .findByExamAfter(examId, 42L, 200);
        emitter.complete();
    }

    @Test
    void events_withoutLastEventId_startsFromZero() throws Exception {
        UUID examId = UUID.randomUUID();
        SubmissionEventsController ctl = controller();

        SseEmitter emitter = ctl.events(examId, null);
        verify(eventRepo, timeout(3000).atLeastOnce())
            .findByExamAfter(examId, 0L, 200);
        emitter.complete();
    }
}
