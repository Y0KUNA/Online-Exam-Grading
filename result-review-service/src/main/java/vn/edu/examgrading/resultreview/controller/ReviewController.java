package vn.edu.examgrading.resultreview.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;

/** Council/office review projections. Candidate identity is never selected here. */
@RestController
@RequestMapping("/api/v1")
public class ReviewController {
    private final JdbcTemplate jdbc;
    private final vn.edu.examgrading.resultreview.client.PurgeClient submissionClient;
    public ReviewController(JdbcTemplate jdbc,
            vn.edu.examgrading.resultreview.client.PurgeClient submissionClient) {
        this.jdbc = jdbc; this.submissionClient = submissionClient;
    }
    @GetMapping("/reviews") @SuppressWarnings("null") public Map<String,Object> list(@RequestParam(required=false) UUID examId, @RequestParam(required=false) String status) {
        StringBuilder sql=new StringBuilder("select review_id as \"reviewId\",submission_id as \"submissionId\",exam_id as \"examId\",reason_type as \"reasonType\",reason,status,opened_at as \"openedAt\",resolved_at as \"resolvedAt\" from review where 1=1");
        java.util.ArrayList<Object> args=new java.util.ArrayList<>();
        if(examId!=null){ sql.append(" and exam_id=?"); args.add(examId); } if(status!=null&&!status.isBlank()){ sql.append(" and status=?"); args.add(status); } sql.append(" order by opened_at desc");
        return Map.of("items",jdbc.queryForList(sql.toString(),args.toArray()));
    }
    @GetMapping("/reviews/{reviewId}") public Map<String,Object> detail(@PathVariable UUID reviewId) {
        Map<String,Object> review=jdbc.queryForMap("select review_id as \"reviewId\",submission_id as \"submissionId\",exam_id as \"examId\",reason_type as \"reasonType\",reason,status,technical_code as \"technicalCode\",opened_at as \"openedAt\" from review where review_id=?",reviewId);
        List<Map<String,Object>> notes=jdbc.queryForList("select grader_id as \"graderId\",note,created_at as \"createdAt\" from review_note where review_id=? order by created_at",reviewId);
        review.put("notes",notes); return review;
    }
    /**
     * Council decision (§3.2 matrix). Dismiss-type decisions resolve the review
     * directly; authorized types leave it ACTION_AUTHORIZED for the office to
     * execute. DISMISS on ANONYMIZATION/MASK_INSUFFICIENT also runs T28 —
     * the submission returns to READY_FOR_GRADING before the review closes so
     * a mid-flight failure leaves the review OPEN for a safe retry.
     */
    @PostMapping("/reviews/{reviewId}/decision")
    public Map<String,Object> decide(@PathVariable UUID reviewId, @RequestBody Map<String,Object> body) {
        String type=required(body,"decisionType");
        String reason = body.get("reason") == null ? "" : body.get("reason").toString().trim();
        if (reason.isBlank()) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "REASON_REQUIRED");
        var rows = jdbc.queryForList("""
            select submission_id, reason_type, technical_code, status, regrade_attempt_id
            from review where review_id=?""", reviewId);
        if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND");
        var review = rows.get(0);
        if (!"OPEN".equals(review.get("status"))) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT, "REVIEW_ALREADY_DECIDED");
        String reasonType = String.valueOf(review.get("reason_type"));
        String techCode = review.get("technical_code") == null ? "" : String.valueOf(review.get("technical_code"));
        if (review.get("regrade_attempt_id") != null && !"SET_FINAL_SCORE".equals(type)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "DECISION_NOT_ALLOWED");
        }
        if (!allowedDecisions(reasonType, techCode).contains(type)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "DECISION_NOT_ALLOWED");
        }
        BigDecimal score=body.get("finalScore")==null?null:new BigDecimal(body.get("finalScore").toString());
        if ("SET_FINAL_SCORE".equals(type) && (score==null || score.signum()<0)) throw new IllegalArgumentException("finalScore is required and must be non-negative");
        boolean dismiss = "DISMISS".equals(type);
        // T28 first: move the submission back to READY_FOR_GRADING (idempotent).
        // Only after that succeeds do we close the review — a failure here
        // leaves the review OPEN so the council can retry the decision.
        if (dismiss && "ANONYMIZATION".equals(reasonType) && "MASK_INSUFFICIENT".equals(techCode)) {
            submissionClient.resumeGrading((UUID) review.get("submission_id"));
        }
        UUID decisionId=UUID.randomUUID();
        jdbc.update("insert into council_decision(decision_id,review_id,decision_type,final_score,reason) values (?,?,?,?,?)",decisionId,reviewId,type,score,reason);
        if (dismiss || "SET_FINAL_SCORE".equals(type)) {
            jdbc.update("update review set status='RESOLVED',resolution_type=?,resolved_at=?,authorized_action=null,version=version+1 where review_id=?",
                type, OffsetDateTime.now(), reviewId);
        } else {
            jdbc.update("update review set status='ACTION_AUTHORIZED',authorized_action=?,version=version+1 where review_id=?",
                type, reviewId);
        }
        return Map.of("decisionId",decisionId,"reviewId",reviewId,"decisionType",type,
            "status",(dismiss||"SET_FINAL_SCORE".equals(type))?"RESOLVED":"ACTION_AUTHORIZED");
    }

    /** §3.2 decision matrix. */
    private static java.util.Set<String> allowedDecisions(String reasonType, String techCode) {
        if ("ANONYMIZATION".equals(reasonType)) {
            return switch (techCode) {
                case "TEMPLATE_SIZE_MISMATCH" -> java.util.Set.of("FORCE_ANONYMIZE","REPLACE_FILE");
                case "TEMPLATE_MASK_ERROR" -> java.util.Set.of("REPROCESS","REPLACE_FILE");
                case "MASK_INSUFFICIENT" -> java.util.Set.of("REPROCESS","REPLACE_FILE","DISMISS");
                default -> java.util.Set.of();
            };
        }
        return switch (reasonType) {
            case "GRADER_FLAG" -> java.util.Set.of("DISMISS","REPLACE_FILE","REGRADE");
            case "SCORE_DIFFERENCE" -> java.util.Set.of("SET_FINAL_SCORE");
            case "REMARK" -> java.util.Set.of("DISMISS","REGRADE","SET_FINAL_SCORE");
            default -> java.util.Set.of();
        };
    }
    @PostMapping("/reviews/{reviewId}/council-decision") public Map<String,Object> legacyDecision(@PathVariable UUID reviewId,@RequestBody Map<String,Object> body) { body.put("decisionType","SET_FINAL_SCORE"); return decide(reviewId,body); }
    @GetMapping("/exams/{examId}/results") public Map<String,Object> results(@PathVariable UUID examId) { return Map.of("items",jdbc.queryForList("select result_id as \"resultId\",submission_id as \"submissionId\",final_score as \"finalScore\",decision_source as \"decisionSource\",finalized_at as \"finalizedAt\",retention_deadline as \"retentionDeadline\",purge_status as \"purgeStatus\" from final_result where exam_id=? and is_current=true order by finalized_at",examId)); }
    @GetMapping("/exams/{examId}/results/export") public ResponseEntity<String> export(@PathVariable UUID examId) {
        List<Map<String,Object>> rows=jdbc.queryForList("select submission_id,final_score,decision_source,finalized_at from final_result where exam_id=? and is_current=true order by finalized_at",examId);
        StringBuilder csv=new StringBuilder("submissionId,finalScore,decisionSource,finalizedAt\n");
        for(Map<String,Object> row:rows) csv.append(row.get("submission_id")).append(',').append(row.get("final_score")).append(',').append(row.get("decision_source")).append(',').append(row.get("finalized_at")).append('\n');
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=results-"+examId+".csv").header(HttpHeaders.CONTENT_TYPE,"text/csv; charset=utf-8").body(csv.toString());
    }
    private static String required(Map<String,Object> body,String key) { Object value=body.get(key); if(value==null||value.toString().isBlank()) throw new IllegalArgumentException(key+" is required"); return value.toString().trim(); }
}
