package vn.edu.examgrading.common.security;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/** Test/development implementation; production services replace this with a durable shared store. */
public final class InMemoryReplayGuard implements ReplayGuard {
    private final ConcurrentHashMap<String, Long> used = new ConcurrentHashMap<>();
    private final Clock clock;
    public InMemoryReplayGuard(Clock clock) { this.clock = clock; }
    @Override public boolean firstUse(String requestId) {
        long now = clock.millis(); used.entrySet().removeIf(entry -> entry.getValue() < now);
        return used.putIfAbsent(requestId, now + 60_000L) == null;
    }
}
