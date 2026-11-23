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
import vn.edu.examgrading.submission.domain.SubmissionStatusMachine;
import vn.edu.examgrading.submission.storage.config.StorageProperties;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway;

/** Signed service-only transitions. Gateway rejects this namespace. */
@RestController
@RequestMapping("/internal/v1/submissions")
public class InternalSubmissionController {
    private final JdbcTemplate jdbc; private final String secret; private final StorageProperties storage; private final ObjectStorageGateway objects;
    public InternalSubmissionController(JdbcTemplate jdbc, @Value("${app.internal.hmac-secret}") String secret, StorageProperties storage, ObjectStorageGateway objects) { this.jdbc=jdbc; this.secret=secret; this.storage=storage; this.objects=objects; }
    @PostMapping("/{submissionId}/status")
    public Map<String,Object> change(@PathVariable UUID submissionId, @RequestBody Map<String,Object> body,
        @RequestHeader("X-Service-Caller") String caller, @RequestHeader("X-Service-Request-Id") UUID requestId,
        @RequestHeader("X-Service-Timestamp") Instant timestamp, @RequestHeader("X-Service-Signature") String signature) {
        if (!Set.of("exam-grading-service","result-review-service","submission-worker").contains(caller) || Duration.between(timestamp,Instant.now()).abs().toSeconds()>60 || !HmacSigner.verify(secret,caller+"\n"+requestId+"\n"+timestamp.toEpochMilli(),signature)) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN,"INVALID_SERVICE_SIGNATURE");
        Map<String,Object> current=jdbc.queryForMap("select status,version from submission where submission_id=?",submissionId);
        String expected=(String)body.get("expectedStatus"); String target=String.valueOf(body.get("newStatus"));
        if(expected!=null&&!expected.equals(current.get("status"))) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,"STATUS_VERSION_CONFLICT");
        SubmissionStatus from = SubmissionStatus.valueOf((String)current.get("status"));
        SubmissionStatus to = SubmissionStatus.valueOf(target);
        SubmissionStatusMachine.transition(from, to);
        int progress=body.get("progress")==null?0:Integer.parseInt(body.get("progress").toString());
        jdbc.update("update submission set status=?,progress=?,version=version+1,updated_at=now() where submission_id=?",target,progress,submissionId);
        jdbc.update("insert into submission_event(submission_id,status,progress) values (?,?,?)",submissionId,target,progress);
        return jdbc.queryForMap("select submission_id as \"submissionId\",status,version from submission where submission_id=?",submissionId);
    }

    @PostMapping("/{submissionId}/files/ANONYMIZED/access")
    public Map<String, Object> anonymizedAccess(@PathVariable UUID submissionId,
        @RequestHeader("X-Service-Caller") String caller, @RequestHeader("X-Service-Request-Id") UUID requestId,
        @RequestHeader("X-Service-Timestamp") Instant timestamp, @RequestHeader("X-Service-Signature") String signature) {
        if (!Set.of("exam-grading-service","result-review-service").contains(caller) || Duration.between(timestamp,Instant.now()).abs().toSeconds()>60 || !HmacSigner.verify(secret,caller+"\n"+requestId+"\n"+timestamp.toEpochMilli(),signature)) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN,"INVALID_SERVICE_SIGNATURE");
        String objectKey = jdbc.queryForObject("select object_key from exam_file where submission_id=? and type='ANONYMIZED' and is_current=true", String.class, submissionId);
        return Map.of("url", objects.presignGet(storage.anonymizedBucket(), objectKey, Duration.ofMinutes(5)),
            "expiresAt", Instant.now().plus(Duration.ofMinutes(5)).toString());
    }
}
