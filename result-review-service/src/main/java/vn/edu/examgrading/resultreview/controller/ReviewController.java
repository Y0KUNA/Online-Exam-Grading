package vn.edu.examgrading.resultreview.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;

/** Council/office review projections. Candidate identity is never selected here. */
@RestController
@RequestMapping("/api/v1")
public class ReviewController {
    private final JdbcTemplate jdbc;
    public ReviewController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping("/reviews") public Map<String,Object> list(@RequestParam(required=false) UUID examId, @RequestParam(required=false) String status) {
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
    /** Council decision is append-only; the office executes authorized technical actions separately. */
    @PostMapping("/reviews/{reviewId}/decision")
    public Map<String,Object> decide(@PathVariable UUID reviewId, @RequestBody Map<String,Object> body) {
        String type=required(body,"decisionType"), reason=required(body,"reason");
        Map<String,Object> review=detail(reviewId);
        if (!"OPEN".equals(review.get("status"))) throw new IllegalStateException("REVIEW_ALREADY_DECIDED");
        BigDecimal score=body.get("finalScore")==null?null:new BigDecimal(body.get("finalScore").toString());
        if ("SET_FINAL_SCORE".equals(type) && (score==null || score.signum()<0)) throw new IllegalArgumentException("finalScore is required and must be non-negative");
        UUID decisionId=UUID.randomUUID(); boolean authorize=!"SET_FINAL_SCORE".equals(type);
        jdbc.update("insert into council_decision(decision_id,review_id,decision_type,final_score,reason) values (?,?,?,?,?)",decisionId,reviewId,type,score,reason);
        jdbc.update("update review set status=?,resolved_at=?,version=version+1 where review_id=?",authorize?"ACTION_AUTHORIZED":"RESOLVED",authorize?null:OffsetDateTime.now(),reviewId);
        return Map.of("decisionId",decisionId,"reviewId",reviewId,"decisionType",type,"status",authorize?"ACTION_AUTHORIZED":"RESOLVED");
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
