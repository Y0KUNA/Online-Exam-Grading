package vn.edu.examgrading.submission.repository;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import vn.edu.examgrading.submission.domain.Submission;
import vn.edu.examgrading.submission.domain.SubmissionStatus;

/**
 * JDBC-backed implementation of {@link SubmissionRepositoryMarker} (TICKET 03).
 * Binds {@code Instant} as UTC {@code OffsetDateTime} for {@code timestamptz} columns.
 * Surfaces unique-index clashes as {@code DuplicateKeyException} for the service layer.
 */
@Repository
public class JdbcSubmissionRepository implements SubmissionRepositoryMarker {

    private final JdbcTemplate jdbcTemplate;

    public JdbcSubmissionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insertUploading(UUID submissionId, UUID examId, UUID candidateId, UUID templateId,
            Instant uploadExpiresAt, long declaredFileSizeBytes, String objectKey) {
        String sql = """
            INSERT INTO submission (
                submission_id, exam_id, candidate_id, template_id, status,
                current_file_version, progress, upload_expires_at,
                declared_file_size_bytes, object_key
            ) VALUES (?, ?, ?, ?, 'UPLOADING', 1, 0, ?, ?, ?)
            """;
        jdbcTemplate.update(sql,
            submissionId, examId, candidateId, templateId,
            toOffsetDateTimeUtc(uploadExpiresAt),
            declaredFileSizeBytes, objectKey
        );
    }

    @Override
    public Optional<Submission> findById(UUID submissionId) {
        String sql = """
            SELECT submission_id, exam_id, candidate_id, template_id, status,
                   current_file_version, progress, upload_expires_at,
                   declared_file_size_bytes, object_key, failure_code,
                   version, updated_at
            FROM submission
            WHERE submission_id = ?
            """;
        List<Submission> results = jdbcTemplate.query(sql, submissionRowMapper(), submissionId);
        return results.stream().findFirst();
    }

    @Override
    public List<Submission> findByExam(UUID examId) {
        String sql = """
            SELECT submission_id, exam_id, candidate_id, template_id, status,
                   current_file_version, progress, upload_expires_at,
                   declared_file_size_bytes, object_key, failure_code,
                   version, updated_at
            FROM submission
            WHERE exam_id = ?
            ORDER BY upload_expires_at DESC
            """;
        return jdbcTemplate.query(sql, submissionRowMapper(), examId);
    }

    @Override
    public int transition(UUID submissionId, SubmissionStatus expected, SubmissionStatus target, int progress) {
        String sql = """
            UPDATE submission
            SET status = ?, progress = ?, updated_at = now()
            WHERE submission_id = ? AND status = ?
            """;
        return jdbcTemplate.update(sql, target.name(), progress, submissionId, expected.name());
    }

    @Override
    public List<Submission> findExpiredUploading(Instant now) {
        String sql = """
            SELECT submission_id, exam_id, candidate_id, template_id, status,
                   current_file_version, progress, upload_expires_at,
                   declared_file_size_bytes, object_key, failure_code,
                   version, updated_at
            FROM submission
            WHERE status = 'UPLOADING' AND upload_expires_at < ?
            """;
        return jdbcTemplate.query(sql, submissionRowMapper(), toOffsetDateTimeUtc(now));
    }

    private RowMapper<Submission> submissionRowMapper() {
        return (ResultSet rs, int rowNum) -> new Submission(
            rs.getObject("submission_id", UUID.class),
            rs.getObject("exam_id", UUID.class),
            rs.getObject("candidate_id", UUID.class),
            rs.getObject("template_id", UUID.class),
            SubmissionStatus.valueOf(rs.getString("status")),
            rs.getInt("current_file_version"),
            rs.getInt("progress"),
            toInstant(rs.getObject("upload_expires_at", OffsetDateTime.class)),
            (Long) rs.getObject("declared_file_size_bytes"),
            rs.getString("object_key"),
            rs.getString("failure_code"),
            rs.getInt("version"),
            toInstant(rs.getObject("updated_at", OffsetDateTime.class))
        );
    }

    private OffsetDateTime toOffsetDateTimeUtc(Instant instant) {
        return instant == null ? null : instant.atOffset(java.time.ZoneOffset.UTC);
    }

    private Instant toInstant(OffsetDateTime offsetDateTime) {
        return offsetDateTime == null ? null : offsetDateTime.toInstant();
    }
}
