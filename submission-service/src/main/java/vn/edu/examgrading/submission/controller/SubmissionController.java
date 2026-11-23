package vn.edu.examgrading.submission.controller;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.examgrading.submission.dto.CompleteSubmissionResponse;
import vn.edu.examgrading.submission.dto.InitSubmissionRequest;
import vn.edu.examgrading.submission.dto.InitSubmissionResponse;
import vn.edu.examgrading.submission.dto.ReplaceFileRequest;
import vn.edu.examgrading.submission.dto.SubmissionPageResponse;
import vn.edu.examgrading.submission.dto.UpdatePseudonymRequest;
import vn.edu.examgrading.submission.service.SubmissionServiceMarker;

/** HTTP boundary for submission upload and office read projections. */
@RestController
@RequestMapping("/api/v1")
public class SubmissionController {
    private final SubmissionServiceMarker service;

    public SubmissionController(SubmissionServiceMarker service) { this.service = service; }

    @PostMapping("/exams/{examId}/submissions")
    public ResponseEntity<InitSubmissionResponse> init(@PathVariable UUID examId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody InitSubmissionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.init(examId, request, null, authorization));
    }

    @PostMapping("/submissions/{submissionId}/complete")
    public ResponseEntity<CompleteSubmissionResponse> complete(@PathVariable UUID submissionId) {
        return ResponseEntity.ok(service.complete(submissionId));
    }

    @GetMapping("/exams/{examId}/submissions")
    public SubmissionPageResponse list(@PathVariable UUID examId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return service.listForExam(examId, status, cursor, limit);
    }

    @GetMapping("/submissions/{submissionId}")
    public Map<String, Object> detail(@PathVariable UUID submissionId) { return service.detail(submissionId); }

    @GetMapping("/submissions/{submissionId}/identity")
    public Map<String, Object> identity(@PathVariable UUID submissionId) { return service.identity(submissionId); }

    @GetMapping("/submissions/{submissionId}/files")
    public Map<String, Object> files(@PathVariable UUID submissionId) { return service.files(submissionId); }

    /**
     * §4.4: EO presigned GET for a file version — ORIGINAL accepts
     * {@code ?version=n} (default current; superseded scans viewable until
     * retention), ANONYMIZED always serves the current version.
     */
    @GetMapping("/submissions/{submissionId}/files/{type}/access")
    public Map<String, Object> fileAccess(@PathVariable UUID submissionId, @PathVariable String type,
            @RequestParam(required = false) Integer version) {
        return service.fileAccess(submissionId, type, version);
    }

    /**
     * §4.4 replace-file step 1: creates a PENDING replacement + presigned POST.
     * Submission status is unchanged; the new ORIGINAL version only becomes
     * current at {@code POST …/replace-file/{id}/complete}.
     */
    @PostMapping("/submissions/{submissionId}/replace-file")
    public ResponseEntity<Map<String, Object>> replaceFile(@PathVariable UUID submissionId,
            @RequestBody ReplaceFileRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.replaceFile(submissionId, request, authorization));
    }

    /**
     * §4.4 replace-file step 2: commits the uploaded replacement object —
     * discard-open-work → new current ORIGINAL version → UPLOADED → publish
     * VALIDATE. Idempotent on an already-COMPLETED replacement.
     */
    @PostMapping("/submissions/{submissionId}/replace-file/{replacementId}/complete")
    public Map<String, Object> completeReplacement(@PathVariable UUID submissionId,
            @PathVariable UUID replacementId) {
        return service.completeReplacement(submissionId, replacementId);
    }

    /**
     * §4.6/T27: EO reopens an anonymization issue (MASK_INSUFFICIENT) on a
     * READY_FOR_GRADING submission → Review OPEN + REVIEW_REQUIRED. 201.
     */
    @PostMapping("/submissions/{submissionId}/anonymization-issues")
    public ResponseEntity<Map<String, Object>> createAnonymizationIssue(
            @PathVariable UUID submissionId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Object reason = body == null ? null : body.get("reason");
        Map<String, Object> res = service.createAnonymizationIssue(submissionId,
            reason == null ? null : reason.toString(), authorization, idempotencyKey);
        return ResponseEntity.status(201).body(res);
    }

    /**
     * §4.1 retry (T17/T18/T19): body {step, force?, reviewId?, templateId?}.
     * FAILED → retry-exhausted codes only; REVIEW_REQUIRED → authorized review.
     */
    @PostMapping("/submissions/{submissionId}/retry")
    public Map<String, Object> retry(@PathVariable UUID submissionId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Object step = body == null ? null : body.get("step");
        Object force = body == null ? null : body.get("force");
        Object reviewId = body == null ? null : body.get("reviewId");
        Object templateId = body == null ? null : body.get("templateId");
        return service.retry(submissionId,
            step == null ? null : step.toString(),
            force instanceof Boolean b ? b : null,
            reviewId == null ? null : UUID.fromString(reviewId.toString()),
            templateId == null ? null : UUID.fromString(templateId.toString()),
            authorization);
    }

    /** §4.4: cancel the pending replacement (204); 404 when none exists. */
    @DeleteMapping("/submissions/{submissionId}/replace-file")
    public ResponseEntity<Void> cancelReplacement(@PathVariable UUID submissionId) {
        service.cancelReplacement(submissionId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/submissions/{submissionId}/pseudonym")
    public Map<String, Object> updatePseudonym(@PathVariable UUID submissionId,
            @RequestBody UpdatePseudonymRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return service.updatePseudonym(submissionId, request, authorization);
    }
}
