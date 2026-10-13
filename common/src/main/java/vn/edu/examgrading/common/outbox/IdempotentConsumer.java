package vn.edu.examgrading.common.outbox;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Consumer guard for at-least-once delivery. Persistent consumers must replace this with a DB unique key. */
public final class IdempotentConsumer {
    private final Set<UUID> processed = ConcurrentHashMap.newKeySet();

    public boolean consume(OutboxMessage message, Consumer<OutboxMessage> handler) {
        if (!processed.add(message.id())) return false;
        try {
            handler.accept(message);
            return true;
        } catch (RuntimeException exception) {
            processed.remove(message.id());
            throw exception;
        }
    }
}
