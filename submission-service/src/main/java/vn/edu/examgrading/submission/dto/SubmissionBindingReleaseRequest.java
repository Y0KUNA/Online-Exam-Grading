package vn.edu.examgrading.submission.dto;

/**
 * Request body for calling Identity Service POST /internal/v1/submission-bindings/{submissionId}/release (TICKET 01).
 * Reason: UPLOAD_ABANDONED or FILE_TOO_LARGE.
 */
public record SubmissionBindingReleaseRequest(
        String reason) {
}
