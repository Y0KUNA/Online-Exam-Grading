package vn.edu.examgrading.examgrading.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import vn.edu.examgrading.examgrading.dto.CreateExamRequest;
import vn.edu.examgrading.examgrading.repository.JdbcExamRepository;
import vn.edu.examgrading.examgrading.storage.RubricFileStorage;
import vn.edu.examgrading.examgrading.storage.RubricStorageProperties;

/** Applies exam business rules before asking the repository to persist data. */
@Service
public class ExamService {
    private final JdbcExamRepository exams;
    private final RubricFileStorage rubricFiles;
    private final RubricStorageProperties rubricStorage;

    @Autowired
    public ExamService(JdbcExamRepository exams, RubricFileStorage rubricFiles, RubricStorageProperties rubricStorage) {
        this.exams = exams;
        this.rubricFiles = rubricFiles;
        this.rubricStorage = rubricStorage;
    }
    /** Kept for focused legacy unit tests that exercise only exam metadata rules. */
    public ExamService(JdbcExamRepository exams) { this(exams, null, null); }
    public List<Map<String, Object>> list() { return exams.findAll(); }
    public Map<String, Object> detail(UUID examId) { return exams.findById(examId); }

    @Transactional
    public Map<String, Object> create(CreateExamRequest request) {
        String name = required(request.name(), "name");
        String subject = required(request.subject(), "subject");
        if (request.templateId() == null) throw new IllegalArgumentException("templateId is required");
        boolean crossGrading = request.crossGradingEnabled() != null && request.crossGradingEnabled();
        int graderCount = request.graderCount() == null ? 1 : request.graderCount();
        BigDecimal threshold = request.differenceThreshold() == null ? BigDecimal.ZERO : request.differenceThreshold();
        BigDecimal scale = request.scale() == null ? BigDecimal.TEN : request.scale();
        if (graderCount < 1 || (crossGrading && graderCount < 2) || threshold.signum() < 0 || scale.signum() <= 0) throw new IllegalArgumentException("Invalid grading configuration");

        UUID id = UUID.randomUUID();
        exams.insert(id, "EX-" + id.toString().substring(0, 8).toUpperCase(), name, subject, parseExamTime(request.examTime()), nullable(request.gradeLevel()), request.templateId(), scale, crossGrading, graderCount, threshold);
        return exams.findById(id);
    }

    public List<Map<String, Object>> questions(UUID examId) { return QuestionTree.fromRows(exams.findQuestions(examId)); }
    public List<Map<String, Object>> rubrics(UUID examId) { return exams.findRubrics(examId); }

    /** Supplies a short-lived signed URL rather than exposing a MinIO object key. */
    public Map<String, Object> rubricPdf(UUID examId, UUID rubricId) {
        Map<String, Object> rubric = exams.findRubric(rubricId, examId);
        String objectKey = nullable(rubric.get("objectKey"));
        if (objectKey == null || "UPLOADING".equals(rubric.get("status"))) {
            throw new IllegalArgumentException("Rubric PDF chưa sẵn sàng để xem");
        }
        RubricFileStorage.AccessTicket ticket = rubricFiles.presignGet(objectKey);
        return Map.of("rubricId", rubricId, "fileName", rubric.get("fileName"),
                "url", ticket.url(), "expiresAt", ticket.expiresAt());
    }

    @Transactional
    public Map<String, Object> setTemplate(UUID examId, UUID templateId) {
        if (templateId == null) throw new IllegalArgumentException("templateId is required");
        if (!exams.setTemplate(examId, templateId)) throw new IllegalArgumentException("exam not found");
        return exams.findById(examId);
    }

    @Transactional
    public Map<String, Object> addQuestion(UUID examId, Map<String, Object> body) {
        BigDecimal maxScore = decimal(body, "maxScore", null);
        int orderNo = integer(body, "orderNo", 0);
        if (maxScore == null || maxScore.signum() < 0) throw new IllegalArgumentException("invalid maxScore");
        if (orderNo < 1) throw new IllegalArgumentException("orderNo is required");
        UUID parentQuestionId = uuid(body.get("parentQuestionId"));
        if (parentQuestionId != null && !exams.questionBelongsToExam(parentQuestionId, examId)) throw new IllegalArgumentException("parentQuestionId does not belong to this exam");
        UUID id = UUID.randomUUID();
        String itemCode = nullable(body.get("itemCode"));
        if (itemCode == null) itemCode = parentQuestionId == null ? "C" + orderNo : "C" + orderNo + "a";
        exams.insertQuestion(id, examId, parentQuestionId, itemCode, orderNo, required(nullable(body.get("content")), "content"), maxScore, bool(body, "required", true));
        return Map.of("questionId", id, "examId", examId, "parentQuestionId", parentQuestionId == null ? "" : parentQuestionId, "itemCode", itemCode, "orderNo", orderNo, "maxScore", maxScore);
    }

