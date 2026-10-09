package vn.edu.examgrading.common.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxMessage(UUID id, String topic, String payload, Instant createdAt) { }
