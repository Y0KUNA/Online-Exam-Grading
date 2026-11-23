package vn.edu.examgrading.submission.controller;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.examgrading.common.security.HmacSigner;
import vn.edu.examgrading.submission.domain.SubmissionStatus;
import vn.edu.examgrading.submission.domain.SubmissionStatusMachine;
import vn.edu.examgrading.submission.repository.ExamFileRepository;
import vn.edu.examgrading.submission.repository.SubmissionEventRepository;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway;
import vn.edu.examgrading.submission.storage.config.StorageProperties;

/** Signed service-only transitions. Gateway rejects this namespace. */
@RestController
@RequestMapping("/internal/v1/submissions")
public class InternalSubmissionController {
    private static final Duration ACCESS_TTL = Duration.ofMinutes(5);
    private final JdbcTemplate jdbc; private final String secret;
    private final ExamFileRepository examFileRepo; private final ObjectStorageGateway storage;
    private final StorageProperties storageProps; private final SubmissionEventRepository eventRepo;
    public InternalSubmissionController(JdbcTemplate jdbc, @Value("${app.internal.hmac-secret}") String secret,
            ExamFileRepository examFileRepo, ObjectStorageGateway storage, StorageProperties storageProps,
            SubmissionEventRepository eventRepo) {
        this.jdbc=jdbc; this.secret=secret; this.examFileRepo=examFileRepo; this.storage=storage; this.storageProps=storageProps;
        this.eventRepo=eventRepo;
    }
    private static final Set<String> STATUS_CALLERS = Set.of("exam-grading-service","result-review-service","submission-worker");
    private static final Set<String> READ_CALLERS = Set.of("exam-grading-service","result-review-service");
    private static final Set<String> FINALIZE_CALLERS = Set.of("result-review-service");
    private static final Set<String> PURGE_CALLERS = Set.of("result-review-service");

    @GetMapping("/{submissionId}")
    public Map<String,Object> get(@PathVariable UUID submissionId,
        @RequestHeader("X-Service-Caller") String caller, @RequestHeader("X-Service-Request-Id") UUID requestId,
        @RequestHeader("X-Service-Timestamp") Instant timestamp, @RequestHeader("X-Service-Signature") String signature) {
        verify(caller, requestId, timestamp, signature, READ_CALLERS);
        try {
            return jdbc.queryForMap("select submission_id as \"submissionId\",exam_id as \"examId\",status,version from submission where submission_id=?",submissionId);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        }
    }

