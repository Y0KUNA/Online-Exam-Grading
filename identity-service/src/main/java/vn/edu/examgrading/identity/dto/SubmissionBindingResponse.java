package vn.edu.examgrading.identity.dto;

import java.util.UUID;

/**
 * Response body for POST /internal/v1/submission-bindings (TICKET 01).
 */
public record SubmissionBindingResponse(
        UUID bindingId,
        String status) {
}
