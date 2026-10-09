package vn.edu.examgrading.common.idempotency;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyStore {
    Optional<IdempotencyRecord> find(UUID actorId, String operation, String key);
    void save(IdempotencyRecord record);
}
