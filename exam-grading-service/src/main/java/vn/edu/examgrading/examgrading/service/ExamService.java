package vn.edu.examgrading.examgrading.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.examgrading.examgrading.dto.CreateExamRequest;
import vn.edu.examgrading.examgrading.repository.JdbcExamRepository;

/** Applies exam business rules before asking the repository to persist data. */
@Service
public class ExamService {
    private final JdbcExamRepository exams;

    public ExamService(JdbcExamRepository exams) { this.exams = exams; }
    public List<Map<String, Object>> list() { return exams.findAll(); }
    public Map<String, Object> detail(UUID examId) { return exams.findById(examId); }

    @Transactional
    public Map<String, Object> create(CreateExamRequest request) {
        String name = required(request.name(), "name");
        String subject = required(request.subject(), "subject");
        boolean crossGrading = request.crossGradingEnabled() != null && request.crossGradingEnabled();
        int graderCount = request.graderCount() == null ? 1 : request.graderCount();
        BigDecimal threshold = request.differenceThreshold() == null ? BigDecimal.ZERO : request.differenceThreshold();
        BigDecimal scale = request.scale() == null ? BigDecimal.TEN : request.scale();
        if (graderCount < 1 || (crossGrading && graderCount < 2) || threshold.signum() < 0 || scale.signum() <= 0) throw new IllegalArgumentException("Invalid grading configuration");

        UUID id = UUID.randomUUID();
        exams.insert(id, "EX-" + id.toString().substring(0, 8).toUpperCase(), name, subject, parseExamTime(request.examTime()), nullable(request.gradeLevel()), scale, crossGrading, graderCount, threshold);
        return exams.findById(id);
    }

    public List<Map<String, Object>> questions(UUID examId) { return exams.findQuestions(examId); }

    @Transactional
    public Map<String, Object> addQuestion(UUID examId, Map<String, Object> body) {
        BigDecimal maxScore = decimal(body, "maxScore", null);
        int orderNo = integer(body, "orderNo", 0);
        if (maxScore == null || maxScore.signum() < 0) throw new IllegalArgumentException("invalid maxScore");
        if (orderNo < 1) throw new IllegalArgumentException("orderNo is required");
        UUID id = UUID.randomUUID();
        exams.insertQuestion(id, examId, orderNo, required(nullable(body.get("content")), "content"), maxScore, bool(body, "required", true));
        return Map.of("questionId", id, "examId", examId, "orderNo", orderNo, "maxScore", maxScore);
    }

    @Transactional
    public Map<String, Object> createRubric(UUID examId, Map<String, Object> body) {
        UUID id = UUID.randomUUID();
        exams.insertRubric(id, examId, String.valueOf(body.getOrDefault("content", "{}")));
        return Map.of("rubricId", id, "status", "DRAFT");
    }

    @Transactional
    public Map<String, Object> activateRubric(UUID examId, UUID rubricId, Map<String, Object> body) {
        if (!bool(body, "confirmed", false)) throw new IllegalArgumentException("CONFIRMATION_REQUIRED");
        exams.deactivateRubrics(examId);
        exams.activateRubric(rubricId, examId);
        return Map.of("rubricId", rubricId, "status", "ACTIVE");
    }

    private static OffsetDateTime parseExamTime(String value) {
        String normalized = nullable(value);
        if (normalized == null) return null;
        try { return OffsetDateTime.parse(normalized.endsWith("Z") || normalized.matches(".*[+-]\\d{2}:\\d{2}$") ? normalized : normalized + "+07:00"); }
        catch (DateTimeParseException exception) { throw new IllegalArgumentException("examTime must be an ISO-8601 date-time"); }
    }
    private static String required(String value, String key) { if (value == null) throw new IllegalArgumentException(key + " is required"); return value; }
    private static String nullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String nullable(Object value) { return value == null ? null : nullable(value.toString()); }
    private static int integer(Map<String, Object> body, String key, int fallback) { Object value = body.get(key); return value == null ? fallback : Integer.parseInt(value.toString()); }
    private static boolean bool(Map<String, Object> body, String key, boolean fallback) { Object value = body.get(key); return value == null ? fallback : Boolean.parseBoolean(value.toString()); }
    private static BigDecimal decimal(Map<String, Object> body, String key, BigDecimal fallback) { Object value = body.get(key); return value == null || value.toString().isBlank() ? fallback : new BigDecimal(value.toString()); }
}
