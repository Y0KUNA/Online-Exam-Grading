package vn.edu.examgrading.submission.storage;

import io.minio.MinioClient;
import io.minio.PostPolicy;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;
import vn.edu.examgrading.submission.storage.config.StorageProperties;

/**
 * MinIO-backed {@link ObjectStorageGateway}. Chosen over the AWS SDK v2 because
 * only the MinIO client can sign a presigned POST policy with
 * content-length-range/key pinning (ADR-013); the AWS v2 presigner offers PUT only.
 *
 * <p>Presigned form URLs are rewritten onto the public Gateway base: the SigV4
 * POST signature covers the policy/form fields, not the host, so rewriting is safe.
 */
public class S3ObjectStorage implements ObjectStorageGateway {

    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final MinioClient client;
    private final StorageProperties props;
    private final Clock clock;

    public S3ObjectStorage(MinioClient client, StorageProperties props, Clock clock) {
        this.client = client;
        this.props = props;
        this.clock = clock;
    }

    /** Builds a MinIO client pointed at the configured (private) endpoint. */
    public static S3ObjectStorage create(StorageProperties props, Clock clock) {
        MinioClient client = MinioClient.builder()
                .endpoint(props.endpoint())
                .credentials(props.accessKey(), props.secretKey())
                .region(props.region())
                .build();
        return new S3ObjectStorage(client, props, clock);
    }

    @Override
    public UploadTicket presignPost(@Nonnull String objectKey, long declaredBytes, Duration ttl) {
        ZonedDateTime expiry = Instant.now(clock).atZone(ZoneOffset.UTC).plus(ttl);
        long maxSize = Math.min(declaredBytes + props.sizeToleranceBytes(), props.maxPdfBytes());

        PostPolicy policy = new PostPolicy(props.bucket(), expiry);
        policy.addEqualsCondition("key", objectKey);
        policy.addEqualsCondition("Content-Type", PDF_CONTENT_TYPE);
        policy.addContentLengthRangeCondition(1L, maxSize);

        try {
            Map<String, String> signed = client.getPresignedPostFormData(policy);
            // MinIO embeds key/Content-Type in the base64 policy but does not return them
            // as form fields; the browser must POST them, so surface the fixed key here.
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("key", objectKey);
            fields.putAll(signed);
            String url = gatewayUrl();
            return new UploadTicket(url, fields, expiry.toInstant());
        } catch (Exception e) {
            throw new StorageUnavailableException("presign POST failed for " + objectKey, e);
        }
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(content)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(props.bucket())
                    .object(objectKey)
                    .stream(in, content.length, -1L)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new StorageUnavailableException("put failed for " + objectKey, e);
        }
    }

    @Override
    public Optional<StoredObject> head(String objectKey) {
        try {
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder()
                    .bucket(props.bucket())
                    .object(objectKey)
                    .build());
            return Optional.of(new StoredObject(objectKey, stat.size(), null));
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return Optional.empty();
            }
            throw new StorageUnavailableException("head failed for " + objectKey, e);
        } catch (Exception e) {
            throw new StorageUnavailableException("head failed for " + objectKey, e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(props.bucket())
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new StorageUnavailableException("delete failed for " + objectKey, e);
        }
    }

    /** Gateway pass-through base + bucket path, e.g. http://localhost:8080/storage/original. */
    private String gatewayUrl() {
        String base = props.gatewayBase();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/" + props.bucket()).toString();
    }
}
