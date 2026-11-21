package vn.edu.examgrading.examgrading.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.examgrading.examgrading.dto.BulkAssignmentRequest;

/** Owns local assignment and grading-attempt lifecycle data. */
@Service
public class AssignmentService {
    private final JdbcTemplate jdbc;
    public AssignmentService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Map<String, Object> bulkAssign(UUID examId, BulkAssignmentRequest request) {
        List<UUID> submissions = request == null || request.submissionIds() == null ? List.of() : request.submissionIds();
        List<UUID> graders = request == null || request.graderIds() == null ? List.of() : request.graderIds();
        if (submissions.isEmpty() || graders.isEmpty()) throw new IllegalArgumentException("submissionIds and graderIds are required");
        if (new HashSet<>(submissions).size() != submissions.size() || new HashSet<>(graders).size() != graders.size()) throw new IllegalArgumentException("submissionIds and graderIds must not contain duplicates");
        Map<String, Object> exam = jdbc.queryForMap("select cross_grading_enabled as \"crossGradingEnabled\",grader_count as \"graderCount\" from exam where exam_id=?", examId);
        int requiredGraders = ((Number) exam.get("graderCount")).intValue();
        boolean cross = Boolean.TRUE.equals(exam.get("crossGradingEnabled"));
        if ((cross && graders.size() != requiredGraders) || (!cross && graders.size() != 1)) throw new IllegalArgumentException("GRADER_COUNT_MISMATCH");

        List<Map<String, Object>> created = new ArrayList<>();
        for (UUID submissionId : submissions) for (UUID graderId : graders) {
            UUID assignmentId = UUID.randomUUID();
            jdbc.update("insert into grading_assignment(assignment_id,exam_id,submission_id,grader_id,status) values(?,?,?,?, 'ASSIGNED')", assignmentId, examId, submissionId, graderId);
            created.add(Map.of("assignmentId", assignmentId, "submissionId", submissionId, "graderId", graderId, "status", "ASSIGNED"));
        }
        return Map.of("created", created);
    }

    public Map<String, Object> listAssignments(UUID graderId) {
        return Map.of("items", jdbc.queryForList("select assignment_id as \"assignmentId\",exam_id as \"examId\",submission_id as \"submissionId\",grader_id as \"graderId\",status,assigned_at as \"assignedAt\" from grading_assignment where grader_id=? order by assigned_at desc", graderId));
    }

    @Transactional
    public Map<String, Object> startAttempt(UUID assignmentId) {
        List<Map<String, Object>> existing = jdbc.queryForList("select attempt_id as \"attemptId\",assignment_id as \"assignmentId\",status from grading_attempt where assignment_id=? and status='DRAFT'", assignmentId);
        if (!existing.isEmpty()) return existing.getFirst();
        UUID attemptId = UUID.randomUUID();
        if (jdbc.update("insert into grading_attempt(attempt_id,assignment_id,status) select ?,assignment_id,'DRAFT' from grading_assignment where assignment_id=?", attemptId, assignmentId) == 0) throw new IllegalArgumentException("assignment not found");
        jdbc.update("update grading_assignment set status='IN_PROGRESS' where assignment_id=? and status='ASSIGNED'", assignmentId);
        return Map.of("attemptId", attemptId, "assignmentId", assignmentId, "status", "DRAFT");
    }
}
