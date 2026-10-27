package vn.edu.examgrading.examgrading.repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns every SQL operation for the exam aggregate. */
@Repository
public class JdbcExamRepository {
    private static final String EXAM_PROJECTION = "exam_id as \"examId\",exam_code as \"examCode\",name,subject,exam_time as \"examTime\",grade_level as \"gradeLevel\",scale,cross_grading_enabled as \"crossGradingEnabled\",grader_count as \"graderCount\",difference_threshold as \"differenceThreshold\",status";
    private final JdbcTemplate jdbc;

    public JdbcExamRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> findAll() { return jdbc.queryForList("select " + EXAM_PROJECTION + " from exam order by created_at desc"); }
    public Map<String, Object> findById(UUID examId) { return jdbc.queryForMap("select " + EXAM_PROJECTION + " from exam where exam_id=?", examId); }
    public void insert(UUID id, String code, String name, String subject, OffsetDateTime examTime, String gradeLevel, BigDecimal scale, boolean crossGradingEnabled, int graderCount, BigDecimal differenceThreshold) { jdbc.update("insert into exam(exam_id,exam_code,name,subject,exam_time,grade_level,scale,cross_grading_enabled,grader_count,difference_threshold,status) values (?,?,?,?,?,?,?,?,?,?, 'DRAFT')", id, code, name, subject, examTime, gradeLevel, scale, crossGradingEnabled, graderCount, differenceThreshold); }
    public List<Map<String, Object>> findQuestions(UUID examId) { return jdbc.queryForList("select question_id as \"questionId\",order_no as \"orderNo\",content,max_score as \"maxScore\",required from exam_question where exam_id=? order by order_no", examId); }
    public void insertQuestion(UUID id, UUID examId, int orderNo, String content, BigDecimal maxScore, boolean required) { jdbc.update("insert into exam_question(question_id,exam_id,order_no,content,max_score,required) values(?,?,?,?,?,?)", id, examId, orderNo, content, maxScore, required); }
    public void insertRubric(UUID id, UUID examId, String content) { jdbc.update("insert into rubric(rubric_id,exam_id,content) values(?,?,cast(? as jsonb))", id, examId, content); }
    public void deactivateRubrics(UUID examId) { jdbc.update("update rubric set status='DRAFT' where exam_id=?", examId); }
    public void activateRubric(UUID rubricId, UUID examId) { jdbc.update("update rubric set status='ACTIVE' where rubric_id=? and exam_id=?", rubricId, examId); }
}
