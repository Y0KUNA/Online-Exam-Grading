package vn.edu.examgrading.submission.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC-backed implementation of {@link ProcessingJobRepository} (TICKET 06).
 * Enqueues processing jobs during the complete flow.
 */
@Repository
public class JdbcProcessingJobRepository implements ProcessingJobRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcProcessingJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insertQueued(UUID jobId, UUID submissionId, String step, int fileVersion, UUID templateId) {
        String sql = """
            INSERT INTO processing_job (
                job_id, submission_id, step, status, attempt, file_version, template_id, created_at
            ) VALUES (?, ?, ?, 'QUEUED', 0, ?, ?, now())
            """;
        jdbcTemplate.update(sql, jobId, submissionId, step, fileVersion, templateId);
    }

    @Override
    public void insertQueued(UUID jobId, UUID submissionId, String step, int fileVersion,
            UUID templateId, boolean force, boolean overwrite) {
        String sql = """
            INSERT INTO processing_job (
                job_id, submission_id, step, status, attempt, file_version,
                force, overwrite, template_id, created_at
            ) VALUES (?, ?, ?, 'QUEUED', 0, ?, ?, ?, ?, now())
            """;
        jdbcTemplate.update(sql, jobId, submissionId, step, fileVersion, force, overwrite, templateId);
    }

    @Override
    public void updateJobStatus(UUID jobId, String status, int attempt) {
        String sql = """
            UPDATE processing_job
            SET status = ?, attempt = ?, updated_at = now()
            WHERE job_id = ?
            """;
        jdbcTemplate.update(sql, status, attempt, jobId);
    }

    @Override
    public void updateJobError(UUID jobId, String errorCode, String errorDetail, Instant nextRetryAt) {
        String sql = """
            UPDATE processing_job
            SET error_code = ?, error_detail = ?, next_retry_at = ?, updated_at = now()
            WHERE job_id = ?
            """;
        jdbcTemplate.update(sql, errorCode, errorDetail, toOffsetDateTimeUtc(nextRetryAt), jobId);
    }

    @Override
    public Optional<JobInfo> findJobById(UUID jobId) {
        String sql = """
            SELECT job_id, submission_id, step, status, attempt,
                   error_code, error_detail, next_retry_at,
                   file_version, force, overwrite, template_id
            FROM processing_job
            WHERE job_id = ?
            """;
        return jdbcTemplate.query(sql, this::mapJob, jobId).stream().findFirst();
    }

    @Override
    public Optional<JobInfo> findJobBySubmissionAndStep(UUID submissionId, String step) {
        String sql = """
            SELECT job_id, submission_id, step, status, attempt,
                   error_code, error_detail, next_retry_at,
                   file_version, force, overwrite, template_id
            FROM processing_job
            WHERE submission_id = ? AND step = ?
            ORDER BY created_at DESC
            LIMIT 1
            """;
        return jdbcTemplate.query(sql, this::mapJob, submissionId, step).stream().findFirst();
    }

    @Override
    public java.util.List<StaleJob> findStaleQueued(Instant staleBefore, Instant now, int limit) {
        String sql = """
            SELECT j.job_id, j.submission_id, s.exam_id, j.step, j.attempt,
                   j.file_version, j.force, j.overwrite, j.template_id
            FROM processing_job j
            JOIN submission s ON s.submission_id = j.submission_id
            WHERE j.status = 'QUEUED'
              AND j.updated_at < ?
              AND (j.next_retry_at IS NULL OR j.next_retry_at <= ?)
            ORDER BY j.updated_at
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new StaleJob(
            rs.getObject("job_id", UUID.class),
            rs.getObject("submission_id", UUID.class),
            rs.getObject("exam_id", UUID.class),
            rs.getString("step"),
            rs.getInt("attempt"),
            rs.getInt("file_version"),
            rs.getBoolean("force"),
            rs.getBoolean("overwrite"),
            rs.getObject("template_id", UUID.class)
        ), toOffsetDateTimeUtc(staleBefore), toOffsetDateTimeUtc(now), limit);
    }

    private JobInfo mapJob(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new JobInfo(
            rs.getObject("job_id", UUID.class),
            rs.getObject("submission_id", UUID.class),
            rs.getString("step"),
            rs.getString("status"),
            rs.getInt("attempt"),
            rs.getString("error_code"),
            rs.getString("error_detail"),
            toInstant(rs.getObject("next_retry_at", OffsetDateTime.class)),
            rs.getInt("file_version"),
            rs.getBoolean("force"),
            rs.getBoolean("overwrite"),
            rs.getObject("template_id", UUID.class)
        );
    }

    private OffsetDateTime toOffsetDateTimeUtc(Instant instant) {
        return instant == null ? null : instant.atOffset(java.time.ZoneOffset.UTC);
    }

    private Instant toInstant(OffsetDateTime offsetDateTime) {
        return offsetDateTime == null ? null : offsetDateTime.toInstant();
    }
}
