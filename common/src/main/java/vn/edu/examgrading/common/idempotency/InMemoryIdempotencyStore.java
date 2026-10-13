package vn.edu.examgrading.common.idempotency;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Atomic reference implementation. Persist the same uniqueness tuple in each service database in production. */
public final class InMemoryIdempotencyStore implements IdempotencyStore {
    private final ConcurrentHashMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();
    private static String key(UUID actor, String operation, String key) { return actor + "\u0000" + operation + "\u0000" + key; }
    @Override public Optional<IdempotencyRecord> find(UUID actorId, String operation, String key) {
        return Optional.ofNullable(records.get(key(actorId, operation, key)));
    }
    @Override public void save(IdempotencyRecord record) {
        IdempotencyRecord previous = records.putIfAbsent(key(record.actorId(), record.operation(), record.key()), record);
        if (previous != null && !previous.responseBody().equals(record.responseBody()))
            throw new IllegalStateException("IDEMPOTENCY_KEY_REUSED");
    }
}
