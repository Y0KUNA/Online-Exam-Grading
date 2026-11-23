package vn.edu.examgrading.resultreview.client;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import vn.edu.examgrading.common.security.ServiceRequestSigner;
import vn.edu.examgrading.common.security.SignedServiceRequest;

/**
 * Calls the three §9.3 purge commands on behalf of the retention scheduler.
 * Every call carries the HMAC service signature ({@code result-review-service})
 * and {@code Idempotency-Key=resultId:retention:v1} so replays are safe.
 * A 404 is treated as DONE — the target may already have been purged.
 */
@Component
public class PurgeClient {

    private static final Logger log = LoggerFactory.getLogger(PurgeClient.class);
    private static final String CALLER = "result-review-service";

    private final RestTemplate restTemplate;
    private final String secret;
    private final String submissionBaseUrl;
    private final String gradingBaseUrl;
    private final String identityBaseUrl;

    public PurgeClient(RestTemplate restTemplate,
            @Value("${app.internal.hmac-secret}") String secret,
            @Value("${app.services.submission-url:http://localhost:8082}") String submissionBaseUrl,
            @Value("${app.services.exam-grading-url:http://localhost:8083}") String gradingBaseUrl,
            @Value("${app.services.identity-url:http://localhost:8081}") String identityBaseUrl) {
        this.restTemplate = restTemplate;
        this.secret = secret;
        this.submissionBaseUrl = trim(submissionBaseUrl);
        this.gradingBaseUrl = trim(gradingBaseUrl);
        this.identityBaseUrl = trim(identityBaseUrl);
    }

    /** Submission detail purge — deletes all ExamFile versions/objects + rows. */
    public void purgeSubmission(UUID resultId, UUID submissionId) {
        delete(submissionBaseUrl + "/internal/v1/submissions/" + submissionId + "/detail",
            idempotencyKey(resultId), "submission");
    }

    /** Exam&Grading detail purge — deletes question_score/attempt/assignment. */
    public void purgeGrading(UUID resultId, UUID submissionId) {
        delete(gradingBaseUrl + "/internal/v1/grading/submissions/" + submissionId + "/detail",
            idempotencyKey(resultId), "exam-grading");
    }

    /**
     * T28: council DISMISS of a MASK_INSUFFICIENT review returns the
     * submission to READY_FOR_GRADING. Idempotent — the status endpoint
     * replays 200 when already at the destination.
     */
    public void resumeGrading(UUID submissionId) {
        String url = submissionBaseUrl + "/internal/v1/submissions/" + submissionId + "/status";
        SignedServiceRequest signed = ServiceRequestSigner.sign(
            secret, CALLER, UUID.randomUUID(), Instant.now());
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Service-Caller", signed.caller());
        headers.set("X-Service-Request-Id", signed.requestId().toString());
        headers.set("X-Service-Timestamp", signed.timestamp().toString());
        headers.set("X-Service-Signature", signed.signature());
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        restTemplate.postForEntity(URI.create(url),
            new HttpEntity<>(java.util.Map.of(
                "newStatus", "READY_FOR_GRADING",
                "expectedStatus", "REVIEW_REQUIRED",
                "progress", 100), headers), String.class);
    }

    /** Identity purge — deletes pseudonym + submission_binding. */
    public void purgeIdentity(UUID resultId, UUID submissionId) {
        delete(identityBaseUrl + "/internal/v1/identity/submissions/" + submissionId + "/pseudonym",
            idempotencyKey(resultId), "identity");
    }

    private void delete(String url, String idempotencyKey, String target) {
        SignedServiceRequest signed = ServiceRequestSigner.sign(
            secret, CALLER, UUID.randomUUID(), Instant.now());
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Service-Caller", signed.caller());
        headers.set("X-Service-Request-Id", signed.requestId().toString());
        headers.set("X-Service-Timestamp", signed.timestamp().toString());
        headers.set("X-Service-Signature", signed.signature());
        headers.set("Idempotency-Key", idempotencyKey);
        try {
            ResponseEntity<String> res = restTemplate.exchange(URI.create(url),
                HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
            log.debug("Purge {} for {} -> {}", target, url, res.getStatusCode());
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("Purge {} target already gone (404) — treating as DONE", target);
        }
    }

    private static String idempotencyKey(UUID resultId) {
        return resultId + ":retention:v1";
    }

    private static String trim(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
