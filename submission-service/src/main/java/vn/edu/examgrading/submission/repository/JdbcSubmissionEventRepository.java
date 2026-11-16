package vn.edu.examgrading.submission.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.edu.examgrading.submission.domain.SubmissionStatus;

/**
 * JDBC-backed implementation of {@link SubmissionEventRepository} (TICKET 03).
 */
@Repository
public class JdbcSubmissionEventRepository implements SubmissionEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcSubmissionEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void append(UUID submissionId, SubmissionStatus status, int progress) {
        String sql = """
            INSERT INTO submission_event (submission_id, exam_id, status, progress, created_at)
            SELECT submission_id, exam_id, ?, ?, now()
            FROM submission
            WHERE submission_id = ?
            """;
        jdbcTemplate.update(sql, status.name(), progress, submissionId);
    }
}
