package vn.edu.examgrading.submission.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFileReplacementRepository implements FileReplacementRepository {

    private final JdbcTemplate jdbc;

    public JdbcFileReplacementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertPending(UUID replacementId, UUID submissionId, int version, String objectKey,
        long declaredSizeBytes, String reason, Instant uploadExpiresAt, UUID requestedBy) {
        jdbc.update("""
            insert into file_replacement(replacement_id,submission_id,version,object_key,
                declared_size_bytes,reason,status,upload_expires_at,requested_by)
            values(?,?,?,?,?,?,'PENDING',?,?)
            """,
            replacementId, submissionId, version, objectKey,
            declaredSizeBytes, reason, Timestamp.from(uploadExpiresAt), requestedBy);
    }

    @Override
    public Optional<Replacement> findPending(UUID submissionId, Instant now) {
        return jdbc.query("""
            select replacement_id,submission_id,version,object_key,declared_size_bytes,reason,
                   status,upload_expires_at,requested_by,created_at,completed_at
            from file_replacement
            where submission_id=? and status='PENDING' and upload_expires_at > ?
            order by created_at desc limit 1
            """, this::map, submissionId, Timestamp.from(now))
            .stream().findFirst();
    }

    @Override
    public Optional<Replacement> findById(UUID replacementId) {
        return jdbc.query("""
            select replacement_id,submission_id,version,object_key,declared_size_bytes,reason,
                   status,upload_expires_at,requested_by,created_at,completed_at
            from file_replacement where replacement_id=?
            """, this::map, replacementId)
            .stream().findFirst();
    }

    @Override
    public int markCompleted(UUID replacementId, Instant completedAt) {
        return jdbc.update("""
            update file_replacement set status='COMPLETED', completed_at=?
            where replacement_id=? and status='PENDING'
            """, Timestamp.from(completedAt), replacementId);
    }

    @Override
    public int markCancelled(UUID replacementId) {
        return jdbc.update(
            "update file_replacement set status='CANCELLED' where replacement_id=? and status='PENDING'",
            replacementId);
    }

    @Override
    public int markExpired(UUID replacementId) {
        return jdbc.update("""
            update file_replacement set status='EXPIRED'
            where replacement_id=? and status='PENDING' and upload_expires_at <= now()
            """, replacementId);
    }

    @Override
    public int expireOverdue(UUID submissionId, Instant now) {
        return jdbc.update("""
            update file_replacement set status='EXPIRED'
            where submission_id=? and status='PENDING' and upload_expires_at <= ?
            """, submissionId, Timestamp.from(now));
    }

    private Replacement map(ResultSet rs, int rowNum) throws SQLException {
        return new Replacement(
            rs.getObject("replacement_id", UUID.class),
            rs.getObject("submission_id", UUID.class),
            rs.getInt("version"),
            rs.getString("object_key"),
            rs.getLong("declared_size_bytes"),
            rs.getString("reason"),
            rs.getString("status"),
            toInstant(rs.getTimestamp("upload_expires_at")),
            rs.getObject("requested_by", UUID.class),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("completed_at")));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
