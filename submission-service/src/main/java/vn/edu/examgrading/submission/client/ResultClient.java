package vn.edu.examgrading.submission.client;

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

    public record ReviewRequest(
            UUID submissionId,
            UUID examId,
            String technicalCode,
            String detail,
            String reason,
            String createdBy) {}
}
