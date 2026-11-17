package vn.edu.examgrading.submission.storage.config;

/**
 * Resolved storage/upload configuration. Values come from {@code app.storage.*}
 * and {@code app.upload.*} (see application.yml). {@code gatewayBase} is the
 * public Gateway prefix presigned URLs are rewritten onto; {@code endpoint} is
 * the private MinIO address the SDK actually signs against.
 */
public record StorageProperties(
        String mode,
        String endpoint,
        String region,
        String accessKey,
        String secretKey,
        String bucket,
        String gatewayBase,
        long maxPdfBytes,
        long sizeToleranceBytes) {
}
