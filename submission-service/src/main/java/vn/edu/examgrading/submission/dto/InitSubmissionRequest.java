package vn.edu.examgrading.submission.dto;

import java.util.UUID;

/**
 * Request body for init submission (TICKET 04, TICKET 01).
 * candidateId is required for Identity binding flow (E-14, T01).
 * The binding stores candidate↔submission mapping in Identity Service,
 * allowing workers to retrieve candidateId from submissionId without
 * storing candidateId in Submission DB (FR-022, FR-025).
 */
public record InitSubmissionRequest(UUID candidateId, UUID templateId, String fileName, long fileSizeBytes, String contentType) { }
