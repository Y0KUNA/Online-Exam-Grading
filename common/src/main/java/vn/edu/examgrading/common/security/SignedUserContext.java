package vn.edu.examgrading.common.security;

import java.time.Instant;
import java.util.UUID;

/** Canonical signed identity data allowed on direct internal calls only. */
public record SignedUserContext(UUID userId, String role, UUID requestId, Instant timestamp, String signature) {
    public String canonicalValue() { return userId + "\n" + role + "\n" + requestId + "\n" + timestamp.toEpochMilli(); }
}