    /** Replaces a draft exam's rubric tree. Only leaves carry an editable maximum score. */
    @Transactional
    public List<Map<String, Object>> replaceQuestionTree(UUID examId, Map<String, Object> body) {
        Object rawItems = body == null ? null : body.get("items");
        if (!(rawItems instanceof List<?> items) || items.isEmpty()) throw new IllegalArgumentException("items is required");
        if (exams.assignmentCount(examId) > 0) throw new IllegalStateException("QUESTION_TREE_LOCKED_AFTER_ASSIGNMENT");
        exams.deleteQuestions(examId);
        int[] order = {0};
        for (Object item : items) insertTreeItem(examId, null, map(item), order);
        return questions(examId);
    }

    private BigDecimal insertTreeItem(UUID examId, UUID parentId, Map<String, Object> item, int[] order) {
        String code = required(nullable(item.get("code")), "item code");
        String content = required(nullable(item.get("content")), "item content");
        List<?> children = item.get("children") instanceof List<?> list ? list : List.of();
        UUID id = UUID.randomUUID();
        int currentOrder = ++order[0];
        if (children.isEmpty()) {
            BigDecimal score = decimal(item, "maxScore", null);
            if (score == null || score.signum() < 0) throw new IllegalArgumentException("Leaf " + code + " must have a non-negative maxScore");
            exams.insertQuestion(id, examId, parentId, code, currentOrder, content, score, bool(item, "required", true));
            return score;
        }
        exams.insertQuestion(id, examId, parentId, code, currentOrder, content, BigDecimal.ZERO, bool(item, "required", true));
        BigDecimal total = BigDecimal.ZERO;
        for (Object child : children) total = total.add(insertTreeItem(examId, id, map(child), order));
        exams.updateQuestionMaxScore(id, total);
        return total;
    }

    @Transactional
    public Map<String, Object> createRubric(UUID examId, Map<String, Object> body) {
        UUID id = UUID.randomUUID();
        exams.insertRubric(id, examId, String.valueOf(body.getOrDefault("content", "{}")));
        return Map.of("rubricId", id, "status", "DRAFT");
    }

    /** Starts a browser-to-MinIO PDF upload through the Gateway; no rubric bytes hit this service. */
    @Transactional
    public Map<String, Object> initiateRubricUpload(UUID examId, Map<String, Object> body) {
        String fileName = required(nullable(body.get("fileName")), "fileName");
        String contentType = nullable(body.get("contentType"));
        long fileSize = longValue(body.get("fileSizeBytes"), "fileSizeBytes");
        if (!"application/pdf".equalsIgnoreCase(contentType)) throw new IllegalArgumentException("Rubric phải là file PDF");
        if (fileSize < 1 || fileSize > rubricStorage.maxPdfBytes()) throw new IllegalArgumentException("Kích thước rubric PDF không hợp lệ");

        UUID rubricId = UUID.randomUUID();
        String objectKey = "exams/" + examId + "/rubrics/" + rubricId + ".pdf";
        RubricFileStorage.UploadTicket ticket = rubricFiles.presignPost(objectKey, fileSize);
        exams.insertRubricUpload(rubricId, examId, fileName, fileSize, "application/pdf", objectKey,
                OffsetDateTime.ofInstant(ticket.expiresAt(), java.time.ZoneOffset.UTC));
        return Map.of("rubricId", rubricId, "status", "UPLOADING", "upload", Map.of("url", ticket.url(), "fields", ticket.fields()), "uploadExpiresAt", ticket.expiresAt());
    }

    @Transactional
    public Map<String, Object> completeRubricUpload(UUID examId, UUID rubricId) {
        Map<String, Object> rubric = exams.findRubric(rubricId, examId);
        if (!"UPLOADING".equals(rubric.get("status"))) return rubric;
        Object expiry = rubric.get("uploadExpiresAt");
        if (expiry instanceof OffsetDateTime time && time.isBefore(OffsetDateTime.now())) throw new IllegalStateException("RUBRIC_UPLOAD_EXPIRED");
        long declaredSize = ((Number) rubric.get("fileSizeBytes")).longValue();
        String objectKey = (String) rubric.get("objectKey");
        long actualSize = rubricFiles.size(objectKey).orElseThrow(() -> new IllegalStateException("RUBRIC_UPLOAD_NOT_FOUND"));
        if (actualSize != declaredSize) throw new IllegalStateException("RUBRIC_FILE_SIZE_MISMATCH");
        exams.completeRubricUpload(rubricId, examId);
        return exams.findRubric(rubricId, examId);
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
    private static long longValue(Object value, String key) { if (value == null) throw new IllegalArgumentException(key + " is required"); try { return Long.parseLong(value.toString()); } catch (NumberFormatException exception) { throw new IllegalArgumentException(key + " must be a number"); } }
    private static boolean bool(Map<String, Object> body, String key, boolean fallback) { Object value = body.get(key); return value == null ? fallback : Boolean.parseBoolean(value.toString()); }
    private static BigDecimal decimal(Map<String, Object> body, String key, BigDecimal fallback) { Object value = body.get(key); return value == null || value.toString().isBlank() ? fallback : new BigDecimal(value.toString()); }
    private static UUID uuid(Object value) { try { return value == null || value.toString().isBlank() ? null : UUID.fromString(value.toString()); } catch (IllegalArgumentException exception) { throw new IllegalArgumentException("parentQuestionId is invalid"); } }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Each rubric item must be an object"); return (Map<String, Object>) raw; }
}
