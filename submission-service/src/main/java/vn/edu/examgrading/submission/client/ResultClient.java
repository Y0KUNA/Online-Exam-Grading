package vn.edu.examgrading.submission.client;

import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP client for calling Result-Review Service internal endpoints (TICKET 02).
 * Used by ValidationWorker to create reviews for TEMPLATE_SIZE_MISMATCH.
 */
@Component
public class ResultClient {

    private final RestTemplate restTemplate;
    private final String resultReviewBaseUrl;

    public ResultClient(
            RestTemplate restTemplate,
            @Value("${result-review.base-url:http://localhost:8084}") String resultReviewBaseUrl) {
        this.restTemplate = restTemplate;
        this.resultReviewBaseUrl = resultReviewBaseUrl;
    }

    /**
     * Create review in Result-Review service.
     * Called when validation fails with TEMPLATE_SIZE_MISMATCH.
     */
    public void createReview(UUID submissionId, UUID examId, String technicalCode, String detail, String reason) {
        String url = resultReviewBaseUrl + "/internal/v1/reviews";

        ReviewRequest request = new ReviewRequest(submissionId, examId, technicalCode, detail, reason, "VALIDATION_WORKER");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<ReviewRequest> entity = new HttpEntity<>(request, headers);

        restTemplate.postForEntity(url, entity, Void.class);
    }

    /**
     * §4.6/T27: create an ANONYMIZATION/MASK_INSUFFICIENT review for the EO
     * anonymization-issues endpoint. Idempotent result-side on
     * (submissionId, technicalCode, fileVersion); 409 REVIEW_ALREADY_OPEN when
     * a different unclosed review exists.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createAnonymizationIssue(UUID submissionId, UUID examId,
            int fileVersion, UUID templateId, String reason, String createdBy,
            String idempotencyKey) {
        String url = resultReviewBaseUrl + "/internal/v1/reviews";
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("submissionId", submissionId.toString());
        body.put("examId", examId.toString());
        body.put("technicalCode", "MASK_INSUFFICIENT");
        body.put("fileVersion", fileVersion);
        body.put("detail", "{\"fileVersion\":" + fileVersion
            + ",\"templateId\":\"" + (templateId == null ? "" : templateId) + "\"}");
        if (reason != null) body.put("reason", reason);
        if (createdBy != null) body.put("createdBy", createdBy);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) headers.set("Idempotency-Key", idempotencyKey);
        Map<String, Object> res = restTemplate.postForObject(url,
            new HttpEntity<>(body, headers), Map.class);
        return res == null ? Map.of() : res;
    }

    /**
     * Replace-file eligibility probe (§4.4): returns the unclosed review's
     * authorization state; {@code authorized=true} only for an
     * ACTION_AUTHORIZED(REPLACE_FILE) ANONYMIZATION/GRADER_FLAG review.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getReplaceAuthorization(UUID submissionId) {
        String url = resultReviewBaseUrl + "/internal/v1/reviews/replace-authorization?submissionId=" + submissionId;
        Map<String, Object> body = restTemplate.getForObject(url, Map.class);
        return body == null ? Map.of("authorized", false) : body;
    }

    /** §4.1 retry probe: state of one review (status/authorizedAction/technicalCode). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getReviewState(UUID reviewId) {
        String url = resultReviewBaseUrl + "/internal/v1/reviews/" + reviewId + "/state";
        Map<String, Object> body = restTemplate.getForObject(url, Map.class);
        return body == null ? Map.of() : body;
    }

    /** §4.1: consume the authorization — ACTION_AUTHORIZED → RESOLVED(resolutionType). */
    public void resolveReviewAction(UUID reviewId, String resolutionType) {
        String url = resultReviewBaseUrl + "/internal/v1/reviews/resolve-action";
        restTemplate.postForEntity(url,
            Map.of("reviewId", reviewId.toString(), "resolutionType", resolutionType), Void.class);
    }

    /**
     * §4.4 saga step: close the authorized review as RESOLVED(REPLACE_FILE).
     * Idempotent — replay after the review already closed is a no-op.
     */
    public void resolveReplaceReview(UUID submissionId) {
        String url = resultReviewBaseUrl + "/internal/v1/reviews/resolve-replace";
        Map<String, Object> body = Map.of("submissionId", submissionId.toString());
        restTemplate.postForEntity(url, body, Void.class);
    }

    public record ReviewRequest(
            UUID submissionId,
            UUID examId,
            String technicalCode,
            String detail,
            String reason,
            String createdBy) {}
}
