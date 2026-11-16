package vn.edu.examgrading.submission.storage;

import java.util.UUID;

/**
 * Deterministic object keys: {@code {examId}/{submissionId}/v{n}.pdf}.
 *
 * <p>Never embeds client-supplied {@code fileName} or {@code candidateId}
 * (PII/pseudonym), so a leaked key reveals no identity (BR-013/014).
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
}
