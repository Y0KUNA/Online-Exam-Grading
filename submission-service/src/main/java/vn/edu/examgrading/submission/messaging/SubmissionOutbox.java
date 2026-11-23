package vn.edu.examgrading.submission.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Writes processing messages in the same database transaction as their job row. */
@Component
public class SubmissionOutbox {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate;
    public SubmissionOutbox(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }
    public void enqueueValidate(ValidationWorker.ValidateJobMessage message) { enqueue("VALIDATE", message); }
    public void enqueueAnonymize(AnonymizeJobMessage message) { enqueue("ANONYMIZE", message); }
    private void enqueue(String type, Object message) {
        try {
            jdbcTemplate.update("INSERT INTO submission_outbox(event_id, message_type, payload) VALUES (?, ?, cast(? as jsonb))",
                UUID.randomUUID(), type, JSON.writeValueAsString(message));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to persist processing outbox event", e);
        }
    }
}
