package vn.edu.examgrading.common.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** Validates caller identity, timestamp and one-time request ID before a service trusts an internal header. */
public final class InternalRequestVerifier {
    private final String secret; private final Set<String> allowedCallers; private final ReplayGuard replayGuard;
    private final Clock clock;
    public InternalRequestVerifier(String secret, Set<String> allowedCallers, ReplayGuard replayGuard, Clock clock) {
        this.secret = secret; this.allowedCallers = Set.copyOf(allowedCallers); this.replayGuard = replayGuard; this.clock = clock;
    }
    public boolean verify(String caller, SignedUserContext context) {
        if (!allowedCallers.contains(caller) || context == null) return false;
        if (Duration.between(context.timestamp(), Instant.now(clock)).abs().compareTo(Duration.ofSeconds(60)) > 0) return false;
        return HmacSigner.verify(secret, context.canonicalValue(), context.signature()) && replayGuard.firstUse(context.requestId().toString());
    }
}
