package vn.edu.examgrading.identity.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Office-only projection used to join a candidate row to its submission status. */
@RestController
@RequestMapping("/api/v1/exams/{examId}/submission-bindings")
@PreAuthorize("hasRole('EXAM_OFFICE')")
public class SubmissionBindingQueryController {
    private final JdbcTemplate jdbc;
    public SubmissionBindingQueryController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Map<String, Object> list(@PathVariable UUID examId) {
        List<Map<String, Object>> items = jdbc.queryForList("""
            select submission_id as "submissionId", candidate_id as "candidateId", status
            from submission_binding where exam_id = ? and status = 'ACTIVE'
            """, examId);
        return Map.of("items", items);
    }
}
