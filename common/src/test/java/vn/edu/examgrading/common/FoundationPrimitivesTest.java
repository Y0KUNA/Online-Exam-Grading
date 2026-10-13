package vn.edu.examgrading.common;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.edu.examgrading.common.idempotency.IdempotencyRecord;
import vn.edu.examgrading.common.idempotency.InMemoryIdempotencyStore;
import vn.edu.examgrading.common.security.HmacSigner;
import vn.edu.examgrading.common.security.InMemoryReplayGuard;
import vn.edu.examgrading.common.security.InternalRequestVerifier;
import vn.edu.examgrading.common.security.SignedUserContext;
import vn.edu.examgrading.common.security.ServiceRequestSigner;
import vn.edu.examgrading.common.security.ServiceRequestVerifier;

class FoundationPrimitivesTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC);

    @Test void signedInternalContextRejectsWrongCallerOldTimestampAndReplay() {
        UUID user = UUID.randomUUID(); UUID request = UUID.randomUUID(); Instant now = clock.instant();
        String canonical = user + "\nEXAM_OFFICE\n" + request + "\n" + now.toEpochMilli();
        SignedUserContext context = new SignedUserContext(user, "EXAM_OFFICE", request, now, HmacSigner.sign("secret", canonical));
        InternalRequestVerifier verifier = new InternalRequestVerifier("secret", Set.of("submission"), new InMemoryReplayGuard(clock), clock);
        assertFalse(verifier.verify("other", context));
        assertTrue(verifier.verify("submission", context));
        assertFalse(verifier.verify("submission", context));

        UUID staleRequest = UUID.randomUUID();
        Instant staleTimestamp = now.minusSeconds(61);
        String staleCanonical = user + "\nEXAM_OFFICE\n" + staleRequest + "\n" + staleTimestamp.toEpochMilli();
        SignedUserContext stale = new SignedUserContext(user, "EXAM_OFFICE", staleRequest, staleTimestamp,
            HmacSigner.sign("secret", staleCanonical));
        assertFalse(verifier.verify("submission", stale));
    }

    @Test void idempotencyDoesNotAllowDifferentResponseForSameTuple() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        IdempotencyRecord first = new IdempotencyRecord(UUID.randomUUID(), "init", "key", 201, "body", clock.instant());
        store.save(first);
        assertEquals(first, store.find(first.actorId(), "init", "key").orElseThrow());
        assertThrows(IllegalStateException.class, () -> store.save(new IdempotencyRecord(
            first.actorId(), "init", "key", 200, "other", clock.instant())));
    }

    @Test void signedServiceRequestRejectsStaleAndReplay() {
        var request = ServiceRequestSigner.sign("secret", "identity", UUID.randomUUID(), clock.instant());
        var verifier = new ServiceRequestVerifier("secret", Set.of("identity"), new InMemoryReplayGuard(clock), clock);
        assertTrue(verifier.verify(request));
        assertFalse(verifier.verify(request));
        assertFalse(verifier.verify(ServiceRequestSigner.sign("secret", "identity", UUID.randomUUID(), clock.instant().minusSeconds(61))));
    }
}
