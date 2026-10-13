package vn.edu.examgrading.common.outbox;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Relay seam used by services: write transactionally to their outbox table, then relay idempotently. */
public final class InMemoryOutbox implements OutboxPublisher {
    private final CopyOnWriteArrayList<OutboxMessage> messages = new CopyOnWriteArrayList<>();
    @Override public void publish(OutboxMessage message) { if (!messages.contains(message)) messages.add(message); }
    public List<OutboxMessage> pending() { return List.copyOf(messages); }
}