    @PostMapping("/{submissionId}/status")
    public Map<String,Object> change(@PathVariable UUID submissionId, @RequestBody Map<String,Object> body,
        @RequestHeader("X-Service-Caller") String caller, @RequestHeader("X-Service-Request-Id") UUID requestId,
        @RequestHeader("X-Service-Timestamp") Instant timestamp, @RequestHeader("X-Service-Signature") String signature) {
        verify(caller, requestId, timestamp, signature, STATUS_CALLERS);
        Map<String,Object> current;
        try {
            current = jdbc.queryForMap("select status,version from submission where submission_id=?",submissionId);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        }
        String from = (String) current.get("status");
        String target = String.valueOf(body.get("newStatus"));
        // Idempotent replay: already at the requested destination → 200 current state.
        if (from.equals(target)) {
            return jdbc.queryForMap("select submission_id as \"submissionId\",status,version from submission where submission_id=?",submissionId);
        }
        String expected=(String)body.get("expectedStatus");
        if(expected!=null&&!expected.equals(from)) throw new ResponseStatusException(HttpStatus.CONFLICT,"STATUS_VERSION_CONFLICT");
        SubmissionStatus to;
        try {
            to = SubmissionStatus.valueOf(target);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_STATUS");
        }
        try {
            SubmissionStatusMachine.transition(SubmissionStatus.valueOf(from), to);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"ILLEGAL_TRANSITION");
        }
        int progress=body.get("progress")==null?0:Integer.parseInt(body.get("progress").toString());
        String reasonCode = body.get("reasonCode") == null ? null : body.get("reasonCode").toString();
        // reasonCode lands on failure_code for FAILED; finalizedAt only matters on →FINALIZED.
        String failureCode = "FAILED".equals(target) ? reasonCode : null;
        Instant finalizedAt = null;
        Instant retentionDeadline = null;
        if ("FINALIZED".equals(target)) {
            finalizedAt = body.get("finalizedAt") != null
                ? Instant.parse(body.get("finalizedAt").toString()) : Instant.now();
            retentionDeadline = finalizedAt.atOffset(ZoneOffset.UTC).plusYears(1).toInstant();
        }
        // Conditional update keeps the optimistic check atomic (FR-022).
        int moved = jdbc.update("""
            update submission set status=?, progress=?,
                failure_code = case
                    when ? = 'FAILED' then coalesce(?, failure_code)
                    when ? = 'FAILED' then null
                    else failure_code end,
                finalized_at = coalesce(finalized_at, ?),
                retention_deadline = coalesce(retention_deadline, ?),
                version=version+1, updated_at=now()
            where submission_id=? and status=?""",
            target, progress, target, failureCode, from,
            finalizedAt == null ? null : Timestamp.from(finalizedAt),
            retentionDeadline == null ? null : Timestamp.from(retentionDeadline),
            submissionId, from);
        if (moved == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"STATUS_VERSION_CONFLICT");
        }
        eventRepo.append(submissionId, SubmissionStatus.valueOf(target), progress);
        if (reasonCode != null) {
            jdbc.update("insert into audit_log(id,actor_id,action,entity_type,entity_id,detail) values (?,null,'SUBMISSION_STATUS_CHANGED','submission',?,?::jsonb)",
                UUID.randomUUID(), submissionId,
                "{\"from\":\"" + from + "\",\"to\":\"" + target + "\",\"reasonCode\":\"" + reasonCode + "\"}");
        }
        return jdbc.queryForMap("select submission_id as \"submissionId\",status,version from submission where submission_id=?",submissionId);
    }

    /**
     * Presigned GET for a stored file (5 min). Internal callers already authorized
     * the end user; per spec this endpoint never grants ORIGINAL — only ANONYMIZED.
     * While an anonymized copy is being overwritten (VALIDATING/ANONYMIZING during
     * REPROCESS), the stale object must not be served → 409 FILE_NOT_READY (§4.6).
     */
    @PostMapping("/{submissionId}/files/{type}/access")
    public Map<String,Object> fileAccess(@PathVariable UUID submissionId, @PathVariable String type,
        @RequestHeader("X-Service-Caller") String caller, @RequestHeader("X-Service-Request-Id") UUID requestId,
        @RequestHeader("X-Service-Timestamp") Instant timestamp, @RequestHeader("X-Service-Signature") String signature) {
        verify(caller, requestId, timestamp, signature, READ_CALLERS);
        if ("ORIGINAL".equals(type)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"ORIGINAL_FORBIDDEN");
        }
        if (!"ANONYMIZED".equals(type)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        }
        String status;
        try {
            status = jdbc.queryForObject("select status from submission where submission_id=?",String.class,submissionId);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        }
        if (SubmissionStatus.VALIDATING.name().equals(status) || SubmissionStatus.ANONYMIZING.name().equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"FILE_NOT_READY");
        }
        var file = examFileRepo.findCurrentByType(submissionId,"ANONYMIZED")
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"NOT_FOUND"));
        var ticket = storage.presignGet(storageProps.anonymizedBucket(),file.objectKey(),ACCESS_TTL);
        return Map.of("url",ticket.url(),"expiresAt",ticket.expiresAt().toString());
    }

    /**
     * T20 (COMPLETED → FINALIZED), called by the Result/Review saga. Idempotent per
     * submissionId: an already-FINALIZED submission returns 200 unchanged (E-10).
     * finalized_at/retention_deadline are written only on the first finalize and
     * never recomputed (D-15); retention_deadline = finalized_at + 1 calendar year.
     */
    @PostMapping("/{submissionId}/finalize")
    public Map<String,Object> finalize(@PathVariable UUID submissionId, @RequestBody Map<String,Object> body,
        @RequestHeader("X-Service-Caller") String caller, @RequestHeader("X-Service-Request-Id") UUID requestId,
        @RequestHeader("X-Service-Timestamp") Instant timestamp, @RequestHeader("X-Service-Signature") String signature) {
        verify(caller, requestId, timestamp, signature, FINALIZE_CALLERS);
        Map<String,Object> current;
        try {
            current = jdbc.queryForMap("select status,version from submission where submission_id=?",submissionId);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        }
        String status = (String) current.get("status");
        if (SubmissionStatus.FINALIZED.name().equals(status)) {
            return Map.of("status","FINALIZED","version",((Number)current.get("version")).intValue());
        }
        if (!SubmissionStatus.COMPLETED.name().equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"ILLEGAL_TRANSITION");
        }
        Instant finalizedAt = body.get("finalizedAt") != null
            ? Instant.parse(body.get("finalizedAt").toString()) : Instant.now();
        Instant retentionDeadline = finalizedAt.atOffset(ZoneOffset.UTC).plusYears(1).toInstant();
        int moved = jdbc.update("""
            update submission set status='FINALIZED', progress=100,
                finalized_at=coalesce(finalized_at, ?), retention_deadline=coalesce(retention_deadline, ?),
                version=version+1, updated_at=now()
            where submission_id=? and status='COMPLETED'""",
            Timestamp.from(finalizedAt), Timestamp.from(retentionDeadline), submissionId);
        if (moved == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"ILLEGAL_TRANSITION");
        }
        eventRepo.append(submissionId, SubmissionStatus.FINALIZED, 100);
        return jdbc.queryForMap("select 'FINALIZED' as \"status\",version from submission where submission_id=?",submissionId);
    }

    /**
     * Retention purge command (§9.3/FR-025, D-03): deletes ALL detailed data of
     * the submission — every ExamFile version's object (ORIGINAL and ANONYMIZED
     * buckets), exam_file/submission_event/processing_job/audit_log rows and the
     * submission row itself. Only result-review-service may call (it owns the
     * purge orchestration). Idempotent: replay after deletion returns 200 DONE.
     */
    @DeleteMapping("/{submissionId}/detail")
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> purgeDetail(@PathVariable UUID submissionId,
        @RequestHeader("X-Service-Caller") String caller, @RequestHeader("X-Service-Request-Id") UUID requestId,
        @RequestHeader("X-Service-Timestamp") Instant timestamp, @RequestHeader("X-Service-Signature") String signature) {
        verify(caller, requestId, timestamp, signature, PURGE_CALLERS);

        // Collect object keys before deleting rows: ORIGINAL type + the
        // submission's own key live in the original bucket, ANONYMIZED in the
        // anonymized bucket (D-05). Deletes are best-effort — a missing object
        // must not block the row purge.
        var keys = jdbc.query("""
            select type, object_key from exam_file where submission_id=?""",
            (rs, n) -> new String[]{rs.getString("type"), rs.getString("object_key")}, submissionId);
        String submissionKey = jdbc.query("""
            select object_key from submission where submission_id=?""",
            (rs, n) -> rs.getString(1), submissionId).stream().findFirst().orElse(null);
        java.util.Set<String> originalKeys = new java.util.HashSet<>();
        java.util.Set<String> anonymizedKeys = new java.util.HashSet<>();
        for (String[] row : keys) {
            ("ANONYMIZED".equals(row[0]) ? anonymizedKeys : originalKeys).add(row[1]);
        }
        if (submissionKey != null) {
            originalKeys.add(submissionKey);
        }
        for (String key : originalKeys) {
            try { storage.delete(storageProps.bucket(), key); } catch (RuntimeException ignored) { }
        }
        for (String key : anonymizedKeys) {
            try { storage.delete(storageProps.anonymizedBucket(), key); } catch (RuntimeException ignored) { }
        }

        jdbc.update("delete from exam_file where submission_id=?", submissionId);
        jdbc.update("delete from submission_event where submission_id=?", submissionId);
        jdbc.update("delete from processing_job where submission_id=?", submissionId);
        jdbc.update("delete from audit_log where entity_id=?", submissionId);
        jdbc.update("delete from submission where submission_id=?", submissionId);
        return Map.of("status", "DONE");
    }

    private void verify(String caller, UUID requestId, Instant timestamp, String signature, Set<String> allowedCallers) {
        if (!allowedCallers.contains(caller) || Duration.between(timestamp,Instant.now()).abs().toSeconds()>60 || !HmacSigner.verify(secret,caller+"\n"+requestId+"\n"+timestamp.toEpochMilli(),signature)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"INVALID_SERVICE_SIGNATURE");
    }
}
