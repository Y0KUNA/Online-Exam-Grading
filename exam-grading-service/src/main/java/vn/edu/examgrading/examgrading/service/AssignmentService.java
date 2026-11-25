package vn.edu.examgrading.examgrading.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.examgrading.examgrading.dto.BulkAssignmentRequest;
import vn.edu.examgrading.examgrading.dto.SaveQuestionScoreRequest;
import vn.edu.examgrading.examgrading.dto.SaveScoresRequest;
import vn.edu.examgrading.examgrading.client.SubmissionClient;

/** Owns local assignment and grading-attempt lifecycle data. */
@Service
public class AssignmentService {
    private final JdbcTemplate jdbc;
    private final SubmissionClient submissions;
    public AssignmentService(JdbcTemplate jdbc, SubmissionClient submissions) { this.jdbc = jdbc; this.submissions = submissions; }

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
        // Submission owns the status rendered by the Exam Office. The internal
        // transition is idempotent, so an assignment retry safely keeps GRADING.
        for (UUID submissionId : submissions) {
            this.submissions.changeStatus(submissionId, "GRADING", 0);
        }
        return Map.of("created", created);
    }

    /**
     * Replace-file saga step (§4.4): discards DRAFT attempts and cancels open
     * assignments for a submission. Blocked by ATTEMPTS_EXIST when any attempt is
     * CONFIRMED. Naturally idempotent — a replay finds no open work.
     */
    @Transactional
    public Map<String, Object> discardOpenWork(UUID submissionId) {
        Integer confirmed = jdbc.queryForObject("""
            select count(*) from grading_attempt t
            join grading_assignment ga on ga.assignment_id = t.assignment_id
            where ga.submission_id = ? and t.status = 'CONFIRMED'
            """, Integer.class, submissionId);
        if (confirmed != null && confirmed > 0) {
            throw new IllegalStateException("ATTEMPTS_EXIST");
        }
        int discarded = jdbc.update("""
            update grading_attempt set status='DISCARDED'
            where status='DRAFT' and assignment_id in (
                select assignment_id from grading_assignment where submission_id=?)
            """, submissionId);
        int cancelled = jdbc.update("""
            update grading_assignment set status='CANCELLED'
            where submission_id=? and status in ('ASSIGNED','IN_PROGRESS')
            """, submissionId);
        return Map.of("discardedAttempts", discarded, "cancelledAssignments", cancelled);
    }

    public Map<String, Object> listAssignments(UUID graderId) {
        return Map.of("items", jdbc.queryForList("select assignment_id as \"assignmentId\",exam_id as \"examId\",submission_id as \"submissionId\",grader_id as \"graderId\",status,assigned_at as \"assignedAt\" from grading_assignment where grader_id=? order by assigned_at desc", graderId));
    }

    @Transactional
    public Map<String, Object> startAttempt(UUID assignmentId) {
        List<Map<String, Object>> existing = jdbc.queryForList("select attempt_id as \"attemptId\",assignment_id as \"assignmentId\",status from grading_attempt where assignment_id=? order by confirmed_at desc nulls first", assignmentId);
        if (!existing.isEmpty()) return existing.getFirst();
        UUID attemptId = UUID.randomUUID();
        if (jdbc.update("insert into grading_attempt(attempt_id,assignment_id,status) select ?,assignment_id,'DRAFT' from grading_assignment where assignment_id=?", attemptId, assignmentId) == 0) throw new IllegalArgumentException("assignment not found");
        jdbc.update("update grading_assignment set status='IN_PROGRESS' where assignment_id=? and status='ASSIGNED'", assignmentId);
        return Map.of("attemptId", attemptId, "assignmentId", assignmentId, "status", "DRAFT");
    }

    public Map<String, Object> attemptDetail(UUID attemptId) {
        Map<String, Object> attempt = jdbc.queryForMap("""
            select ga.attempt_id as \"attemptId\", ga.assignment_id as \"assignmentId\", a.exam_id as \"examId\",
                   ga.status, ga.total_score as \"totalScore\", ga.confirmed_at as \"confirmedAt\"
            from grading_attempt ga join grading_assignment a on a.assignment_id=ga.assignment_id
            where ga.attempt_id=?
            """, attemptId);
        List<Map<String, Object>> scores = jdbc.queryForList("""
            select qs.question_id as \"questionId\", eq.order_no as \"orderNo\", eq.max_score as \"maxScore\",
                   qs.score, qs.note, qs.needs_review as \"needsReview\"
            from question_score qs join exam_question eq on eq.question_id=qs.question_id
            where qs.attempt_id=? order by eq.order_no
            """, attemptId);
        return Map.of("attempt", attempt, "scores", scores);
    }

    public Map<String, Object> attemptContext(UUID attemptId) {
        Map<String, Object> attempt = jdbc.queryForMap("""
            select ga.attempt_id as \"attemptId\", a.submission_id as \"submissionId\", a.exam_id as \"examId\", ga.status
            from grading_attempt ga join grading_assignment a on a.assignment_id=ga.assignment_id where ga.attempt_id=?
            """, attemptId);
        UUID examId = (UUID) attempt.get("examId");
        List<Map<String, Object>> questions = jdbc.queryForList("select question_id as \"questionId\",order_no as \"orderNo\",content,max_score as \"maxScore\",required from exam_question where exam_id=? order by order_no", examId);
        List<Map<String, Object>> rubrics = jdbc.queryForList("select rubric_id as \"rubricId\",version,content::text as content from rubric where exam_id=? and status='ACTIVE' order by version desc limit 1", examId);
        Map<String, Object> file = submissions.anonymizedAccess((UUID) attempt.get("submissionId"));
        return Map.of("attemptId", attemptId, "submissionId", attempt.get("submissionId"), "status", attempt.get("status"),
            "anonymizedFileAccessUrl", file.get("url"), "fileAccessExpiresAt", file.get("expiresAt"), "questions", questions,
            "rubric", rubrics.isEmpty() ? Map.of() : rubrics.getFirst());
    }

    @Transactional
    public Map<String, Object> saveScores(UUID attemptId, SaveScoresRequest request) {
        Map<String, Object> attempt = attemptForUpdate(attemptId);
        requireDraft(attempt);
        List<SaveScoresRequest.QuestionScore> scores = request == null || request.scores() == null ? List.of() : request.scores();
        if (scores.isEmpty()) throw new IllegalArgumentException("scores are required");
        if (scores.stream().map(SaveScoresRequest.QuestionScore::questionId).anyMatch(java.util.Objects::isNull)
            || new HashSet<>(scores.stream().map(SaveScoresRequest.QuestionScore::questionId).toList()).size() != scores.size()) {
            throw new IllegalArgumentException("questionIds must be present and unique");
        }
        UUID examId = (UUID) attempt.get("examId");
        Map<UUID, BigDecimal> maximums = new java.util.HashMap<>();
        jdbc.query("select question_id, max_score from exam_question where exam_id=?", rs -> {
            maximums.put((UUID) rs.getObject("question_id"), rs.getBigDecimal("max_score"));
        }, examId);
        BigDecimal total = BigDecimal.ZERO;
        for (SaveScoresRequest.QuestionScore item : scores) {
            BigDecimal maximum = maximums.get(item.questionId());
            if (maximum == null) throw new IllegalArgumentException("question does not belong to this exam");
            if (item.score() == null || item.score().signum() < 0 || item.score().compareTo(maximum) > 0) {
                throw new IllegalArgumentException("score must be between 0 and the question maximum");
            }
            total = total.add(item.score());
            jdbc.update("""
                insert into question_score(attempt_id,question_id,score,note,needs_review) values(?,?,?,?,?)
                on conflict(attempt_id,question_id) do update set score=excluded.score,note=excluded.note,needs_review=excluded.needs_review
                """, attemptId, item.questionId(), item.score(), blankToNull(item.note()), Boolean.TRUE.equals(item.needsReview()));
        }
        total = jdbc.queryForObject("select coalesce(sum(score), 0) from question_score where attempt_id=?", BigDecimal.class, attemptId);
        jdbc.update("update grading_attempt set total_score=? where attempt_id=?", total, attemptId);
        return Map.of("attemptId", attemptId, "status", "DRAFT", "totalScore", total, "savedCount", scores.size());
    }

    @Transactional
    public Map<String, Object> saveQuestionScore(UUID attemptId, UUID questionId, SaveQuestionScoreRequest request) {
        if (request == null) throw new IllegalArgumentException("score is required");
        Map<String, Object> result = saveScores(attemptId, new SaveScoresRequest(List.of(
            new SaveScoresRequest.QuestionScore(questionId, request.score(), request.note(), request.needsReview()))));
        return Map.of("questionId", questionId, "score", request.score(), "note", blankToNull(request.note()),
            "needsReview", Boolean.TRUE.equals(request.needsReview()), "totalScore", result.get("totalScore"));
    }

    @Transactional
    public Map<String, Object> confirmAttempt(UUID attemptId) {
        Map<String, Object> attempt = attemptForUpdate(attemptId);
        if ("CONFIRMED".equals(attempt.get("status"))) {
            BigDecimal total = jdbc.queryForObject("select total_score from grading_attempt where attempt_id=?", BigDecimal.class, attemptId);
            return Map.of("attemptId", attemptId, "status", "CONFIRMED", "totalScore", total);
        }
        requireDraft(attempt);
        UUID examId = (UUID) attempt.get("examId");
        int questionCount = jdbc.queryForObject("select count(*) from exam_question where exam_id=?", Integer.class, examId);
        int scoredCount = jdbc.queryForObject("select count(*) from question_score where attempt_id=?", Integer.class, attemptId);
        if (questionCount == 0 || scoredCount != questionCount) throw new IllegalArgumentException("ALL_QUESTIONS_MUST_BE_SCORED");
        BigDecimal total = jdbc.queryForObject("select coalesce(sum(score), 0) from question_score where attempt_id=?", BigDecimal.class, attemptId);
        jdbc.update("update grading_attempt set total_score=?,status='CONFIRMED',confirmed_at=now() where attempt_id=?", total, attemptId);
        jdbc.update("update grading_assignment set status='COMPLETED' where assignment_id=?", attempt.get("assignmentId"));
        UUID submissionId = (UUID) attempt.get("submissionId");
        Integer remaining = jdbc.queryForObject("""
            select count(*) from grading_assignment
            where submission_id=? and status <> 'COMPLETED'
            """, Integer.class, submissionId);
        // A cross-graded paper becomes complete only after every assigned grader
        // confirms. This updates the submission read model used by Exam Office.
        if (remaining != null && remaining == 0) {
            // Existing assignments created before the GRADING transition was
            // introduced can still be READY_FOR_GRADING. Bring those records
            // through the valid state transition before marking them completed.
            submissions.changeStatus(submissionId, "GRADING", 0);
            submissions.changeStatus(submissionId, "COMPLETED", 100);
        }
        return Map.of("attemptId", attemptId, "status", "CONFIRMED", "totalScore", total);
    }

    private Map<String, Object> attemptForUpdate(UUID attemptId) {
        List<Map<String, Object>> attempts = jdbc.queryForList("""
            select ga.attempt_id as \"attemptId\", ga.assignment_id as \"assignmentId\", ga.status,
                   a.exam_id as \"examId\", a.submission_id as \"submissionId\" from grading_attempt ga
            join grading_assignment a on a.assignment_id=ga.assignment_id where ga.attempt_id=? for update
            """, attemptId);
        if (attempts.isEmpty()) throw new IllegalArgumentException("attempt not found");
        return attempts.getFirst();
    }

    private static void requireDraft(Map<String, Object> attempt) {
        if (!"DRAFT".equals(attempt.get("status"))) throw new IllegalArgumentException("ATTEMPT_NOT_EDITABLE");
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
