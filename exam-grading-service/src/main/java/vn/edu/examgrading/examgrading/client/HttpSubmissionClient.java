package vn.edu.examgrading.examgrading.client;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import vn.edu.examgrading.common.security.HmacSigner;

/** Retrieves service-only, anonymized file access for an authorized grading attempt. */
@Component
public class HttpSubmissionClient implements SubmissionClient {
    private final RestTemplate http = new RestTemplate();
    private final String baseUrl;
    private final String secret;

    public HttpSubmissionClient(@Value("${SUBMISSION_URL:http://submission-service:8082}") String baseUrl,
            @Value("${app.internal.hmac-secret:replace-with-a-32-byte-minimum-secret}") String secret) {
        this.baseUrl = baseUrl;
        this.secret = secret;
    }

    @Override @SuppressWarnings("unchecked")
    public Map<String, Object> anonymizedAccess(UUID submissionId) {
        return exchange(submissionId + "/files/ANONYMIZED/access", Map.of());
    }

    @Override
    public Map<String, Object> changeStatus(UUID submissionId, String newStatus, int progress) {
        return exchange(submissionId + "/status", Map.of("newStatus", newStatus, "progress", progress));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchange(String path, Object body) {
        Instant now = Instant.now(); UUID requestId = UUID.randomUUID(); String caller = "exam-grading-service";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Service-Caller", caller); headers.set("X-Service-Request-Id", requestId.toString());
        headers.set("X-Service-Timestamp", now.toString());
        headers.set("X-Service-Signature", HmacSigner.sign(secret, caller + "\n" + requestId + "\n" + now.toEpochMilli()));
        return http.exchange(baseUrl + "/internal/v1/submissions/" + path, HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class).getBody();
    }
}
