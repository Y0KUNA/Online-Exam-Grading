package vn.edu.examgrading.identity.controller;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.examgrading.identity.repository.SubmissionBindingRepository;
import vn.edu.examgrading.common.security.HmacSigner;

/**
 * Internal endpoints for pseudonym management (TICKET 02).
 * Only accessible by other services via internal network, not through Gateway.
 */
@RestController
@RequestMapping("/internal/v1")
public class PseudonymController {

    private static final String PSEUDONYM_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int PSEUDONYM_LENGTH = 8;
    private static final int MAX_GENERATION_ATTEMPTS = 5;

    private final JdbcTemplate jdbcTemplate;
    private final SubmissionBindingRepository bindingRepository;
    private final String internalHmacSecret;
    private final SecureRandom secureRandom = new SecureRandom();

    public PseudonymController(JdbcTemplate jdbcTemplate, SubmissionBindingRepository bindingRepository,
            @Value("${app.internal.hmac-secret}") String internalHmacSecret) {
        this.jdbcTemplate = jdbcTemplate;
        this.bindingRepository = bindingRepository;
        this.internalHmacSecret = internalHmacSecret;
    }

    /**
     * Create pseudonym for submission (called by Submission service VALIDATE worker).
     * Request: {examId, submissionId, idempotencyKey}
     * Identity looks up candidate from binding (E-14).
     */
    @PostMapping("/pseudonyms")
    public ResponseEntity<PseudonymResponse> createPseudonym(@RequestBody PseudonymRequest request,
            @RequestHeader("X-Service-Caller") String caller,
            @RequestHeader("X-Service-Request-Id") UUID requestId,
            @RequestHeader("X-Service-Timestamp") Instant timestamp,
            @RequestHeader("X-Service-Signature") String signature) {
        verifyServiceSignature(caller, requestId, timestamp, signature);
        // Check idempotency - return existing if same key
        var existing = findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return ResponseEntity.ok(existing.get());
        }

        // Get candidate from binding
        UUID candidateId = bindingRepository.getCandidateId(request.submissionId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "BINDING_NOT_FOUND"));

        // Generate unique pseudonym
        String pseudonym = generateUniquePseudonym(request.examId());

        // Insert pseudonym
        UUID pseudonymId = UUID.randomUUID();
        try {
            jdbcTemplate.update(
                """
                INSERT INTO pseudonym (pseudonym_id, exam_id, submission_id, candidate_id, value, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                pseudonymId, request.examId(), request.submissionId(), candidateId, pseudonym, request.idempotencyKey()
            );
        } catch (DuplicateKeyException e) {
            // Race condition or duplicate value - retry with new pseudonym
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PSEUDONYM_GENERATION_FAILED");
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(
            new PseudonymResponse(pseudonymId, pseudonym, request.submissionId())
        );
    }

    /**
     * Lookup pseudonyms by submission IDs (batch).
     * Request: {submissionIds: [uuid]}
     * Response: {items: [{submissionId, pseudonym}]}
     */
    @PostMapping("/pseudonyms/lookup")
    public ResponseEntity<PseudonymLookupResponse> lookupPseudonyms(@RequestBody PseudonymLookupRequest request,
            @RequestHeader("X-Service-Caller") String caller,
            @RequestHeader("X-Service-Request-Id") UUID requestId,
            @RequestHeader("X-Service-Timestamp") Instant timestamp,
            @RequestHeader("X-Service-Signature") String signature) {
        verifyServiceSignature(caller, requestId, timestamp, signature);
        if (request.submissionIds() == null || request.submissionIds().isEmpty()) {
            return ResponseEntity.ok(new PseudonymLookupResponse(java.util.List.of()));
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(request.submissionIds().size(), "?"));
        String sql = """
            SELECT submission_id, value
            FROM pseudonym
            WHERE submission_id IN (""" + placeholders + ")";

        var items = jdbcTemplate.query(sql, (rs, rowNum) -> new PseudonymLookupItem(
            rs.getObject("submission_id", UUID.class),
            rs.getString("value")
        ), request.submissionIds().toArray()).stream().toList();

        return ResponseEntity.ok(new PseudonymLookupResponse(items));
    }

    private java.util.Optional<PseudonymResponse> findByIdempotencyKey(String idempotencyKey) {
        String sql = """
            SELECT pseudonym_id, value, submission_id
            FROM pseudonym
            WHERE idempotency_key = ?
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new PseudonymResponse(
            rs.getObject("pseudonym_id", UUID.class),
            rs.getString("value"),
            rs.getObject("submission_id", UUID.class)
        ), idempotencyKey).stream().findFirst();
    }

    private String generateUniquePseudonym(UUID examId) {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            StringBuilder sb = new StringBuilder(PSEUDONYM_LENGTH);
            for (int i = 0; i < PSEUDONYM_LENGTH; i++) {
                sb.append(PSEUDONYM_CHARS.charAt(secureRandom.nextInt(PSEUDONYM_CHARS.length())));
            }
            String candidate = sb.toString();

            // Check uniqueness
            if (!pseudonymExists(examId, candidate)) {
                return candidate;
            }
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "PSEUDONYM_GENERATION_FAILED");
    }

    private boolean pseudonymExists(UUID examId, String value) {
        String sql = "SELECT EXISTS(SELECT 1 FROM pseudonym WHERE exam_id = ? AND value = ?)";
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, examId, value);
        return Boolean.TRUE.equals(exists);
    }

    private void verifyServiceSignature(String caller, UUID requestId, Instant timestamp, String signature) {
        boolean valid = Set.of("submission-service", "exam-grading-service", "result-review-service").contains(caller)
                && Duration.between(timestamp, Instant.now()).abs().toSeconds() <= 60
                && HmacSigner.verify(internalHmacSecret,
                    caller + "\n" + requestId + "\n" + timestamp.toEpochMilli(), signature);
        if (!valid) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "INVALID_SERVICE_SIGNATURE");
    }

    // DTOs
    public record PseudonymRequest(UUID examId, UUID submissionId, String idempotencyKey) {}
    public record PseudonymResponse(UUID pseudonymId, String pseudonym, UUID submissionId) {}
    public record PseudonymLookupRequest(java.util.List<UUID> submissionIds) {}
    public record PseudonymLookupResponse(java.util.List<PseudonymLookupItem> items) {}
    public record PseudonymLookupItem(UUID submissionId, String pseudonym) {}
}
