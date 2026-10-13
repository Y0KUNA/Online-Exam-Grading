package vn.edu.examgrading.common.outbox;

import java.util.function.Consumer;

/** Transport-neutral relay contract.  Implementations must mark a record delivered only after the consumer acknowledges it. */
public final class OutboxRelay {
    private final Consumer<OutboxMessage> sender;
    public OutboxRelay(Consumer<OutboxMessage> sender) { this.sender = sender; }
    public void relay(OutboxMessage message) { sender.accept(message); }
}
