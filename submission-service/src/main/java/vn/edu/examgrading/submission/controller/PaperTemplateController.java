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
        Map<String, Object> data = json.readValue(metadata, new TypeReference<>() {});
        String name = string(data, "name");
        List<Map<String, Object>> masks = json.convertValue(data.get("masks"), new TypeReference<>() {});
        if (masks == null || masks.isEmpty()) throw new IllegalArgumentException("Cần đánh dấu ít nhất một vùng che.");
        byte[] sourcePdf = file.getBytes();
        PageInfo pageInfo = readPageInfo(sourcePdf);
        for (Map<String, Object> mask : masks) validateRegion(mask, pageInfo.pageCount(), "vùng che");
        List<Map<String, Object>> crops = json.convertValue(data.get("crops"), new TypeReference<>() {});
        if (crops == null || crops.isEmpty()) throw new IllegalArgumentException("Cần đánh dấu ít nhất một vùng cắt trang.");
        for (Map<String, Object> crop : crops) validateCrop(crop, pageInfo.pageCount());
        if (crops.stream().map(crop -> integer(crop, "order")).distinct().count() != crops.size()) {
            throw new IllegalArgumentException("Thứ tự các trang cắt không được trùng nhau.");
        }
        validateCutRegions(masks, crops);
        List<Map<String, Object>> redactions = json.convertValue(data.get("redactions"), new TypeReference<>() {});
        if (redactions == null) redactions = List.of();
        for (Map<String, Object> redaction : redactions) validateRegion(redaction, pageInfo.pageCount(), "vùng che thường");
        Map<String, Object> normalizedMask = normalizedMask(masks, crops, redactions, pageInfo.pageCount());
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

    private static void validateRegion(Map<String, Object> mask, int pageCount, String label) {
        int page = integer(mask, "page");
        if (page < 1 || page > pageCount) throw new IllegalArgumentException("Trang của " + label + " không hợp lệ.");
        for (String key : List.of("x", "y", "width", "height")) {
            BigDecimal value = number(mask, key);
            if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("Tọa độ vùng che không hợp lệ.");
        }
        if (number(mask, "width").signum() <= 0 || number(mask, "height").signum() <= 0
                || number(mask, "x").add(number(mask, "width")).compareTo(BigDecimal.ONE) > 0 || number(mask, "y").add(number(mask, "height")).compareTo(BigDecimal.ONE) > 0)
            throw new IllegalArgumentException("Vùng che vượt quá trang PDF.");
    }

    private static void validateCrop(Map<String, Object> crop, int pageCount) {
        validateRegion(crop, pageCount, "vùng cắt");
        if (integer(crop, "order") < 1) throw new IllegalArgumentException("Thứ tự trang cắt không hợp lệ.");
    }

    private static void validateCutRegions(List<Map<String, Object>> cuts, List<Map<String, Object>> crops) {
        final BigDecimal tolerance = new BigDecimal("0.01");
        for (Map<String, Object> cut : cuts) {
            BigDecimal y = number(cut, "y");
            BigDecimal bottom = y.add(number(cut, "height"));
            if (y.compareTo(tolerance) > 0 || bottom.compareTo(BigDecimal.ONE.subtract(tolerance)) < 0) {
                throw new IllegalArgumentException("Vùng cắt phách phải kéo dài từ mép trên đến mép dưới trang PDF.");
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
                throw new IllegalArgumentException("Vùng cắt phách phải chạm mép của một vùng trang đã tách.");
            }
        }
    }

    /**
     * PageMasker currently rasterizes one rectangle and writes the generated
     * pseudonym inside it.  The editor may collect several rectangles, so keep
     * the user's count for display but persist their bounding rectangle.  This
     * is intentionally conservative: no selected personal information can be
     * left visible between rectangles.
     */
    private static Map<String, Object> normalizedMask(List<Map<String, Object>> masks, List<Map<String, Object>> crops,
                                                        List<Map<String, Object>> redactions, int templatePageCount) {
        int maskPage = integer(masks.get(0), "page");
        if (masks.stream().anyMatch(mask -> integer(mask, "page") != maskPage)) {
            throw new IllegalArgumentException("Các vùng che phải nằm trên cùng một mặt mẫu.");
        }
        BigDecimal minX = BigDecimal.ONE, minY = BigDecimal.ONE, maxX = BigDecimal.ZERO, maxY = BigDecimal.ZERO;
        for (Map<String, Object> region : masks) {
            BigDecimal x = number(region, "x"), y = number(region, "y");
            minX = minX.min(x); minY = minY.min(y);
            maxX = maxX.max(x.add(number(region, "width"))); maxY = maxY.max(y.add(number(region, "height")));
        }
        BigDecimal width = maxX.subtract(minX), height = maxY.subtract(minY);
        Map<String, Object> mask = region(minX, minY, width, height);
        // A centered inner area is reserved for the generated phách.
        Map<String, Object> print = region(
                minX.add(width.multiply(new BigDecimal("0.10"))),
                minY.add(height.multiply(new BigDecimal("0.25"))),
                width.multiply(new BigDecimal("0.80")), height.multiply(new BigDecimal("0.50")));
        print.put("fontName", "Helvetica-Bold");
        print.put("fontSize", 12);
        print.put("align", "CENTER");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mask", mask); result.put("print", print); result.put("maskPage", maskPage);
        result.put("maskCount", masks.size()); result.put("templatePageCount", templatePageCount);
        result.put("crops", crops); result.put("cuts", masks); result.put("redactions", redactions);
        return result;
    }

    private static Map<String, Object> region(BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("x", x); result.put("y", y); result.put("width", width); result.put("height", height);
        return result;
    }
    private static PageInfo readPageInfo(byte[] sourcePdf) throws Exception {
        try (PDDocument pdf = PDDocument.load(new ByteArrayInputStream(sourcePdf))) {
            if (pdf.getNumberOfPages() == 0) throw new IllegalArgumentException("PDF mẫu không có trang nào.");
            var box = pdf.getPage(0).getMediaBox();
            return new PageInfo(box.getWidth(), box.getHeight(), pdf.getNumberOfPages());
        }
    }
    private static String string(Map<String, Object> data, String key) { String value = optional(data, key); if (value == null) throw new IllegalArgumentException(key + " là bắt buộc."); return value; }
    private static String optional(Map<String, Object> data, String key) { Object value = data.get(key); return value == null || value.toString().isBlank() ? null : value.toString().trim(); }
    private static BigDecimal number(Map<String, Object> data, String key) { try { return new BigDecimal(String.valueOf(data.get(key))); } catch (RuntimeException e) { throw new IllegalArgumentException(key + " không hợp lệ."); } }
    private static int integer(Map<String, Object> data, String key) { try { return Integer.parseInt(String.valueOf(data.get(key))); } catch (RuntimeException e) { throw new IllegalArgumentException(key + " không hợp lệ."); } }
    private record PageInfo(double width, double height, int pageCount) {}
}
