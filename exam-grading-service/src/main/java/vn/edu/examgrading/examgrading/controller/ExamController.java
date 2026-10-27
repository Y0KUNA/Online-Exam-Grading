package vn.edu.examgrading.examgrading.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.examgrading.examgrading.dto.CreateExamRequest;
import vn.edu.examgrading.examgrading.service.ExamService;

/** HTTP boundary for exam configuration. Persistence is delegated to ExamService. */
@RestController
@RequestMapping("/api/v1/exams")
public class ExamController {
    private final ExamService exams;

    public ExamController(ExamService exams) { this.exams = exams; }
    @GetMapping public List<Map<String, Object>> list() { return exams.list(); }
    @GetMapping("/{examId}") public Map<String, Object> detail(@PathVariable UUID examId) { return exams.detail(examId); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Map<String, Object> create(@RequestBody CreateExamRequest request) { return exams.create(request); }
    @GetMapping("/{examId}/questions") public List<Map<String, Object>> questions(@PathVariable UUID examId) { return exams.questions(examId); }
    @PostMapping("/{examId}/questions") @ResponseStatus(HttpStatus.CREATED) public Map<String, Object> addQuestion(@PathVariable UUID examId, @RequestBody Map<String, Object> body) { return exams.addQuestion(examId, body); }
    @PostMapping("/{examId}/rubrics") @ResponseStatus(HttpStatus.CREATED) public Map<String, Object> rubric(@PathVariable UUID examId, @RequestBody Map<String, Object> body) { return exams.createRubric(examId, body); }
    @PostMapping("/{examId}/rubrics/{rubricId}/activate") public Map<String, Object> activateRubric(@PathVariable UUID examId, @PathVariable UUID rubricId, @RequestBody Map<String, Object> body) { return exams.activateRubric(examId, rubricId, body); }
}
