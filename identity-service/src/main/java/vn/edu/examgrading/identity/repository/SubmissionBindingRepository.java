package vn.edu.examgrading.identity.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Repository for submission_binding table (TICKET 01).
 * Manages candidate↔submission↔exam mapping.
 */
@Repository
public class SubmissionBindingRepository {

    private final JdbcTemplate jdbcTemplate;

    public SubmissionBindingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }

    /**
     * Create or retrieve existing ACTIVE binding for submission.
     * Returns bindingId and status.
     */
    public Optional<BindingInfo> findActiveBinding(UUID submissionId) {
        String sql = """
            SELECT binding_id, status
            FROM submission_binding
            WHERE submission_id = ? AND status = 'ACTIVE'
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new BindingInfo(
            rs.getObject("binding_id", UUID.class),
            rs.getString("status")
        ), submissionId).stream().findFirst();
    }

    /**
     * Insert new ACTIVE binding.
     * Throws exception if candidate already has ACTIVE binding for this exam.
     */
    public void insertBinding(UUID bindingId, UUID examId, UUID submissionId, UUID candidateId) {
        String sql = """
            INSERT INTO submission_binding (binding_id, exam_id, submission_id, candidate_id, status)
            VALUES (?, ?, ?, ?, 'ACTIVE')
            """;
        jdbcTemplate.update(sql, bindingId, examId, submissionId, candidateId);
    }

    /**
     * Release binding (mark as RELEASED).
     * Idempotent: no-op if already RELEASED or not found.
     */
    public void releaseBinding(UUID submissionId, String reason) {
        String sql = """
            UPDATE submission_binding
            SET status = 'RELEASED', released_at = now()
            WHERE submission_id = ? AND status = 'ACTIVE'
            """;
        jdbcTemplate.update(sql, submissionId);
    }

    /**
     * Get candidateId for a submission (used by pseudonym service).
     */
    public Optional<UUID> getCandidateId(UUID submissionId) {
        String sql = """
            SELECT candidate_id
            FROM submission_binding
            WHERE submission_id = ? AND status = 'ACTIVE'
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getObject("candidate_id", UUID.class), submissionId)
            .stream().findFirst();
    }

    public record BindingInfo(UUID bindingId, String status) {}
}
