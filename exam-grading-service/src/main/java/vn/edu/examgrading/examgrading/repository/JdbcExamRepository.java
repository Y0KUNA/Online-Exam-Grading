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
    private static final String EXAM_PROJECTION = "exam_id as \"examId\",exam_code as \"examCode\",name,subject,exam_time as \"examTime\",grade_level as \"gradeLevel\",template_id as \"templateId\",scale,cross_grading_enabled as \"crossGradingEnabled\",grader_count as \"graderCount\",difference_threshold as \"differenceThreshold\",status";
    private final JdbcTemplate jdbc;

    public JdbcExamRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> findAll() { return jdbc.queryForList("select " + EXAM_PROJECTION + " from exam order by created_at desc"); }
    public Map<String, Object> findById(UUID examId) { return jdbc.queryForMap("select " + EXAM_PROJECTION + " from exam where exam_id=?", examId); }
    public void insert(UUID id, String code, String name, String subject, OffsetDateTime examTime, String gradeLevel, UUID templateId, BigDecimal scale, boolean crossGradingEnabled, int graderCount, BigDecimal differenceThreshold) { jdbc.update("insert into exam(exam_id,exam_code,name,subject,exam_time,grade_level,template_id,scale,cross_grading_enabled,grader_count,difference_threshold,status) values (?,?,?,?,?,?,?,?,?,?,?, 'DRAFT')", id, code, name, subject, examTime, gradeLevel, templateId, scale, crossGradingEnabled, graderCount, differenceThreshold); }
    public boolean setTemplate(UUID examId, UUID templateId) { return jdbc.update("update exam set template_id=?,updated_at=now() where exam_id=?", templateId, examId) == 1; }
    public List<Map<String, Object>> findQuestions(UUID examId) { return jdbc.queryForList("select question_id as \"questionId\",parent_question_id as \"parentQuestionId\",item_code as \"itemCode\",order_no as \"orderNo\",content,max_score as \"maxScore\",required from exam_question where exam_id=? order by order_no", examId); }
    public void insertQuestion(UUID id, UUID examId, UUID parentQuestionId, String itemCode, int orderNo, String content, BigDecimal maxScore, boolean required) { jdbc.update("insert into exam_question(question_id,exam_id,parent_question_id,item_code,order_no,content,max_score,required) values(?,?,?,?,?,?,?,?)", id, examId, parentQuestionId, itemCode, orderNo, content, maxScore, required); }
    public boolean questionBelongsToExam(UUID questionId, UUID examId) { return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from exam_question where question_id=? and exam_id=?)", Boolean.class, questionId, examId)); }
    public void deleteQuestions(UUID examId) { jdbc.update("delete from exam_question where exam_id=?", examId); }
    public void updateQuestionMaxScore(UUID questionId, BigDecimal maxScore) { jdbc.update("update exam_question set max_score=? where question_id=?", maxScore, questionId); }
    public int assignmentCount(UUID examId) { return jdbc.queryForObject("select count(*) from grading_assignment where exam_id=?", Integer.class, examId); }
    public void insertRubric(UUID id, UUID examId, String content) { jdbc.update("insert into rubric(rubric_id,exam_id,content) values(?,?,cast(? as jsonb))", id, examId, content); }
    public void insertRubricUpload(UUID id, UUID examId, String fileName, long fileSizeBytes, String contentType, String objectKey, OffsetDateTime expiresAt) {
        jdbc.update("insert into rubric(rubric_id,exam_id,content,status,file_name,file_size_bytes,content_type,object_key,upload_expires_at) values(?,?,cast('{}' as jsonb),'UPLOADING',?,?,?,?,?)",
                id, examId, fileName, fileSizeBytes, contentType, objectKey, expiresAt);
    }
    public boolean completeRubricUpload(UUID rubricId, UUID examId) {
        return jdbc.update("update rubric set status='DRAFT',upload_expires_at=null where rubric_id=? and exam_id=? and status='UPLOADING'", rubricId, examId) == 1;
    }
    public Map<String, Object> findRubric(UUID rubricId, UUID examId) {
        return jdbc.queryForMap("select rubric_id as \"rubricId\",exam_id as \"examId\",status,file_name as \"fileName\",file_size_bytes as \"fileSizeBytes\",content_type as \"contentType\",object_key as \"objectKey\",upload_expires_at as \"uploadExpiresAt\" from rubric where rubric_id=? and exam_id=?", rubricId, examId);
    }
    public List<Map<String, Object>> findRubrics(UUID examId) {
        return jdbc.queryForList("select rubric_id as \"rubricId\",status,file_name as \"fileName\",file_size_bytes as \"fileSizeBytes\",content_type as \"contentType\",created_at as \"createdAt\" from rubric where exam_id=? order by created_at desc", examId);
    }
    public void deactivateRubrics(UUID examId) { jdbc.update("update rubric set status='DRAFT' where exam_id=?", examId); }
    public void activateRubric(UUID rubricId, UUID examId) { jdbc.update("update rubric set status='ACTIVE' where rubric_id=? and exam_id=?", rubricId, examId); }
}
