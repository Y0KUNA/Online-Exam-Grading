package vn.edu.examgrading.submission.storage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory double used only by unit tests (STORAGE_MODE=memory). It is NOT the
 * dev default: the vertical slice verifies against real MinIO (TICKET 01/02).
 */
public class InMemoryObjectStorage implements ObjectStorageGateway {

    private record Entry(byte[] content, Instant lastModified) { }

    private final Map<String, Entry> objects = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryObjectStorage() {
        this(Clock.systemUTC());
    }

    public InMemoryObjectStorage(Clock clock) {
        this.clock = clock;
    }

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
    public AccessTicket presignGet(String bucket, String objectKey, Duration ttl) {
        return new AccessTicket("http://memory.test/" + bucket + "/" + objectKey + "?signature=in-memory", Instant.now().plus(ttl));
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        objects.put(scopedKey(null, objectKey), new Entry(content.clone(), Instant.now(clock)));
    }

    @Override
    public Optional<StoredObject> head(String objectKey) {
        Entry entry = objects.get(scopedKey(null, objectKey));
        return entry == null ? Optional.empty() : Optional.of(new StoredObject(objectKey, entry.content().length, null));
    }

    @Override
    public byte[] get(String objectKey) {
        Entry entry = objects.get(scopedKey(null, objectKey));
        if (entry == null) {
            throw new StorageUnavailableException("object not found: " + objectKey);
        }
        return entry.content().clone();
    }

    @Override
    public void delete(String objectKey) {
        objects.remove(scopedKey(null, objectKey));
    }

    @Override
    public void put(String bucket, String objectKey, byte[] content, String contentType) {
        objects.put(scopedKey(bucket, objectKey), new Entry(content.clone(), Instant.now(clock)));
    }

    @Override
    public Optional<StoredObject> head(String bucket, String objectKey) {
        Entry entry = objects.get(scopedKey(bucket, objectKey));
        return entry == null ? Optional.empty() : Optional.of(new StoredObject(objectKey, entry.content().length, null));
    }

    @Override
    public byte[] get(String bucket, String objectKey) {
        Entry entry = objects.get(scopedKey(bucket, objectKey));
        if (entry == null) {
            throw new StorageUnavailableException("object not found: " + bucket + "/" + objectKey);
        }
        return entry.content().clone();
    }

    @Override
    public void delete(String bucket, String objectKey) {
        objects.remove(scopedKey(bucket, objectKey));
    }

    @Override
    public List<ObjectInfo> list(String bucket) {
        String prefix = scopedKey(bucket, "");
        List<ObjectInfo> result = new ArrayList<>();
        for (Map.Entry<String, Entry> e : objects.entrySet()) {
            String scoped = e.getKey();
            if (scoped.startsWith(prefix)) {
                String key = scoped.substring(prefix.length());
                result.add(new ObjectInfo(key, e.getValue().lastModified(), e.getValue().content().length));
            }
        }
        return result;
    }

    private static String scopedKey(String bucket, String objectKey) {
        return bucket == null ? objectKey : bucket + "" + objectKey;
    }
}
