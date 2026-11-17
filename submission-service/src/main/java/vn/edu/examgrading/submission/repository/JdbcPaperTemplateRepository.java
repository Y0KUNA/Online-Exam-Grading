package vn.edu.examgrading.submission.repository;

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
}
