package vn.edu.examgrading.submission.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC-backed implementation of {@link PaperTemplateRepository} (TICKET 03).
 */
@Repository
public class JdbcPaperTemplateRepository implements PaperTemplateRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcPaperTemplateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean existsActive(UUID templateId) {
        String sql = "SELECT EXISTS(SELECT 1 FROM paper_template WHERE template_id = ? AND active = true)";
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, templateId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Optional<TemplateInfo> findTemplateInfo(UUID templateId) {
        String sql = """
            SELECT template_id, page_width, page_height, active, source_pdf
            FROM paper_template
            WHERE template_id = ?
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new TemplateInfo(
            rs.getObject("template_id", UUID.class),
            rs.getDouble("page_width"),
            rs.getDouble("page_height"),
            rs.getBoolean("active"),
            rs.getBytes("source_pdf")
        ), templateId).stream().findFirst();
    }

    @Override
    public Optional<MaskRegions> findMaskRegions(UUID templateId) {
        String sql = """
            SELECT mask FROM paper_template
            WHERE template_id = ? AND active = true
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> parseMaskRegions(rs.getString("mask")),
            templateId).stream().findFirst();
    }

    private static MaskRegions parseMaskRegions(String maskJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = OBJECT_MAPPER.readTree(maskJson);
            com.fasterxml.jackson.databind.JsonNode mask = root.path("mask");
            com.fasterxml.jackson.databind.JsonNode print = root.path("print");
            Region maskRegion = new Region(
                mask.path("x").asDouble(), mask.path("y").asDouble(),
                mask.path("width").asDouble(), mask.path("height").asDouble());
            Region printRegion = new Region(
                print.path("x").asDouble(), print.path("y").asDouble(),
                print.path("width").asDouble(), print.path("height").asDouble());
            String fontName = print.path("fontName").asText("Helvetica");
            double fontSize = print.path("fontSize").asDouble(12.0);
            String align = print.path("align").asText("CENTER");
            return new MaskRegions(maskRegion, printRegion, fontName, fontSize, align);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid mask jsonb for template", e);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();
}
