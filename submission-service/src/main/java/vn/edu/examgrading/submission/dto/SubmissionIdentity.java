package vn.edu.examgrading.submission.dto;

import java.util.UUID;

/**
 * Identity resolution for {@code GET /api/v1/submissions/{id}/identity} (FR-019).
 * Mirrors Identity's internal {@code SubmissionIdentityResponse}; {@code candidateId}
 * is internal-only and stripped before the public response is built.
 */
public record SubmissionIdentity(UUID candidateId, String candidateNumber,
        String fullName, String className, String pseudonym) { }
