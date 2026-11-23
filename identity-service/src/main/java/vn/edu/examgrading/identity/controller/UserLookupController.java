package vn.edu.examgrading.identity.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import vn.edu.examgrading.common.security.HmacSigner;
import vn.edu.examgrading.identity.domain.UserRole;
import vn.edu.examgrading.identity.repository.UserAccountRepository;

/**
 * Internal batch user lookup (TICKET 04, task 2).
 * Only Exam & Grading and Result services may call this — the allowlist is
 * narrower than the pseudonym endpoints. Never returns password_hash.
 */
@RestController
@RequestMapping("/internal/v1")
public class UserLookupController {

    private static final Set<String> ALLOWED_CALLERS = Set.of("exam-grading-service", "result-review-service");

    private final UserAccountRepository userRepository;
    private final String internalHmacSecret;

    public UserLookupController(UserAccountRepository userRepository,
            @Value("${app.internal.hmac-secret}") String internalHmacSecret) {
        this.userRepository = userRepository;
        this.internalHmacSecret = internalHmacSecret;
    }

    /**
     * Lookup users by IDs (batch).
     * Request: {userIds: [uuid]}
     * Response: {items: [{userId, username, fullName, role, isActive}]}
     */
    @PostMapping("/users/lookup")
    public ResponseEntity<UserLookupResponse> lookupUsers(@RequestBody UserLookupRequest request,
            @RequestHeader("X-Service-Caller") String caller,
            @RequestHeader("X-Service-Request-Id") UUID requestId,
            @RequestHeader("X-Service-Timestamp") Instant timestamp,
            @RequestHeader("X-Service-Signature") String signature) {
        verifyServiceSignature(caller, requestId, timestamp, signature);
        if (request.userIds() == null || request.userIds().isEmpty()) {
            return ResponseEntity.ok(new UserLookupResponse(List.of()));
        }

        List<UserLookupItem> items = userRepository.findAllById(request.userIds()).stream()
            .map(user -> new UserLookupItem(user.id(), user.username(), user.fullName(), user.role(), user.active()))
            .toList();

        return ResponseEntity.ok(new UserLookupResponse(items));
    }

    private void verifyServiceSignature(String caller, UUID requestId, Instant timestamp, String signature) {
        boolean valid = ALLOWED_CALLERS.contains(caller)
                && Duration.between(timestamp, Instant.now()).abs().toSeconds() <= 60
                && HmacSigner.verify(internalHmacSecret,
                    caller + "\n" + requestId + "\n" + timestamp.toEpochMilli(), signature);
        if (!valid) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "INVALID_SERVICE_SIGNATURE");
    }

    // DTOs
    public record UserLookupRequest(List<UUID> userIds) {}
    public record UserLookupResponse(List<UserLookupItem> items) {}
    public record UserLookupItem(UUID userId, String username, String fullName, UserRole role, boolean isActive) {}
}
