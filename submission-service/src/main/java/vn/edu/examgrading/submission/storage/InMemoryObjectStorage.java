package vn.edu.examgrading.submission.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory double used only by unit tests (STORAGE_MODE=memory). It is NOT the
 * dev default: the vertical slice verifies against real MinIO (TICKET 01/02).
 */
public class InMemoryObjectStorage implements ObjectStorageGateway {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public UploadTicket presignPost(String objectKey, long declaredBytes, Duration ttl) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("key", objectKey);
        fields.put("Content-Type", "application/pdf");
        fields.put("policy", "in-memory-policy");
        fields.put("x-amz-algorithm", "AWS4-HMAC-SHA256");
        fields.put("x-amz-credential", "in-memory");
        fields.put("x-amz-date", Instant.now().toString());
        fields.put("x-amz-signature", "in-memory-signature");
        return new UploadTicket("http://memory.test/original", fields, Instant.now().plus(ttl));
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        objects.put(scopedKey(null, objectKey), content.clone());
    }

    @Override
    public Optional<StoredObject> head(String objectKey) {
        byte[] content = objects.get(scopedKey(null, objectKey));
        return content == null ? Optional.empty() : Optional.of(new StoredObject(objectKey, content.length, null));
    }

    @Override
    public byte[] get(String objectKey) {
        byte[] content = objects.get(scopedKey(null, objectKey));
        if (content == null) {
            throw new StorageUnavailableException("object not found: " + objectKey);
        }
        return content.clone();
    }

    @Override
    public void delete(String objectKey) {
        objects.remove(scopedKey(null, objectKey));
    }

    @Override
    public void put(String bucket, String objectKey, byte[] content, String contentType) {
        objects.put(scopedKey(bucket, objectKey), content.clone());
    }

    @Override
    public Optional<StoredObject> head(String bucket, String objectKey) {
        byte[] content = objects.get(scopedKey(bucket, objectKey));
        return content == null ? Optional.empty() : Optional.of(new StoredObject(objectKey, content.length, null));
    }

    @Override
    public byte[] get(String bucket, String objectKey) {
        byte[] content = objects.get(scopedKey(bucket, objectKey));
        if (content == null) {
            throw new StorageUnavailableException("object not found: " + bucket + "/" + objectKey);
        }
        return content.clone();
    }

    @Override
    public void delete(String bucket, String objectKey) {
        objects.remove(scopedKey(bucket, objectKey));
    }

    private static String scopedKey(String bucket, String objectKey) {
        return bucket == null ? objectKey : bucket + "\u0001" + objectKey;
    }
}
