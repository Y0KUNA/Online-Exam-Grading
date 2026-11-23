package vn.edu.examgrading.examgrading.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.examgrading.common.security.HmacSigner;
import vn.edu.examgrading.examgrading.service.AssignmentService;

/**
 * Signed service-only endpoints under /internal/v1/grading. The Gateway rejects
 * this namespace; callers are other backend services that already authorized
 * the end user.
 */
@RestController
@RequestMapping("/internal/v1/grading")
public class InternalGradingController {

    private static final Set<String> CALLERS = Set.of("submission-service");
    /** §9.3: only result-review-service orchestrates retention purge. */
    private static final Set<String> PURGE_CALLERS = Set.of("result-review-service");
    private static final java.util.UUID SERVICE_ACTOR = new java.util.UUID(0L, 0L);
    private final JdbcTemplate jdbc;
    private final String secret;
    private final AssignmentService assignmentService;

    public InternalGradingController(JdbcTemplate jdbc, @Value("${app.internal.hmac-secret}") String secret,
            AssignmentService assignmentService) {
        this.jdbc = jdbc;
        this.secret = secret;
        this.assignmentService = assignmentService;
    }

    /**
     * Attempt counters for a submission, checked by Submission before allowing a
     * file replacement (§4.4): confirmed attempts block replace-file (ATTEMPTS_EXIST).
     */
    @GetMapping("/submissions/{submissionId}/attempt-summary")
    public Map<String, Object> attemptSummary(@PathVariable UUID submissionId,
        @RequestHeader("X-Service-Caller") String caller, @RequestHeader("X-Service-Request-Id") UUID requestId,
        @RequestHeader("X-Service-Timestamp") Instant timestamp, @RequestHeader("X-Service-Signature") String signature) {
        verify(caller, requestId, timestamp, signature);
        return jdbc.queryForMap("""
            select
              (select count(*) from grading_attempt t
                 join grading_assignment ga on ga.assignment_id = t.assignment_id
                where ga.submission_id = ? and t.status = 'CONFIRMED') as "confirmedCount",
              (select count(*) from grading_attempt t
                 join grading_assignment ga on ga.assignment_id = t.assignment_id
                where ga.submission_id = ? and t.status = 'DRAFT') as "draftCount",
              (select count(*) from grading_assignment
                where submission_id = ? and status in ('ASSIGNED','IN_PROGRESS')) as "openAssignmentCount"
            """, submissionId, submissionId, submissionId);
    }

    /**
     * Replace-file saga step (§4.4): cancels open assignments and discards DRAFT
     * attempts. Idempotent via Idempotency-Key=replacementId; replay returns the
     * stored response. 409 ATTEMPTS_EXIST when any attempt is CONFIRMED.
     */
    @PostMapping("/submissions/{submissionId}/discard-open-work")
    public Map<String, Object> discardOpenWork(@PathVariable UUID submissionId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader("X-Service-Caller") String caller, @RequestHeader("X-Service-Request-Id") UUID requestId,
        @RequestHeader("X-Service-Timestamp") Instant timestamp, @RequestHeader("X-Service-Signature") String signature) {
        verify(caller, requestId, timestamp, signature);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var stored = jdbc.query("""
                select response_body->>'discardedAttempts' as d, response_body->>'cancelledAssignments' as c
                from idempotency_record
                where actor_id=? and operation='discard-open-work' and idempotency_key=?
                """, (rs, n) -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("discardedAttempts", Integer.parseInt(rs.getString("d")));
                    m.put("cancelledAssignments", Integer.parseInt(rs.getString("c")));
                    return m;
                },
                SERVICE_ACTOR, idempotencyKey);
            if (!stored.isEmpty()) {
                return stored.getFirst();
            }
        }
        Map<String, Object> result;
        try {
            result = assignmentService.discardOpenWork(submissionId);
        } catch (IllegalStateException e) {
            if ("ATTEMPTS_EXIST".equals(e.getMessage())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "ATTEMPTS_EXIST");
            }
            throw e;
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String body = "{\"discardedAttempts\":" + result.get("discardedAttempts")
                + ",\"cancelledAssignments\":" + result.get("cancelledAssignments") + "}";
            try {
                jdbc.update("""
                    insert into idempotency_record(id,actor_id,operation,idempotency_key,response_code,response_body)
                    values (?,?,'discard-open-work',?,200,?::jsonb)""",
                    UUID.randomUUID(), SERVICE_ACTOR, idempotencyKey, body);
            } catch (org.springframework.dao.DuplicateKeyException ignored) {
                // Concurrent replay with the same key: the stored response wins.
            }
        }
        return result;
    }

    /**
     * Retention purge command (§9.3/FR-025): deletes the submission's detailed
     * grading data — question_score rows, grading_attempt rows and
     * grading_assignment rows. Exam-level data (exam, questions, rubric) is
     * untouched. Idempotent: replay after deletion returns 200 DONE.
     */
    @DeleteMapping("/submissions/{submissionId}/detail")
    public Map<String, Object> purgeDetail(@PathVariable UUID submissionId,
        @RequestHeader("X-Service-Caller") String caller, @RequestHeader("X-Service-Request-Id") UUID requestId,
        @RequestHeader("X-Service-Timestamp") Instant timestamp, @RequestHeader("X-Service-Signature") String signature) {
        verify(caller, requestId, timestamp, signature, PURGE_CALLERS);
        jdbc.update("""
            delete from question_score where attempt_id in (
                select t.attempt_id from grading_attempt t
                join grading_assignment ga on ga.assignment_id = t.assignment_id
                where ga.submission_id = ?)
            """, submissionId);
        jdbc.update("""
            delete from grading_attempt where assignment_id in (
                select assignment_id from grading_assignment where submission_id = ?)
            """, submissionId);
        jdbc.update("delete from grading_assignment where submission_id = ?", submissionId);
        return Map.of("status", "DONE");
    }

    private void verify(String caller, UUID requestId, Instant timestamp, String signature) {
        verify(caller, requestId, timestamp, signature, CALLERS);
    }

    private void verify(String caller, UUID requestId, Instant timestamp, String signature, Set<String> allowed) {
        if (!allowed.contains(caller) || Duration.between(timestamp, Instant.now()).abs().toSeconds() > 60
                || !HmacSigner.verify(secret, caller + "\n" + requestId + "\n" + timestamp.toEpochMilli(), signature)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "INVALID_SERVICE_SIGNATURE");
        }
    }
}
