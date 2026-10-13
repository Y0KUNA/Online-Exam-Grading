package vn.edu.examgrading.common.idempotency;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/** Replays the first completed operation for an actor/operation/key tuple. */
public final class IdempotencyExecutor {
    private final IdempotencyStore store; private final Clock clock;
    public IdempotencyExecutor(IdempotencyStore store, Clock clock) { this.store = store; this.clock = clock; }
    public IdempotencyRecord execute(UUID actor, String operation, String key, Supplier<IdempotencyRecord> operationBody) {
        return store.find(actor, operation, key).orElseGet(() -> {
            IdempotencyRecord result = operationBody.get();
            IdempotencyRecord normalized = new IdempotencyRecord(actor, operation, key, result.responseStatus(), result.responseBody(), Instant.now(clock));
            store.save(normalized);
            return store.find(actor, operation, key).orElse(normalized);
        });
    }
}
