package vn.edu.examgrading.examgrading.storage;

import io.minio.MinioClient;
import io.minio.PostPolicy;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import io.minio.errors.ErrorResponseException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Signs and verifies PDF uploads without exposing the MinIO endpoint to browsers. */
public final class RubricFileStorage {
    private static final String PDF = "application/pdf";
    private final MinioClient client;
    private final RubricStorageProperties props;
    private final Clock clock;

    public RubricFileStorage(MinioClient client, RubricStorageProperties props, Clock clock) {
        this.client = client;
        this.props = props;
        this.clock = clock;
    }

    public UploadTicket presignPost(String objectKey, long declaredBytes) {
        Instant expiresAt = clock.instant().plus(Duration.ofMinutes(props.uploadTtlMinutes()));
        PostPolicy policy = new PostPolicy(props.bucket(), ZonedDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        policy.addEqualsCondition("key", objectKey);
        policy.addEqualsCondition("Content-Type", PDF);
        policy.addContentLengthRangeCondition(1L, declaredBytes);
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("key", objectKey);
            fields.put("Content-Type", PDF);
            fields.putAll(client.getPresignedPostFormData(policy));
            return new UploadTicket(gatewayBucketUrl(), fields, expiresAt);
        } catch (Exception exception) {
            throw new IllegalStateException("Không thể tạo URL tải rubric", exception);
        }
    }

    public Optional<Long> size(String objectKey) {
        try {
            return Optional.of(client.statObject(StatObjectArgs.builder().bucket(props.bucket()).object(objectKey).build()).size());
        } catch (ErrorResponseException exception) {
            if ("NoSuchKey".equals(exception.errorResponse().code())) return Optional.empty();
            throw new IllegalStateException("Không thể kiểm tra rubric đã tải", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Không thể kiểm tra rubric đã tải", exception);
        }
    }

    /** Creates a short-lived browser URL for viewing an uploaded rubric PDF. */
    public AccessTicket presignGet(String objectKey) {
        Duration ttl = Duration.ofMinutes(props.uploadTtlMinutes());
        try {
            URI signed = URI.create(client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(props.bucket()).object(objectKey)
                    .expiry((int) ttl.toSeconds()).build()));
            String pathAndQuery = signed.getRawPath()
                    + (signed.getRawQuery() == null ? "" : "?" + signed.getRawQuery());
            return new AccessTicket(gatewayBase() + pathAndQuery, clock.instant().plus(ttl));
        } catch (Exception exception) {
            throw new IllegalStateException("Không thể tạo URL xem rubric", exception);
        }
    }

    private String gatewayBucketUrl() {
        return URI.create(gatewayBase() + "/" + props.bucket()).toString();
    }

    private String gatewayBase() {
        return props.gatewayBase().endsWith("/")
                ? props.gatewayBase().substring(0, props.gatewayBase().length() - 1)
                : props.gatewayBase();
    }

    public record UploadTicket(String url, Map<String, String> fields, Instant expiresAt) { }
    public record AccessTicket(String url, Instant expiresAt) { }
}
