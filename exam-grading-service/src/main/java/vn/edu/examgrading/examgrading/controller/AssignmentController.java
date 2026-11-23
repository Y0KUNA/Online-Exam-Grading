package vn.edu.examgrading.examgrading.controller;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.examgrading.examgrading.dto.BulkAssignmentRequest;
import vn.edu.examgrading.examgrading.dto.SaveQuestionScoreRequest;
import vn.edu.examgrading.examgrading.dto.SaveScoresRequest;
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

    @GetMapping("/api/v1/grading/attempts/{attemptId}")
    public Map<String, Object> detail(@PathVariable UUID attemptId) { return assignments.attemptDetail(attemptId); }

    @GetMapping("/api/v1/grading/attempts/{attemptId}/context")
    public Map<String, Object> context(@PathVariable UUID attemptId) { return assignments.attemptContext(attemptId); }

    @org.springframework.web.bind.annotation.PutMapping("/api/v1/grading/attempts/{attemptId}/scores")
    public Map<String, Object> saveScores(@PathVariable UUID attemptId, @RequestBody SaveScoresRequest request) {
        return assignments.saveScores(attemptId, request);
    }

    @org.springframework.web.bind.annotation.PutMapping("/api/v1/grading/attempts/{attemptId}/scores/{questionId}")
    public Map<String, Object> saveQuestionScore(@PathVariable UUID attemptId, @PathVariable UUID questionId,
            @RequestBody SaveQuestionScoreRequest request) {
        return assignments.saveQuestionScore(attemptId, questionId, request);
    }

    @PostMapping("/api/v1/grading/attempts/{attemptId}/confirm")
    public Map<String, Object> confirm(@PathVariable UUID attemptId) { return assignments.confirmAttempt(attemptId); }
}
