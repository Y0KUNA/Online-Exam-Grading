package vn.edu.examgrading.submission.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.edu.examgrading.submission.dto.CompleteSubmissionResponse;
import vn.edu.examgrading.submission.dto.InitSubmissionRequest;
import vn.edu.examgrading.submission.dto.InitSubmissionResponse;
import vn.edu.examgrading.submission.service.SubmissionServiceMarker;

/**
 * Office-facing projection plus the upload-init entry point (TICKET 05).
 * Delegates all operations to {@link SubmissionServiceMarker} - no direct JDBC.
 * State changes remain available exclusively under /internal.
 */
@RestController
@RequestMapping("/api/v1")
public class SubmissionController {
    private final SubmissionServiceMarker service;

    public SubmissionController(SubmissionServiceMarker service) {
        this.service = service;
    }

    /**
     * Reserves a submission and returns the presigned POST ticket (TICKET 05).
     * The client then uploads bytes straight to storage through the Gateway.
     */
    @PostMapping("/exams/{examId}/submissions")
    public ResponseEntity<InitSubmissionResponse> init(@PathVariable UUID examId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody InitSubmissionRequest request) {
        InitSubmissionResponse body = service.init(examId, request, null, authorization);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Completes the upload after the client has POSTed bytes to storage (TICKET 06).
     * Validates the uploaded file, transitions to UPLOADED, and enqueues processing.
     */
    @PostMapping("/submissions/{submissionId}/complete")
    public ResponseEntity<CompleteSubmissionResponse> complete(@PathVariable UUID submissionId) {
        CompleteSubmissionResponse body = service.complete(submissionId);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/exams/{examId}/submissions")
    public List<Map<String, Object>> list(@PathVariable UUID examId) {
        return service.listForExam(examId);
    }

    @GetMapping("/submissions/{submissionId}")
    public Map<String, Object> detail(@PathVariable UUID submissionId) {
        return service.detail(submissionId);
    }
}
