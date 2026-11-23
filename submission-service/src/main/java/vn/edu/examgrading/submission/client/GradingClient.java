package vn.edu.examgrading.submission.client;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import vn.edu.examgrading.common.security.HmacSigner;

/**
 * HTTP client for Exam &amp; Grading Service internal endpoints (§4.4).
 * Signed with the shared HMAC secret as {@code submission-service} — the only
 * caller allowed on {@code /internal/v1/grading/**}.
 */
@Component
public class GradingClient {

    private final RestTemplate restTemplate;
    private final String gradingBaseUrl;
    private final String internalHmacSecret;

    public GradingClient(
            RestTemplate restTemplate,
            @Value("${exam-grading.base-url:http://localhost:8083}") String gradingBaseUrl,
            @Value("${app.internal.hmac-secret}") String internalHmacSecret) {
        this.restTemplate = restTemplate;
        this.gradingBaseUrl = gradingBaseUrl;
        this.internalHmacSecret = internalHmacSecret;
    }

    /**
     * Attempt counters for replace-file eligibility (§4.4/Q-13):
     * {@code confirmedCount > 0} blocks replacement with 409 ATTEMPTS_EXIST.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> attemptSummary(UUID submissionId) {
        String url = gradingBaseUrl + "/internal/v1/grading/submissions/" + submissionId + "/attempt-summary";
        return restTemplate.exchange(url, HttpMethod.GET,
            new HttpEntity<>(signedHeaders()), Map.class).getBody();
    }

    /**
     * §4.4 saga step 1: cancels open assignments and discards DRAFT attempts.
     * Idempotent on the grading side via {@code Idempotency-Key} (the
     * replacementId is used so replays of the same complete are safe).
     * A 5xx propagates so the caller can retry before mutating anything.
     */
    public void discardOpenWork(UUID submissionId, UUID idempotencyKey) {
        String url = gradingBaseUrl + "/internal/v1/grading/submissions/" + submissionId + "/discard-open-work";
        HttpHeaders headers = signedHeaders();
        headers.set("Idempotency-Key", idempotencyKey.toString());
        restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(headers), Map.class);
    }

    private HttpHeaders signedHeaders() {
        Instant timestamp = Instant.now();
        UUID requestId = UUID.randomUUID();
        String caller = "submission-service";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Service-Caller", caller);
        headers.set("X-Service-Request-Id", requestId.toString());
        headers.set("X-Service-Timestamp", timestamp.toString());
        headers.set("X-Service-Signature", HmacSigner.sign(internalHmacSecret,
            caller + "\n" + requestId + "\n" + timestamp.toEpochMilli()));
        return headers;
    }
}
