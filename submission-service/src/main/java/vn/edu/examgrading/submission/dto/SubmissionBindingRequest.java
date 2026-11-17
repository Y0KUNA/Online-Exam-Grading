package vn.edu.examgrading.submission.dto;

import java.util.UUID;

/**
 * Request body for calling Identity Service POST /internal/v1/submission-bindings (TICKET 01).
 * Stores the mapping between candidate, submission, and exam.
 */
public record SubmissionBindingRequest(
        UUID examId,
        UUID submissionId,
        UUID candidateId) {
}
