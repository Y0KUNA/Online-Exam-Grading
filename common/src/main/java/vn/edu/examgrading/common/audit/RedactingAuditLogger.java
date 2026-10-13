package vn.edu.examgrading.common.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import vn.edu.examgrading.common.logging.SensitiveDataRedactor;

/** Ensures cross-cutting audit events never preserve tokens, presigned URLs or credentials. */
public final class RedactingAuditLogger implements AuditLogger {
    private final AuditLogger delegate;
    public RedactingAuditLogger(AuditLogger delegate) { this.delegate = delegate; }
    @Override public void append(AuditEntry entry) {
        Map<String, Object> safe = new LinkedHashMap<>();
        entry.detail().forEach((key, value) -> safe.put(key, redact(key, value)));
        delegate.append(new AuditEntry(entry.actorId(), entry.action(), entry.entityType(), entry.entityId(), entry.occurredAt(), Map.copyOf(safe)));
    }
    private Object redact(String key, Object value) {
        String lowerKey = key.toLowerCase();
        if (lowerKey.matches(".*(password|token|signature|presigned|authorization|candidate|email|phone).*")) return "[REDACTED]";
        return value instanceof String text ? SensitiveDataRedactor.redact(text) : value;
    }
}
