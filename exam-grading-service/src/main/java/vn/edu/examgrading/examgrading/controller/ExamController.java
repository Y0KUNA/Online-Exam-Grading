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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.examgrading.examgrading.dto.CreateExamRequest;
import vn.edu.examgrading.examgrading.service.ExamService;

/** HTTP boundary for exam configuration; persistence stays in ExamService. */
@RestController
@RequestMapping("/api/v1/exams")
public class ExamController {
    private final ExamService exams;

    public ExamController(ExamService exams) { this.exams = exams; }

    @GetMapping public List<Map<String, Object>> list() { return exams.list(); }
    @GetMapping("/{examId}") public Map<String, Object> detail(@PathVariable UUID examId) { return exams.detail(examId); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody CreateExamRequest request) { return exams.create(request); }

    @PutMapping("/{examId}/template")
    public Map<String, Object> setTemplate(@PathVariable UUID examId, @RequestBody Map<String, UUID> body) { return exams.setTemplate(examId, body.get("templateId")); }

    @GetMapping("/{examId}/questions") public List<Map<String, Object>> questions(@PathVariable UUID examId) { return exams.questions(examId); }

    @PostMapping("/{examId}/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addQuestion(@PathVariable UUID examId, @RequestBody Map<String, Object> body) { return exams.addQuestion(examId, body); }

    @PutMapping("/{examId}/questions/tree")
    public List<Map<String, Object>> replaceQuestionTree(@PathVariable UUID examId, @RequestBody Map<String, Object> body) {
        try {
            return exams.replaceQuestionTree(examId, body);
        } catch (IllegalStateException exception) {
            if ("QUESTION_TREE_LOCKED_AFTER_ASSIGNMENT".equals(exception.getMessage())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Không thể đổi cấu trúc câu hỏi vì kỳ thi đã có bài được phân công chấm.");
            }
            throw exception;
        }
    }

    @PostMapping("/{examId}/rubrics")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> rubric(@PathVariable UUID examId, @RequestBody Map<String, Object> body) { return exams.createRubric(examId, body); }

    @GetMapping("/{examId}/rubrics") public List<Map<String, Object>> rubrics(@PathVariable UUID examId) { return exams.rubrics(examId); }

    @GetMapping("/{examId}/rubrics/{rubricId}/file")
    public Map<String, Object> rubricPdf(@PathVariable UUID examId, @PathVariable UUID rubricId) {
        return exams.rubricPdf(examId, rubricId);
    }

    @PostMapping("/{examId}/rubrics/upload")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> initiateRubricUpload(@PathVariable UUID examId, @RequestBody Map<String, Object> body) { return exams.initiateRubricUpload(examId, body); }

    @PostMapping("/{examId}/rubrics/{rubricId}/complete")
    public Map<String, Object> completeRubricUpload(@PathVariable UUID examId, @PathVariable UUID rubricId) { return exams.completeRubricUpload(examId, rubricId); }

    @PostMapping("/{examId}/rubrics/{rubricId}/activate")
    public Map<String, Object> activateRubric(@PathVariable UUID examId, @PathVariable UUID rubricId, @RequestBody Map<String, Object> body) { return exams.activateRubric(examId, rubricId, body); }
}
