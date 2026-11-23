package vn.edu.examgrading.resultreview.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.edu.examgrading.resultreview.repository.FinalResultRepository.PurgeCandidate;

/** JDBC-backed {@link FinalResultRepository} over the {@code final_result} table. */
@Repository
public class JdbcFinalResultRepository implements FinalResultRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcFinalResultRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<PurgeCandidate> findPurgeCandidates(Instant now, int limit) {
        return jdbcTemplate.query("""
            SELECT result_id, submission_id FROM final_result
            WHERE is_current = true
              AND retention_hold = false
              AND retention_deadline <= ?
              AND purge_status IN ('PENDING', 'FAILED')
            ORDER BY retention_deadline
            LIMIT ?
            """, this::mapCandidate, toOffset(now), limit);
    }

    @Override
    public boolean claimForPurge(UUID resultId) {
        int moved = jdbcTemplate.update("""
            UPDATE final_result SET purge_status = 'IN_PROGRESS'
            WHERE result_id = ?
              AND is_current = true
              AND retention_hold = false
              AND purge_status IN ('PENDING', 'FAILED')
            """, resultId);
        return moved > 0;
    }

    @Override
    public void markPurged(UUID resultId) {
        jdbcTemplate.update("""
            UPDATE final_result
            SET purge_status = 'PURGED', submission_id = NULL
            WHERE result_id = ?
            """, resultId);
    }

    @Override
    public void markPurgeFailed(UUID resultId) {
        jdbcTemplate.update("""
            UPDATE final_result SET purge_status = 'FAILED'
            WHERE result_id = ? AND purge_status = 'IN_PROGRESS'
            """, resultId);
    }

    private PurgeCandidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new PurgeCandidate(rs.getObject("result_id", UUID.class),
            rs.getObject("submission_id", UUID.class));
    }

    private OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(java.time.ZoneOffset.UTC);
    }
}
