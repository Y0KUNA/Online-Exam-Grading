package vn.edu.examgrading.submission.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.edu.examgrading.submission.repository.SubmissionEventRepository;

/**
 * SSE stream of submission status events for one exam (spec 05:
 * {@code GET /api/v1/exams/{examId}/submissions/events}). EXAM_OFFICE only —
 * enforced at the Gateway. Polls {@code submission_event} (the append-only log
 * written before every status transition, §4.3) so reconnects replay from
 * {@code Last-Event-ID}. Heartbeat comment every 15s per spec.
 */
@RestController
@RequestMapping("/api/v1/exams/{examId}/submissions")
public class SubmissionEventsController {

    private static final Logger log = LoggerFactory.getLogger(SubmissionEventsController.class);
    private static final long POLL_INTERVAL_SECONDS = 1;
    private static final long HEARTBEAT_SECONDS = 15;
    private static final int EVENT_BATCH = 200;

    private final SubmissionEventRepository eventRepo;
    private final ObjectMapper objectMapper;
    private final long emitterTimeoutMs;
    private final ScheduledExecutorService scheduler;

    public SubmissionEventsController(
            SubmissionEventRepository eventRepo,
            ObjectMapper objectMapper,
            @Value("${app.sse.timeout-minutes:30}") long timeoutMinutes) {
        this.eventRepo = eventRepo;
        this.objectMapper = objectMapper;
        this.emitterTimeoutMs = Duration.ofMinutes(timeoutMinutes).toMillis();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "submission-sse-pump");
            t.setDaemon(true);
            return t;
        });
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID examId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        long lastId = parseLastEventId(lastEventId);
        SseEmitter emitter = new SseEmitter(emitterTimeoutMs);
        AtomicLong cursor = new AtomicLong(lastId);
        AtomicBoolean closed = new AtomicBoolean(false);

        ScheduledFuture<?> poll = scheduler.scheduleWithFixedDelay(
            () -> pump(examId, cursor, emitter, closed),
            0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        ScheduledFuture<?> heartbeat = scheduler.scheduleWithFixedDelay(
            () -> beat(emitter, closed),
            HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                poll.cancel(false);
                heartbeat.cancel(false);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        return emitter;
    }

    private long parseLastEventId(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_LAST_EVENT_ID");
        }
    }

    private void pump(UUID examId, AtomicLong cursor, SseEmitter emitter, AtomicBoolean closed) {
        if (closed.get()) {
            return;
        }
        try {
            List<SubmissionEventRepository.EventRow> rows =
                eventRepo.findByExamAfter(examId, cursor.get(), EVENT_BATCH);
            for (SubmissionEventRepository.EventRow row : rows) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("submissionId", row.submissionId());
                data.put("status", row.status().name());
                data.put("progress", row.progress());
                data.put("updatedAt", row.updatedAt());
                emitter.send(SseEmitter.event()
                    .id(String.valueOf(row.eventId()))
                    .name("submission-status")
                    .data(objectMapper.writeValueAsString(data)));
                cursor.set(row.eventId());
            }
        } catch (IOException | RuntimeException e) {
            log.debug("SSE client gone, closing stream: examId={}", examId);
            closed.set(true);
            try {
                emitter.complete();
            } catch (RuntimeException ignored) { }
        }
    }

    private void beat(SseEmitter emitter, AtomicBoolean closed) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment("heartbeat"));
        } catch (IOException | RuntimeException e) {
            closed.set(true);
            try {
                emitter.complete();
            } catch (RuntimeException ignored) { }
        }
    }
}
