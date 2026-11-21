package vn.edu.examgrading.submission.storage;

import java.util.UUID;

/**
 * Generates original-object keys.  The display form keeps MinIO navigable for
 * Exam Office; the immutable submission ID is retained to prevent collisions.
 */
public final class ObjectKeyFactory {

    private ObjectKeyFactory() { }

    public static String of(UUID examId, UUID submissionId, int version) {
        if (examId == null || submissionId == null) {
            throw new IllegalArgumentException("examId and submissionId are required");
        }
        if (version < 1) {
            throw new IllegalArgumentException("file version must be >= 1");
        }
        return examId + "/" + submissionId + "/v" + version + ".pdf";
    }

    public static String of(UUID examId, UUID submissionId, int version,
            String examName, String examTime, String subject, String candidateName) {
        // Keep the original argument validation shared with legacy callers.
        of(examId, submissionId, version);
        String examFolder = String.join(" - ", segment(examName, "exam-" + examId),
                segment(examTime, "unscheduled"), segment(subject, "subject"));
        return examFolder + "/" + segment(candidateName, "candidate-" + submissionId)
                + "/" + submissionId + "/v" + version + ".pdf";
    }

    private static String segment(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String safe = value.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "-").replaceAll("\\s+", " ");
        if (safe.isBlank() || ".".equals(safe) || "..".equals(safe)) return fallback;
        return safe.length() <= 96 ? safe : safe.substring(0, 96).trim();
    }
}
