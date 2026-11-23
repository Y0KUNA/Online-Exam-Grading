package vn.edu.examgrading.submission.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.TimeUnit;

/** Relays committed outbox records to RabbitMQ. Duplicate delivery is safe by job ID. */
@Component
public class SubmissionOutboxRelay {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate;
    private final RabbitTemplate rabbitTemplate;
    public SubmissionOutboxRelay(JdbcTemplate jdbcTemplate, RabbitTemplate rabbitTemplate) {
        this.jdbcTemplate = jdbcTemplate; this.rabbitTemplate = rabbitTemplate;
    }
    @Scheduled(fixedDelayString = "${app.outbox.relay-delay-ms:500}")
    @Transactional
    public void relayPending() {
        List<Event> events = jdbcTemplate.query("""
            SELECT event_id, message_type, payload::text FROM submission_outbox
            WHERE status = 'PENDING' ORDER BY created_at LIMIT 50 FOR UPDATE SKIP LOCKED
            """, (rs, row) -> new Event(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)));
        for (Event event : events) {
            try {
                if ("VALIDATE".equals(event.type())) publish(event, "validate", JSON.readValue(event.payload(), ValidationWorker.ValidateJobMessage.class));
                else if ("ANONYMIZE".equals(event.type())) publish(event, "anonymize", JSON.readValue(event.payload(), AnonymizeJobMessage.class));
                else throw new IllegalStateException("Unknown outbox message type: " + event.type());
                jdbcTemplate.update("UPDATE submission_outbox SET status = 'SENT', sent_at = now(), last_error = NULL WHERE event_id = ?", event.id());
            } catch (Exception e) {
                jdbcTemplate.update("UPDATE submission_outbox SET attempt = attempt + 1, last_error = ? WHERE event_id = ?", e.getMessage(), event.id());
            }
        }
    }

    private void publish(Event event, String routingKey, Object message) throws Exception {
        CorrelationData correlation = new CorrelationData(event.id().toString());
        rabbitTemplate.convertAndSend("submission.processing", routingKey, message, correlation);
        CorrelationData.Confirm confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
        if (!confirm.isAck()) throw new IllegalStateException("RabbitMQ rejected outbox event: " + confirm.getReason());
    }
    private record Event(UUID id, String type, String payload) {}
}
