package vn.edu.examgrading.submission.dto;

/**
 * Request body for {@code PUT /api/v1/submissions/{id}/pseudonym} (spec 10 §4.1):
 * manual phách edit. {@code reason} is mandatory; the change is appended to
 * Identity's PseudonymHistory.
 */
public record UpdatePseudonymRequest(String pseudonym, String reason) { }
