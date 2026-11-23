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
import org.springframework.transaction.annotation.Transactional;
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
    private static final Set<String> ALL_SERVICE_CALLERS =
        Set.of("submission-service", "exam-grading-service", "result-review-service");
    /** §9.4: only Result/Review orchestrates retention purge. */
    private static final Set<String> PURGE_CALLERS = Set.of("result-review-service");

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
        verifyServiceSignature(caller, requestId, timestamp, signature, ALL_SERVICE_CALLERS);
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
        verifyServiceSignature(caller, requestId, timestamp, signature, ALL_SERVICE_CALLERS);
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

    /**
     * Retention purge (TICKET 04, task 3; spec §9.4, FR-025).
     * Deletes the pseudonym and the submission_binding row for the submission —
     * the binding is the candidate↔submission mapping and counts as detailed
     * data. Idempotent: replay after deletion still returns 200 DONE.
     * Caller allowlist: result-review-service only (it orchestrates purge).
     */
    @DeleteMapping("/identity/submissions/{submissionId}/pseudonym")
    @Transactional
    public ResponseEntity<PurgePseudonymResponse> purgePseudonym(@PathVariable UUID submissionId,
            @RequestHeader("X-Service-Caller") String caller,
            @RequestHeader("X-Service-Request-Id") UUID requestId,
            @RequestHeader("X-Service-Timestamp") Instant timestamp,
            @RequestHeader("X-Service-Signature") String signature) {
        verifyServiceSignature(caller, requestId, timestamp, signature, PURGE_CALLERS);

        int deletedPseudonyms = jdbcTemplate.update(
            "DELETE FROM pseudonym WHERE submission_id = ?", submissionId);
        jdbcTemplate.update(
            "DELETE FROM submission_binding WHERE submission_id = ?", submissionId);

        return ResponseEntity.ok(new PurgePseudonymResponse(deletedPseudonyms, "DONE"));
    }

    /**
     * Resolve submission → candidate identity + pseudonym (spec 05:
     * {@code GET /internal/v1/submissions/{id}/identity}). Used by Submission
     * Service to serve the EXAM_OFFICE-only public {@code /identity} endpoint
     * (FR-019 ghép phách). Returns 404 when the submission has no ACTIVE binding.
     */
    @GetMapping("/submissions/{submissionId}/identity")
    public ResponseEntity<SubmissionIdentityResponse> submissionIdentity(@PathVariable UUID submissionId,
            @RequestHeader("X-Service-Caller") String caller,
            @RequestHeader("X-Service-Request-Id") UUID requestId,
            @RequestHeader("X-Service-Timestamp") Instant timestamp,
            @RequestHeader("X-Service-Signature") String signature) {
        verifyServiceSignature(caller, requestId, timestamp, signature, ALL_SERVICE_CALLERS);

        String sql = """
            SELECT b.candidate_id, c.candidate_number, c.full_name, c.class_name, p.value AS pseudonym
            FROM submission_binding b
            JOIN candidate c ON c.candidate_id = b.candidate_id AND c.exam_id = b.exam_id
            LEFT JOIN pseudonym p ON p.submission_id = b.submission_id
            WHERE b.submission_id = ? AND b.status = 'ACTIVE'
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new SubmissionIdentityResponse(
            rs.getObject("candidate_id", UUID.class),
            rs.getString("candidate_number"),
            rs.getString("full_name"),
            rs.getString("class_name"),
            rs.getString("pseudonym")
        ), submissionId).stream().findFirst()
            .map(ResponseEntity::ok)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "BINDING_NOT_FOUND"));
    }

    /**
     * Manual phách edit (spec 10 §4.1 {@code PUT /api/v1/submissions/{id}/pseudonym},
     * D-10): updates the current pseudonym value and appends a PseudonymHistory row.
     * Submission uniqueness is enforced by UNIQUE(exam_id, value) → 409 on clash.
     * Idempotent: setting the same value returns 200 without a new history row.
     */
    @PutMapping("/submissions/{submissionId}/pseudonym")
    @Transactional
    public ResponseEntity<UpdatePseudonymResponse> updatePseudonym(@PathVariable UUID submissionId,
            @RequestBody UpdatePseudonymRequest request,
            @RequestHeader("X-Service-Caller") String caller,
            @RequestHeader("X-Service-Request-Id") UUID requestId,
            @RequestHeader("X-Service-Timestamp") Instant timestamp,
            @RequestHeader("X-Service-Signature") String signature) {
        verifyServiceSignature(caller, requestId, timestamp, signature, ALL_SERVICE_CALLERS);
        if (request.value() == null || request.value().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PSEUDONYM_REQUIRED");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "REASON_REQUIRED");
        }
        if (request.changedBy() == null || request.changedBy().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "CHANGED_BY_REQUIRED");
        }

        String sql = """
            SELECT pseudonym_id, exam_id, value
            FROM pseudonym
            WHERE submission_id = ?
            """;
        var rows = jdbcTemplate.query(sql, (rs, rowNum) -> new Object[] {
            rs.getObject("pseudonym_id", UUID.class),
            rs.getObject("exam_id", UUID.class),
            rs.getString("value")
        }, submissionId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PSEUDONYM_NOT_FOUND");
        }
        Object[] row = rows.get(0);
        UUID pseudonymId = (UUID) row[0];
        UUID examId = (UUID) row[1];
        String oldValue = (String) row[2];
        String newValue = request.value().trim();

        if (!newValue.equals(oldValue)) {
            try {
                jdbcTemplate.update("UPDATE pseudonym SET value = ? WHERE pseudonym_id = ?",
                    newValue, pseudonymId);
            } catch (DuplicateKeyException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "PSEUDONYM_TAKEN");
            }
            jdbcTemplate.update("""
                INSERT INTO pseudonym_history (pseudonym_id, old_value, new_value, changed_by, reason)
                VALUES (?, ?, ?, ?, ?)
                """, pseudonymId, oldValue, newValue,
                request.changedBy().length() > 100 ? request.changedBy().substring(0, 100) : request.changedBy(),
                request.reason());
        }
        return ResponseEntity.ok(new UpdatePseudonymResponse(submissionId, newValue, examId));
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

    private void verifyServiceSignature(String caller, UUID requestId, Instant timestamp, String signature,
            Set<String> allowedCallers) {
        boolean valid = allowedCallers.contains(caller)
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
    public record PurgePseudonymResponse(int deletedPseudonyms, String status) {}
    public record SubmissionIdentityResponse(UUID candidateId, String candidateNumber,
            String fullName, String className, String pseudonym) {}
    public record UpdatePseudonymRequest(String value, String reason, String changedBy) {}
    public record UpdatePseudonymResponse(UUID submissionId, String pseudonym, UUID examId) {}
}
