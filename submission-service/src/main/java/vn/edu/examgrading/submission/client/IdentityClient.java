package vn.edu.examgrading.submission.client;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import vn.edu.examgrading.submission.dto.PseudonymRequest;
import vn.edu.examgrading.submission.dto.PseudonymResponse;
import vn.edu.examgrading.submission.dto.SubmissionBindingRequest;
import vn.edu.examgrading.submission.dto.SubmissionBindingReleaseRequest;

/**
 * HTTP client for calling Identity Service internal endpoints (TICKET 01).
 * Uses service credential (not JWT) for service-to-service communication.
 */
@Component
public class IdentityClient {

    private final RestTemplate restTemplate;
    private final String identityBaseUrl;

    public IdentityClient(
            RestTemplate restTemplate,
            @Value("${identity.base-url:http://localhost:8081}") String identityBaseUrl) {
        this.restTemplate = restTemplate;
        this.identityBaseUrl = identityBaseUrl;
    }

    /**
     * Call Identity Service to create submission binding.
     * Returns true if successful, false if binding already exists (idempotent).
     * Throws exception if candidate already bound to another submission.
     */
    public void createBinding(UUID examId, UUID submissionId, UUID candidateId) {
        String url = identityBaseUrl + "/internal/v1/submission-bindings";
        SubmissionBindingRequest request = new SubmissionBindingRequest(examId, submissionId, candidateId);

        try {
            restTemplate.postForEntity(url, request, Void.class);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                // CANDIDATE_ALREADY_BOUND or CANDIDATE_EXAM_MISMATCH
                throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, e.getResponseBodyAsString());
            }
            throw e;
        }
    }

    /**
     * Call Identity Service to release binding.
     * Used when upload fails (UPLOAD_ABANDONED, FILE_TOO_LARGE).
     */
    public void releaseBinding(UUID submissionId, String reason) {
        String url = identityBaseUrl + "/internal/v1/submission-bindings/" + submissionId + "/release";
        SubmissionBindingReleaseRequest request = new SubmissionBindingReleaseRequest(reason);

        restTemplate.postForEntity(url, request, Void.class);
    }

    /**
     * Call Identity Service to create pseudonym for submission (TICKET 02).
     * Identity looks up candidate from binding (E-14). Returns the created
     * pseudonym record; idempotent replays return the existing value.
     */
    public PseudonymResponse createPseudonym(UUID examId, UUID submissionId, String idempotencyKey) {
        String url = identityBaseUrl + "/internal/v1/pseudonyms";
        PseudonymRequest request = new PseudonymRequest(examId, submissionId, idempotencyKey);

        try {
            return restTemplate.postForEntity(url, request, PseudonymResponse.class).getBody();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                e.getStatusCode(), e.getResponseBodyAsString());
        }
    }
}
