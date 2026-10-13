package vn.edu.examgrading.common.security;

import java.time.Instant;
import java.util.UUID;

public final class ServiceRequestSigner {
    private ServiceRequestSigner() { }

    public static SignedServiceRequest sign(String secret, String caller, UUID requestId, Instant timestamp) {
        SignedServiceRequest unsigned = new SignedServiceRequest(caller, requestId, timestamp, "");
        return new SignedServiceRequest(caller, requestId, timestamp, HmacSigner.sign(secret, unsigned.canonicalValue()));
    }
}
