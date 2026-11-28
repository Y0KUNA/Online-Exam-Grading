package vn.edu.examgrading.examgrading.storage;

/** Storage settings for rubric PDFs. The bucket is intentionally distinct from submissions. */
public record RubricStorageProperties(
        String endpoint,
        String region,
        String accessKey,
        String secretKey,
        String bucket,
        String gatewayBase,
        long maxPdfBytes,
        int uploadTtlMinutes) { }
