package vn.edu.examgrading.identity.controller;

import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.examgrading.identity.dto.SubmissionBindingReleaseRequest;
import vn.edu.examgrading.identity.dto.SubmissionBindingRequest;
import vn.edu.examgrading.identity.dto.SubmissionBindingResponse;
import vn.edu.examgrading.identity.repository.SubmissionBindingRepository;

/**
 * Internal endpoints for submission binding (TICKET 01).
 * Only accessible by other services via internal network, not through Gateway.
 */
@RestController
@RequestMapping("/internal/v1")
public class SubmissionBindingController {

    private final SubmissionBindingRepository bindingRepository;

    public SubmissionBindingController(SubmissionBindingRepository bindingRepository) {
        this.bindingRepository = bindingRepository;
    }

    @PostMapping("/submission-bindings")
    public ResponseEntity<SubmissionBindingResponse> createBinding(
            @RequestBody SubmissionBindingRequest request) {
        // Validate candidate belongs to exam
        if (!candidateBelongsToExam(request.candidateId(), request.examId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CANDIDATE_EXAM_MISMATCH");
        }

        // Check for existing ACTIVE binding (idempotent)
        var existing = bindingRepository.findActiveBinding(request.submissionId());
        if (existing.isPresent()) {
            return ResponseEntity.ok(new SubmissionBindingResponse(
                    existing.get().bindingId(),
                    existing.get().status()));
        }

        // Check if candidate already has ACTIVE binding for this exam
        if (candidateHasActiveBinding(request.candidateId(), request.examId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CANDIDATE_ALREADY_BOUND");
        }

        // Insert new binding
        UUID bindingId = UUID.randomUUID();
        try {
            bindingRepository.insertBinding(bindingId, request.examId(), request.submissionId(), request.candidateId());
        } catch (DuplicateKeyException e) {
            // Race condition: another binding was created concurrently
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CANDIDATE_ALREADY_BOUND");
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(
                new SubmissionBindingResponse(bindingId, "ACTIVE"));
    }

    @PostMapping("/submission-bindings/{submissionId}/release")
    public ResponseEntity<Void> releaseBinding(
            @PathVariable UUID submissionId,
            @RequestBody SubmissionBindingReleaseRequest request) {
        // Validate reason
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REASON");
        }

        // Release binding (idempotent)
        bindingRepository.releaseBinding(submissionId, request.reason());

        return ResponseEntity.noContent().build();
    }

    private boolean candidateBelongsToExam(UUID candidateId, UUID examId) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1 FROM candidate
                    WHERE candidate_id = ? AND exam_id = ?
                )
                """;

        Boolean result = bindingRepository.getJdbcTemplate()
                .queryForObject(sql, Boolean.class, candidateId, examId);

        return Boolean.TRUE.equals(result);
    }

    private boolean candidateHasActiveBinding(UUID candidateId, UUID examId) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1 FROM submission_binding
                    WHERE candidate_id = ?
                      AND exam_id = ?
                      AND status = 'ACTIVE'
                )
                """;

        Boolean result = bindingRepository.getJdbcTemplate()
                .queryForObject(sql, Boolean.class, candidateId, examId);

        return Boolean.TRUE.equals(result);
    }
}
