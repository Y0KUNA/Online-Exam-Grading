package vn.edu.examgrading.submission.controller;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @PutMapping("/submissions/{submissionId}/pseudonym")
    public Map<String, Object> updatePseudonym(@PathVariable UUID submissionId,
            @RequestBody UpdatePseudonymRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return service.updatePseudonym(submissionId, request, authorization);
    }
}
