package vn.edu.examgrading.common.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEntry(UUID actorId, String action, String entityType, UUID entityId, Instant occurredAt, Map<String, Object> detail) { }
