package vn.edu.examgrading.examgrading.controller;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.examgrading.examgrading.dto.BulkAssignmentRequest;
import vn.edu.examgrading.examgrading.service.AssignmentService;

/** APIs for the exam office to distribute work and for graders to open it. */
@RestController
public class AssignmentController {
    private final AssignmentService assignments;
    public AssignmentController(AssignmentService assignments) { this.assignments = assignments; }

    @PostMapping({"/api/v1/exams/{examId}/assignments", "/api/v1/exams/{examId}/assignments/bulk"})
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> bulk(@PathVariable UUID examId, @RequestBody BulkAssignmentRequest request) { return assignments.bulkAssign(examId, request); }

    @GetMapping("/api/v1/grading/assignments")
    public Map<String, Object> list(@RequestParam UUID graderId) { return assignments.listAssignments(graderId); }

    @PostMapping("/api/v1/grading/assignments/{assignmentId}/attempts")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> start(@PathVariable UUID assignmentId) { return assignments.startAttempt(assignmentId); }
}
