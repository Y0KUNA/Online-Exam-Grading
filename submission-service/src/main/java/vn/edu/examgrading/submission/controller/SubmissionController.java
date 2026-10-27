package vn.edu.examgrading.submission.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Read-only office projection. State changes remain available exclusively under /internal. */
@RestController
@RequestMapping("/api/v1")
public class SubmissionController {
    private final JdbcTemplate jdbc;
    public SubmissionController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping("/exams/{examId}/submissions") public List<Map<String,Object>> list(@PathVariable UUID examId) {
        return jdbc.queryForList("select submission_id as \"submissionId\",exam_id as \"examId\",template_id as \"templateId\",status,progress,current_file_version as \"currentFileVersion\",updated_at as \"updatedAt\" from submission where exam_id=? order by updated_at desc",examId);
    }
    @GetMapping("/submissions/{submissionId}") public Map<String,Object> detail(@PathVariable UUID submissionId) {
        return jdbc.queryForMap("select submission_id as \"submissionId\",exam_id as \"examId\",status,progress,version,updated_at as \"updatedAt\" from submission where submission_id=?",submissionId);
    }
}
