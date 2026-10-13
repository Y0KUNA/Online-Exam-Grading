package vn.edu.examgrading.common.security;

import java.time.Instant;
import java.util.UUID;

/** Signed caller identity for service-to-service requests. */
public record SignedServiceRequest(String caller, UUID requestId, Instant timestamp, String signature) {
    public String canonicalValue() {
        return caller + "\n" + requestId + "\n" + timestamp.toEpochMilli();
    }
}
