package vn.edu.examgrading.submission.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
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

    @Override
    public List<EventRow> findByExamAfter(UUID examId, long afterEventId, int limit) {
        String sql = """
            SELECT e.event_id, e.submission_id, e.status, e.progress, s.updated_at
            FROM submission_event e
            JOIN submission s ON s.submission_id = e.submission_id
            WHERE e.exam_id = ? AND e.event_id > ?
            ORDER BY e.event_id
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new EventRow(
            rs.getLong("event_id"),
            rs.getObject("submission_id", UUID.class),
            SubmissionStatus.valueOf(rs.getString("status")),
            rs.getInt("progress"),
            toInstant(rs.getObject("updated_at", OffsetDateTime.class))
        ), examId, afterEventId, limit);
    }

    private Instant toInstant(OffsetDateTime offsetDateTime) {
        return offsetDateTime == null ? null : offsetDateTime.toInstant();
    }
}
