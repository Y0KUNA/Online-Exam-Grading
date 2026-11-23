package vn.edu.examgrading.submission.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * S3/MinIO adapter boundary; object keys never contain PII or pseudonyms.
 *
 * <p>Upload uses a presigned POST policy (ADR-013): the client pushes bytes
 * straight to storage through the Gateway, so this boundary only signs the
 * policy, verifies the stored object afterwards, and removes objects.
 */
public interface ObjectStorageGateway {

    /**
     * Signs a POST policy pinned to {@code objectKey}, the configured bucket and
     * a content-length upper bound, valid for {@code ttl}.
     */
    UploadTicket presignPost(String objectKey, long declaredBytes, Duration ttl);

    /** Signs a read URL for an object; callers must never construct storage URLs themselves. */
    String presignGet(String bucket, String objectKey, Duration ttl);

    /**
     * Direct put used by tests and non-client writes (e.g. anonymized copies).
     * Not the browser upload path, which goes through {@link #presignPost}.
     */
    void put(String objectKey, byte[] content, String contentType);

    /** HEAD the object; empty when it does not exist. sha256 is filled later at VALIDATING. */
    Optional<StoredObject> head(String objectKey);

    /** GET the object content as bytes. Used by validation worker. */
    byte[] get(String objectKey);

    void delete(String objectKey);

    /** Bucket-scoped variants used by the ANONYMIZE worker (D-05: separate buckets). */
    void put(String bucket, String objectKey, byte[] content, String contentType);

    Optional<StoredObject> head(String bucket, String objectKey);

    byte[] get(String bucket, String objectKey);

    void delete(String bucket, String objectKey);

    /** Form URL plus fields a client must POST verbatim to upload the object. */
    record UploadTicket(String url, Map<String, String> fields, Instant expiresAt) { }

    /** Reference to a stored object; {@code sha256} may be null until computed. */
    record StoredObject(String objectKey, long sizeBytes, String sha256) { }
}
