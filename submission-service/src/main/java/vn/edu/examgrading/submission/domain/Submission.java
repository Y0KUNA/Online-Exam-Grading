package vn.edu.examgrading.submission.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model of the {@code submission} aggregate row (TICKET 00 contract).
 * Carries plain domain types only; the JDBC repository maps to/from this record.
 */
public record Submission(
        UUID submissionId,
        UUID examId,
        UUID candidateId,
        UUID templateId,
        SubmissionStatus status,
        int currentFileVersion,
        int progress,
        Instant uploadExpiresAt,
        Long declaredFileSizeBytes,
        String objectKey,
        String failureCode,
        int version,
        Instant updatedAt) { }
