package vn.edu.examgrading.submission.messaging;

import java.util.UUID;

/**
 * Message published to {@code submission.anonymize.v1} (spec §8, D-18).
 * Never carries candidateId/name — only ids, file version and the số phách.
 * {@code overwrite}/{@code templateId} for reprocess jobs (T18/T27) are NOT
 * carried here: the worker reads them from {@code ProcessingJob} by jobId (§8).
 */
public record AnonymizeJobMessage(
        int schemaVersion,
        UUID jobId,
        UUID submissionId,
        UUID examId,
        UUID templateId,
        int fileVersion,
        String pseudonym,
        int attempt,
        UUID correlationId) {

    public static final int SCHEMA_VERSION = 1;

    public AnonymizeJobMessage(UUID jobId, UUID submissionId, UUID examId, UUID templateId,
                               int fileVersion, String pseudonym) {
        this(SCHEMA_VERSION, jobId, submissionId, examId, templateId, fileVersion,
            pseudonym, 0, UUID.randomUUID());
    }
}
