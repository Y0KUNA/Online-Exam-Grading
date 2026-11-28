package vn.edu.examgrading.submission.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
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
import org.apache.pdfbox.pdmodel.PDDocument;

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
        return jdbc.queryForList("""
            select template_id as "templateId", name, description, page_width as "pageWidth", page_height as "pageHeight",
                   case jsonb_typeof(mask)
                     when 'array' then jsonb_array_length(mask)
                     else coalesce((mask->>'maskCount')::integer, 1)
                   end as "maskCount",
                   active, created_at as "createdAt"
            from paper_template where active=true order by created_at desc
            """);
    }

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestPart("metadata") String metadata, @RequestPart("file") MultipartFile file) throws Exception {
        if (file.isEmpty() || file.getSize() > MAX_TEMPLATE_BYTES || !"application/pdf".equalsIgnoreCase(file.getContentType()))
            throw new IllegalArgumentException("Tệp mẫu phải là PDF và không vượt quá 20 MB.");
        Map<String, Object> data;
        try { data = json.readValue(metadata, new TypeReference<>() {}); }
        catch (Exception exception) { throw new IllegalArgumentException("Thông tin mẫu giấy thi không phải JSON hợp lệ."); }
        String name = string(data, "name");
        // A selected region is now removed from the page instead of being painted
        // white or overwritten with a pseudonym. Accept `masks` as a migration
        // alias for templates created by older versions of the editor.
        List<Map<String, Object>> cuts = json.convertValue(data.containsKey("cuts") ? data.get("cuts") : data.get("masks"), new TypeReference<>() {});
        if (cuts == null || cuts.isEmpty()) throw new IllegalArgumentException("Cần đánh dấu ít nhất một vùng cắt bỏ.");
        byte[] sourcePdf = file.getBytes();
        PageInfo pageInfo = readPageInfo(sourcePdf);
        for (int index = 0; index < cuts.size(); index++) validateRegion(cuts.get(index), pageInfo.pageCount(), "vùng cắt bỏ " + (index + 1));
        List<Map<String, Object>> crops = json.convertValue(data.get("crops"), new TypeReference<>() {});
        if (crops == null || crops.isEmpty()) throw new IllegalArgumentException("Cần đánh dấu ít nhất một vùng cắt trang.");
        for (int index = 0; index < crops.size(); index++) validateCrop(crops.get(index), pageInfo.pageCount(), "vùng trang " + (index + 1));
        if (crops.stream().map(crop -> integer(crop, "order")).distinct().count() != crops.size()) {
            throw new IllegalArgumentException("Thứ tự các trang cắt không được trùng nhau.");
        }
        validateCutRegions(cuts, crops);
        Map<String, Object> normalizedMask = normalizedMask(cuts, crops, pageInfo.pageCount());
        UUID id = UUID.randomUUID();
        jdbc.update("insert into paper_template(template_id,name,description,page_width,page_height,mask,source_pdf,source_file_name,active) values(?,?,?,?,?,cast(? as jsonb),?,?,true)",
                id, name, optional(data, "description"), pageInfo.width(), pageInfo.height(), json.writeValueAsString(normalizedMask), sourcePdf, file.getOriginalFilename());
        return jdbc.queryForMap("select template_id as \"templateId\", name, description, page_width as \"pageWidth\", page_height as \"pageHeight\", (mask->>'maskCount')::integer as \"maskCount\", active, created_at as \"createdAt\" from paper_template where template_id=?", id);
    }

    @DeleteMapping("/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID templateId) {
        int updated = jdbc.update("delete from paper_template where template_id=? and not exists (select 1 from submission where template_id=paper_template.template_id)", templateId);
        if (updated == 0) throw new IllegalArgumentException("Mẫu không tồn tại hoặc đã được dùng cho bài thi nên không thể xóa.");
    }

    private static void validateRegion(Map<String, Object> region, int pageCount, String label) {
        try {
            int page = integer(region, "page");
            if (page < 1 || page > pageCount) throw new IllegalArgumentException("chọn trang " + page + ", nhưng PDF chỉ có " + pageCount + " trang.");
            for (String key : List.of("x", "y", "width", "height")) {
                BigDecimal value = number(region, key);
                if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("tọa độ " + key + " phải nằm trong khoảng 0 đến 1.");
            }
            if (number(region, "width").signum() <= 0 || number(region, "height").signum() <= 0) throw new IllegalArgumentException("chiều rộng và chiều cao phải lớn hơn 0.");
            if (number(region, "x").add(number(region, "width")).compareTo(BigDecimal.ONE) > 0 || number(region, "y").add(number(region, "height")).compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("vượt ra ngoài mép trang PDF.");
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(capitalize(label) + ": " + exception.getMessage());
        }
    }

    private static void validateCrop(Map<String, Object> crop, int pageCount, String label) {
        validateRegion(crop, pageCount, label);
        if (integer(crop, "order") < 1) throw new IllegalArgumentException(capitalize(label) + ": thứ tự trang xuất phải từ 1 trở lên.");
    }

    private static void validateCutRegions(List<Map<String, Object>> cuts, List<Map<String, Object>> crops) {
        final BigDecimal tolerance = new BigDecimal("0.01");
        for (int index = 0; index < cuts.size(); index++) {
            Map<String, Object> cut = cuts.get(index);
            String label = "Vùng cắt bỏ " + (index + 1);
            BigDecimal y = number(cut, "y");
            BigDecimal bottom = y.add(number(cut, "height"));
            if (y.compareTo(tolerance) > 0 || bottom.compareTo(BigDecimal.ONE.subtract(tolerance)) < 0) {
                throw new IllegalArgumentException(label + " phải kéo dài từ mép trên đến mép dưới trang PDF.");
            }
            int page = integer(cut, "page");
            BigDecimal left = number(cut, "x");
            BigDecimal right = left.add(number(cut, "width"));
            boolean touchesCropEdge = crops.stream().filter(crop -> integer(crop, "page") == page).anyMatch(crop -> {
                BigDecimal cropLeft = number(crop, "x");
                BigDecimal cropRight = cropLeft.add(number(crop, "width"));
                boolean overlapsCrop = left.compareTo(cropRight.add(tolerance)) < 0
                    && right.compareTo(cropLeft.subtract(tolerance)) > 0;
                boolean touchesLeft = left.compareTo(cropLeft.add(tolerance)) <= 0;
                boolean touchesRight = right.compareTo(cropRight.subtract(tolerance)) >= 0;
                return overlapsCrop && (touchesLeft || touchesRight);
            });
            if (!touchesCropEdge) {
                throw new IllegalArgumentException(label + " phải chạm mép trái hoặc phải của vùng trang đã tách.");
            }
        }
    }

    /** Persists every selected edge strip; the worker removes these strips. */
    private static Map<String, Object> normalizedMask(List<Map<String, Object>> cuts, List<Map<String, Object>> crops,
                                                        int templatePageCount) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("maskCount", cuts.size()); result.put("templatePageCount", templatePageCount);
        result.put("crops", crops); result.put("cuts", cuts); result.put("redactions", List.of());
        return result;
    }
    private static PageInfo readPageInfo(byte[] sourcePdf) {
        try (PDDocument pdf = PDDocument.load(new ByteArrayInputStream(sourcePdf))) {
            if (pdf.getNumberOfPages() == 0) throw new IllegalArgumentException("PDF mẫu không có trang nào.");
            var box = pdf.getPage(0).getMediaBox();
            return new PageInfo(box.getWidth(), box.getHeight(), pdf.getNumberOfPages());
        } catch (IllegalArgumentException exception) { throw exception;
        } catch (Exception exception) { throw new IllegalArgumentException("Không thể đọc tệp PDF mẫu. Hãy kiểm tra tệp không bị hỏng hoặc có mật khẩu."); }
    }
    private static String string(Map<String, Object> data, String key) { String value = optional(data, key); if (value == null) throw new IllegalArgumentException(key + " là bắt buộc."); return value; }
    private static String optional(Map<String, Object> data, String key) { Object value = data.get(key); return value == null || value.toString().isBlank() ? null : value.toString().trim(); }
    private static BigDecimal number(Map<String, Object> data, String key) { try { return new BigDecimal(String.valueOf(data.get(key))); } catch (RuntimeException e) { throw new IllegalArgumentException(key + " không hợp lệ."); } }
    private static int integer(Map<String, Object> data, String key) { try { return Integer.parseInt(String.valueOf(data.get(key))); } catch (RuntimeException e) { throw new IllegalArgumentException(key + " không hợp lệ."); } }
    private static String capitalize(String value) { return Character.toUpperCase(value.charAt(0)) + value.substring(1); }
    private record PageInfo(double width, double height, int pageCount) {}
}
