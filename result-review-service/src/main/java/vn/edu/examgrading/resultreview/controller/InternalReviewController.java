package vn.edu.examgrading.resultreview.controller;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Internal endpoints for service-to-service communication (TICKET 02).
 * Not exposed through Gateway - only accessible by other services.
 */
@RestController
@RequestMapping("/internal/v1")
public class InternalReviewController {

    private final JdbcTemplate jdbc;

    public InternalReviewController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Create review for submission (called by Submission service worker).
     * Request: {submissionId, examId, technicalCode, detail, reason?, createdBy?}
     * technicalCode ∈ TEMPLATE_SIZE_MISMATCH, TEMPLATE_MASK_ERROR, MASK_INSUFFICIENT
     */
    @PostMapping("/reviews")
    public Map<String, Object> createReview(@RequestBody Map<String, Object> body) {
        UUID submissionId = UUID.fromString(body.get("submissionId").toString());
        UUID examId = UUID.fromString(body.get("examId").toString());
        String technicalCode = body.get("technicalCode").toString();
        String detail = body.get("detail") != null ? body.get("detail").toString() : null;
        String reason = body.get("reason") != null ? body.get("reason").toString() : null;
        String createdBy = body.get("createdBy") != null ? body.get("createdBy").toString() : null;
        Integer fileVersion = body.get("fileVersion") != null
            ? Integer.valueOf(body.get("fileVersion").toString()) : null;

        // §3.2/D-21 + spec §10 internal contract: at most one unclosed review per
        // submission (also enforced by uq_review_one_unclosed). A retry of the
        // same logical request (same technicalCode + fileVersion) replays the
        // existing review; a genuinely different request → 409.
        List<Map<String, Object>> unclosed = jdbc.queryForList("""
            SELECT review_id, status, technical_code, detail->>'fileVersion' AS fv
            FROM review
            WHERE submission_id = ? AND status IN ('OPEN','ACTION_AUTHORIZED')
            LIMIT 1
            """, submissionId);
        if (!unclosed.isEmpty()) {
            var existing = unclosed.get(0);
            Integer existingFv = existing.get("fv") == null ? null
                : Integer.valueOf(existing.get("fv").toString());
            if (technicalCode.equals(existing.get("technical_code"))
                    && Objects.equals(fileVersion, existingFv)) {
                return Map.of(
                    "reviewId", existing.get("review_id"),
                    "submissionId", submissionId,
                    "status", existing.get("status")
                );
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REVIEW_ALREADY_OPEN");
        }

        UUID reviewId = UUID.randomUUID();
        String sql = """
            INSERT INTO review (review_id, submission_id, exam_id, reason_type, technical_code, detail, reason, status, created_by, opened_at)
            VALUES (?, ?, ?, 'ANONYMIZATION', ?, ?, ?, 'OPEN', ?, now())
            """;
        jdbc.update(sql, reviewId, submissionId, examId, technicalCode, detail, reason, createdBy);

        return Map.of(
            "reviewId", reviewId,
            "submissionId", submissionId,
            "status", "OPEN"
        );
    }

    /**
     * Replace-file eligibility probe (§4.4, called by Submission service):
     * {@code authorized=true} only when the submission's single unclosed Review
     * is {@code ACTION_AUTHORIZED} with {@code authorized_action=REPLACE_FILE}
     * and a replace-eligible reason type (ANONYMIZATION|GRADER_FLAG, D-19).
     */
    @GetMapping("/reviews/replace-authorization")
    public Map<String, Object> replaceAuthorization(@RequestParam UUID submissionId) {
        var rows = jdbc.queryForList("""
            SELECT review_id, reason_type, status, authorized_action
            FROM review
            WHERE submission_id = ? AND status IN ('OPEN','ACTION_AUTHORIZED')
            ORDER BY opened_at DESC LIMIT 1
            """, submissionId);
        if (rows.isEmpty()) {
            // Retry tolerance (§4.4): a previous complete may have resolved the
            // review already — report that so the saga can finish idempotently.
            Integer resolved = jdbc.queryForObject("""
                SELECT COUNT(*) FROM review
                WHERE submission_id = ? AND status = 'RESOLVED' AND resolution_type = 'REPLACE_FILE'
                """, Integer.class, submissionId);
            return resolved != null && resolved > 0
                ? Map.of("authorized", true, "resolvedReplaceFile", true)
                : Map.of("authorized", false);
        }
        var row = rows.get(0);
        String status = (String) row.get("status");
        String reasonType = (String) row.get("reason_type");
        String action = (String) row.get("authorized_action");
        boolean authorized = "ACTION_AUTHORIZED".equals(status)
            && "REPLACE_FILE".equals(action)
            && ("ANONYMIZATION".equals(reasonType) || "GRADER_FLAG".equals(reasonType));
        return Map.of(
            "authorized", authorized,
            "reviewId", row.get("review_id"),
            "reasonType", reasonType == null ? "" : reasonType,
            "status", status,
            "authorizedAction", action == null ? "" : action
        );
    }

    /**
     * Retry authorization probe (§4.1 T17/T18, called by Submission service):
     * state of a specific review — status, authorized_action, technical_code.
     */
    @GetMapping("/reviews/{reviewId}/state")
    public Map<String, Object> reviewState(@PathVariable UUID reviewId) {
        var rows = jdbc.queryForList("""
            SELECT review_id, submission_id, status, authorized_action, technical_code, reason_type
            FROM review WHERE review_id = ?
            """, reviewId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND");
        }
        var r = rows.get(0);
        return Map.of(
            "reviewId", r.get("review_id"),
            "submissionId", r.get("submission_id"),
            "status", r.get("status"),
            "authorizedAction", r.get("authorized_action") == null ? "" : r.get("authorized_action"),
            "technicalCode", r.get("technical_code") == null ? "" : r.get("technical_code"),
            "reasonType", r.get("reason_type") == null ? "" : r.get("reason_type")
        );
    }

    /**
     * §4.1: consume a council authorization — ACTION_AUTHORIZED →
     * RESOLVED(resolutionType). Idempotent: replay with the same resolution is
     * a no-op; a different resolution on a closed review → 409.
     */
    @PostMapping("/reviews/resolve-action")
    public Map<String, Object> resolveAction(@RequestBody Map<String, Object> body) {
        UUID reviewId = UUID.fromString(body.get("reviewId").toString());
        String resolution = body.get("resolutionType").toString();
        var rows = jdbc.queryForList("""
            SELECT status, resolution_type FROM review WHERE review_id = ?
            """, reviewId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND");
        }
        var r = rows.get(0);
        String status = (String) r.get("status");
        if ("RESOLVED".equals(status)) {
            if (resolution.equals(r.get("resolution_type"))) {
                return Map.of("reviewId", reviewId, "status", "RESOLVED");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REVIEW_ALREADY_DECIDED");
        }
        if (!"ACTION_AUTHORIZED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REVIEW_NOT_AUTHORIZED");
        }
        jdbc.update("""
            UPDATE review SET status='RESOLVED', resolution_type=?, resolved_at=now()
            WHERE review_id=? AND status='ACTION_AUTHORIZED'
            """, resolution, reviewId);
        return Map.of("reviewId", reviewId, "status", "RESOLVED");
    }

    /**
     * §4.4 saga step: close the submission's unclosed review as
     * RESOLVED(REPLACE_FILE). Idempotent — replay is a no-op.
     */
    @PostMapping("/reviews/resolve-replace")
    public Map<String, Object> resolveReplace(@RequestBody Map<String, Object> body) {
        UUID submissionId = UUID.fromString(body.get("submissionId").toString());
        int moved = jdbc.update("""
            UPDATE review
            SET status = 'RESOLVED', resolution_type = 'REPLACE_FILE', resolved_at = now()
            WHERE submission_id = ? AND status IN ('OPEN','ACTION_AUTHORIZED')
            """, submissionId);
        return Map.of("submissionId", submissionId, "resolved", moved);
    }
}
