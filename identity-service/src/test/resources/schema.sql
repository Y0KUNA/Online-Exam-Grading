-- Schema for H2 in-memory database used in unit tests (TICKET 01)
-- H2 doesn't support UUID type directly, using VARCHAR(36) instead

-- Candidate table (simplified for testing)
CREATE TABLE IF NOT EXISTS candidate (
    candidate_id VARCHAR(36) PRIMARY KEY,
    exam_id VARCHAR(36) NOT NULL
);

-- Submission binding table
CREATE TABLE IF NOT EXISTS submission_binding (
    binding_id VARCHAR(36) PRIMARY KEY,
    exam_id VARCHAR(36) NOT NULL,
    submission_id VARCHAR(36) NOT NULL UNIQUE,
    candidate_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    released_at TIMESTAMP NULL
);

-- Index for efficient query (H2 doesn't support partial index, creating full index)
CREATE INDEX IF NOT EXISTS idx_submission_binding_active
ON submission_binding (exam_id, candidate_id, status);
