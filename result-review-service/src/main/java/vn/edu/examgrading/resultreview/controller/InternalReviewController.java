package vn.edu.examgrading.resultreview.controller;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

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

        UUID reviewId = UUID.randomUUID();
        String sql = """
            INSERT INTO review (review_id, submission_id, exam_id, reason_type, technical_code, detail, reason, status, created_by, opened_at)
            VALUES (?, ?, ?, 'ANONYMIZATION', ?, jsonb_build_object('message', ?), ?, 'OPEN', ?, now())
            """;
        jdbc.update(sql, reviewId, submissionId, examId, technicalCode, detail, reason, createdBy);

        return Map.of(
            "reviewId", reviewId,
            "submissionId", submissionId,
            "status", "OPEN"
        );
    }
}
