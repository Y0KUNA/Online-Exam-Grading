package vn.edu.examgrading.submission.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    /**
     * Direct put used by tests and non-client writes (e.g. anonymized copies).
     * Not the browser upload path, which goes through {@link #presignPost}.
     */
    void put(String objectKey, byte[] content, String contentType);

    /**
     * Signs a presigned GET for an existing object, valid for {@code ttl}.
     * The returned URL is rewritten onto the public Gateway storage prefix.
     */
    AccessTicket presignGet(String bucket, String objectKey, Duration ttl);

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

    /**
     * Lists every object in {@code bucket} (recursive). Used by the orphan
     * cleanup job; implementations should page through the full listing.
     */
    List<ObjectInfo> list(String bucket);

    /** Form URL plus fields a client must POST verbatim to upload the object. */
    record UploadTicket(String url, Map<String, String> fields, Instant expiresAt) { }

    /** Presigned GET URL plus its expiry. */
    record AccessTicket(String url, Instant expiresAt) { }

    /** Reference to a stored object; {@code sha256} may be null until computed. */
    record StoredObject(String objectKey, long sizeBytes, String sha256) { }

    /** Listing entry for orphan scans: key plus server-side last-modified time. */
    record ObjectInfo(String objectKey, Instant lastModified, long sizeBytes) { }
}
