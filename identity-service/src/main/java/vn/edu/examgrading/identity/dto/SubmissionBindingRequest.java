package vn.edu.examgrading.identity.dto;

import java.util.UUID;

/**
 * Request body for POST /internal/v1/submission-bindings (TICKET 01).
 * Stores the mapping between candidate, submission, and exam.
 * Idempotent: calling again with same submissionId returns 200 if binding is ACTIVE.
 */
public record SubmissionBindingRequest(
        UUID examId,
        UUID submissionId,
        UUID candidateId) {
}
