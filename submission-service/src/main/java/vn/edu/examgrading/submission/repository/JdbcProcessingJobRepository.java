package vn.edu.examgrading.submission.repository;

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
    public void insertQueued(UUID jobId, UUID submissionId, String step, int fileVersion) {
        String sql = """
            INSERT INTO processing_job (
                job_id, submission_id, step, file_version, status, created_at
            ) VALUES (?, ?, ?, ?, 'QUEUED', now())
            """;
        jdbcTemplate.update(sql, jobId, submissionId, step, fileVersion);
    }
}
