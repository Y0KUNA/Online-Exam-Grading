package vn.edu.examgrading.submission.controller;

import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import vn.edu.examgrading.common.security.HmacSigner;
import vn.edu.examgrading.submission.domain.SubmissionStatus;

/** Signed service-only transitions. Gateway rejects this namespace. */
@RestController
@RequestMapping("/internal/v1/submissions")
public class InternalSubmissionController {
    private final JdbcTemplate jdbc; private final String secret;
    public InternalSubmissionController(JdbcTemplate jdbc, @Value("${app.internal.hmac-secret}") String secret) { this.jdbc=jdbc; this.secret=secret; }
    @PostMapping("/{submissionId}/status")
    public Map<String,Object> change(@PathVariable UUID submissionId, @RequestBody Map<String,Object> body,
        @RequestHeader("X-Service-Caller") String caller, @RequestHeader("X-Service-Request-Id") UUID requestId,
        @RequestHeader("X-Service-Timestamp") Instant timestamp, @RequestHeader("X-Service-Signature") String signature) {
        if (!Set.of("exam-grading-service","result-review-service","submission-worker").contains(caller) || Duration.between(timestamp,Instant.now()).abs().toSeconds()>60 || !HmacSigner.verify(secret,caller+"\n"+requestId+"\n"+timestamp.toEpochMilli(),signature)) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN,"INVALID_SERVICE_SIGNATURE");
        Map<String,Object> current=jdbc.queryForMap("select status,version from submission where submission_id=?",submissionId);
        String expected=(String)body.get("expectedStatus"); String target=String.valueOf(body.get("newStatus"));
        if(expected!=null&&!expected.equals(current.get("status"))) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,"STATUS_VERSION_CONFLICT");
        allowed(SubmissionStatus.valueOf((String)current.get("status")),SubmissionStatus.valueOf(target));
        int progress=body.get("progress")==null?0:Integer.parseInt(body.get("progress").toString());
        jdbc.update("update submission set status=?,progress=?,version=version+1,updated_at=now() where submission_id=?",target,progress,submissionId);
        jdbc.update("insert into submission_event(submission_id,status,progress) values (?,?,?)",submissionId,target,progress);
        return jdbc.queryForMap("select submission_id as \"submissionId\",status,version from submission where submission_id=?",submissionId);
    }
    private static void allowed(SubmissionStatus from,SubmissionStatus to) {
        boolean valid=switch(from) { case UPLOADING -> to==SubmissionStatus.UPLOADED||to==SubmissionStatus.FAILED; case UPLOADED -> to==SubmissionStatus.VALIDATING; case VALIDATING -> to==SubmissionStatus.ANONYMIZING||to==SubmissionStatus.REVIEW_REQUIRED||to==SubmissionStatus.FAILED; case ANONYMIZING -> to==SubmissionStatus.READY_FOR_GRADING||to==SubmissionStatus.FAILED; case READY_FOR_GRADING -> to==SubmissionStatus.GRADING||to==SubmissionStatus.REVIEW_REQUIRED; case GRADING -> to==SubmissionStatus.COMPLETED||to==SubmissionStatus.REVIEW_REQUIRED; case REVIEW_REQUIRED -> to==SubmissionStatus.GRADING||to==SubmissionStatus.VALIDATING||to==SubmissionStatus.ANONYMIZING||to==SubmissionStatus.COMPLETED||to==SubmissionStatus.FINALIZED; case COMPLETED -> to==SubmissionStatus.FINALIZED||to==SubmissionStatus.REVIEW_REQUIRED; case FINALIZED -> to==SubmissionStatus.REVIEW_REQUIRED; case FAILED -> to==SubmissionStatus.VALIDATING||to==SubmissionStatus.ANONYMIZING; };
        if(!valid) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,"ILLEGAL_TRANSITION");
    }
}
