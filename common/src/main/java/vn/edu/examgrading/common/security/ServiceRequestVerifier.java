package vn.edu.examgrading.common.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** Rejects untrusted callers, stale requests, invalid signatures and replayed request IDs. */
public final class ServiceRequestVerifier {
    private static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(60);
    private final String secret;
    private final Set<String> allowedCallers;
    private final ReplayGuard replayGuard;
    private final Clock clock;

    public ServiceRequestVerifier(String secret, Set<String> allowedCallers, ReplayGuard replayGuard, Clock clock) {
        this.secret = secret;
        this.allowedCallers = Set.copyOf(allowedCallers);
        this.replayGuard = replayGuard;
        this.clock = clock;
    }

    public boolean verify(SignedServiceRequest request) {
        if (request == null || !allowedCallers.contains(request.caller())) return false;
        if (Duration.between(request.timestamp(), Instant.now(clock)).abs().compareTo(MAX_CLOCK_SKEW) > 0) return false;
        return HmacSigner.verify(secret, request.canonicalValue(), request.signature())
            && replayGuard.firstUse(request.requestId().toString());
    }
}
