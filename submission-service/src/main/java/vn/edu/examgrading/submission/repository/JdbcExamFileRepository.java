package vn.edu.examgrading.submission.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC-backed implementation of {@link ExamFileRepository} (TICKET 06).
 * Manages exam-file rows populated during the complete flow.
 */
@Repository
public class JdbcExamFileRepository implements ExamFileRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcExamFileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean existsCurrentOriginal(UUID submissionId) {
        String sql = """
            SELECT COUNT(*) FROM exam_file
            WHERE submission_id = ? AND type = 'ORIGINAL' AND is_current = true
            """;
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class, submissionId);
        return result != null && result > 0;
    }

    @Override
    public void insertOriginal(UUID fileId, UUID submissionId, long sizeBytes, String objectKey) {
        String sql = """
            INSERT INTO exam_file (
                file_id, submission_id, type, version,
                size_bytes, object_key, sha256, is_current
            ) VALUES (?, ?, 'ORIGINAL', 1, ?, ?, null, true)
            """;
        jdbcTemplate.update(sql, fileId, submissionId, sizeBytes, objectKey);
    }

    @Override
    public Optional<OriginalFile> findCurrentOriginal(UUID submissionId) {
        String sql = """
            SELECT file_id, version, size_bytes, object_key
            FROM exam_file
            WHERE submission_id = ? AND type = 'ORIGINAL' AND is_current = true
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new OriginalFile(
            rs.getObject("file_id", UUID.class),
            rs.getInt("version"),
            rs.getLong("size_bytes"),
            rs.getString("object_key")
        ), submissionId).stream().findFirst();
    }

    @Override
    public Optional<OriginalFile> findCurrentByType(UUID submissionId, String type) {
        String sql = """
            SELECT file_id, version, size_bytes, object_key
            FROM exam_file
            WHERE submission_id = ? AND type = ? AND is_current = true
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new OriginalFile(
            rs.getObject("file_id", UUID.class),
            rs.getInt("version"),
            rs.getLong("size_bytes"),
            rs.getString("object_key")
        ), submissionId, type).stream().findFirst();
    }

    @Override
    public void insertAnonymized(UUID fileId, UUID submissionId, int version, long sizeBytes, String objectKey, String sha256) {
        String sql = """
            INSERT INTO exam_file (
                file_id, submission_id, type, version,
                size_bytes, object_key, sha256, is_current
            ) VALUES (?, ?, 'ANONYMIZED', ?, ?, ?, ?, true)
            """;
        jdbcTemplate.update(sql, fileId, submissionId, version, sizeBytes, objectKey, sha256);
    }

    @Override
    public List<ExamFileVersion> findAllBySubmission(UUID submissionId) {
        String sql = """
            SELECT type, version, is_current, size_bytes, sha256, created_at
            FROM exam_file
            WHERE submission_id = ?
            ORDER BY type, version DESC
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new ExamFileVersion(
            rs.getString("type"),
            rs.getInt("version"),
            rs.getBoolean("is_current"),
            (Long) rs.getObject("size_bytes"),
            rs.getString("sha256"),
            toInstant(rs.getObject("created_at", OffsetDateTime.class))
        ), submissionId);
    }

    @Override
    public boolean existsByObjectKey(String objectKey) {
        Integer result = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM exam_file WHERE object_key = ?", Integer.class, objectKey);
        return result != null && result > 0;
    }

    private Instant toInstant(OffsetDateTime offsetDateTime) {
        return offsetDateTime == null ? null : offsetDateTime.toInstant();
    }

    @Override
    public int updateAnonymized(UUID submissionId, long sizeBytes, String objectKey, String sha256) {
        String sql = """
            UPDATE exam_file
            SET size_bytes = ?, object_key = ?, sha256 = ?, created_at = now()
            WHERE submission_id = ? AND type = 'ANONYMIZED' AND is_current = true
            """;
        return jdbcTemplate.update(sql, sizeBytes, objectKey, sha256, submissionId);
    }
}
