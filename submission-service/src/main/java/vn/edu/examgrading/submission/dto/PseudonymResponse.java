package vn.edu.examgrading.submission.dto;

import java.util.UUID;

/**
 * Response from Identity {@code POST /internal/v1/pseudonyms} (TICKET 02/03).
 * {@code pseudonym} is the anonymous identifier used for grading; it is not
 * rendered onto the anonymized PDF.
 */
public record PseudonymResponse(UUID pseudonymId, String pseudonym, UUID submissionId) {}
