package vn.edu.examgrading.submission.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Upload ticket returned by the init call (05-api-contracts). The client must
 * POST {@code fields} verbatim to {@code url} through the Gateway; the object
 * key lives inside {@code fields.key}, never in the URL.
 */
public record InitSubmissionResponse(
        String submissionId,
        String status,
        Upload upload,
        Instant uploadExpiresAt,
        String statusUrl) {

    public record Upload(String url, Map<String, String> fields) { }
}
