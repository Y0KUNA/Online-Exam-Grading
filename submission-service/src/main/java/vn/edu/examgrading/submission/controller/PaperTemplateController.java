package vn.edu.examgrading.submission.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Stores immutable PDF paper templates and their normalized redaction rectangles. */
@RestController
@RequestMapping("/api/v1/paper-templates")
public class PaperTemplateController {
    private static final long MAX_TEMPLATE_BYTES = 20L * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PaperTemplateController(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("select template_id as \"templateId\", name, description, page_width as \"pageWidth\", page_height as \"pageHeight\", jsonb_array_length(mask) as \"maskCount\", active, created_at as \"createdAt\" from paper_template where active=true order by created_at desc");
    }

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestPart("metadata") String metadata, @RequestPart("file") MultipartFile file) throws Exception {
        if (file.isEmpty() || file.getSize() > MAX_TEMPLATE_BYTES || !"application/pdf".equalsIgnoreCase(file.getContentType()))
            throw new IllegalArgumentException("Tệp mẫu phải là PDF và không vượt quá 20 MB.");
        Map<String, Object> data = json.readValue(metadata, new TypeReference<>() {});
        String name = string(data, "name");
        List<Map<String, Object>> masks = json.convertValue(data.get("masks"), new TypeReference<>() {});
        if (masks == null || masks.isEmpty()) throw new IllegalArgumentException("Cần đánh dấu ít nhất một vùng che.");
        for (Map<String, Object> mask : masks) validateMask(mask);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into paper_template(template_id,name,description,page_width,page_height,mask,source_pdf,source_file_name,active) values(?,?,?,?,?,cast(? as jsonb),?,?,true)",
                id, name, optional(data, "description"), number(data, "pageWidth"), number(data, "pageHeight"), json.writeValueAsString(masks), file.getBytes(), file.getOriginalFilename());
        return jdbc.queryForMap("select template_id as \"templateId\", name, description, page_width as \"pageWidth\", page_height as \"pageHeight\", jsonb_array_length(mask) as \"maskCount\", active, created_at as \"createdAt\" from paper_template where template_id=?", id);
    }

    @DeleteMapping("/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID templateId) {
        int updated = jdbc.update("delete from paper_template where template_id=? and not exists (select 1 from submission where template_id=paper_template.template_id)", templateId);
        if (updated == 0) throw new IllegalArgumentException("Mẫu không tồn tại hoặc đã được dùng cho bài thi nên không thể xóa.");
    }

    private static void validateMask(Map<String, Object> mask) {
        for (String key : List.of("x", "y", "width", "height")) {
            BigDecimal value = number(mask, key);
            if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("Tọa độ vùng che không hợp lệ.");
        }
        if (number(mask, "x").add(number(mask, "width")).compareTo(BigDecimal.ONE) > 0 || number(mask, "y").add(number(mask, "height")).compareTo(BigDecimal.ONE) > 0)
            throw new IllegalArgumentException("Vùng che vượt quá trang PDF.");
    }
    private static String string(Map<String, Object> data, String key) { String value = optional(data, key); if (value == null) throw new IllegalArgumentException(key + " là bắt buộc."); return value; }
    private static String optional(Map<String, Object> data, String key) { Object value = data.get(key); return value == null || value.toString().isBlank() ? null : value.toString().trim(); }
    private static BigDecimal number(Map<String, Object> data, String key) { try { return new BigDecimal(String.valueOf(data.get(key))); } catch (RuntimeException e) { throw new IllegalArgumentException(key + " không hợp lệ."); } }
}
