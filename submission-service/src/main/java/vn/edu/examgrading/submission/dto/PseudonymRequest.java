package vn.edu.examgrading.submission.dto;

import java.util.UUID;

/**
 * Request body for calling Identity Service POST /internal/v1/pseudonyms (TICKET 02).
 * Identity looks up candidate from binding (E-14).
 */
public record PseudonymRequest(
        UUID examId,
        UUID submissionId,
        String idempotencyKey) {
}
