package vn.edu.examgrading.common.idempotency;

import java.time.Instant;
import java.util.UUID;

public record IdempotencyRecord(UUID actorId, String operation, String key, int responseStatus, String responseBody, Instant createdAt) { }
