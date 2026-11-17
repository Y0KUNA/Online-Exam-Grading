-- TICKET 08: Add submission_binding table for Identity binding flow (E-14, T01)
-- This table stores the mapping between candidate, submission, and exam
-- enabling the pseudonym service to retrieve candidate_id from submission_id
-- without storing candidate_id in the submission service database (FR-022, FR-025)

CREATE TYPE binding_status AS ENUM ('ACTIVE', 'RELEASED');

-- A binding must point to the candidate in the same exam. PostgreSQL requires
-- the referenced tuple to be explicitly unique, even though candidate_id alone
-- is already the primary key.
ALTER TABLE candidate
    ADD CONSTRAINT uq_candidate_id_exam UNIQUE (candidate_id, exam_id);

CREATE TABLE submission_binding (
    binding_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    exam_id uuid NOT NULL,
    submission_id uuid NOT NULL UNIQUE,
    candidate_id uuid NOT NULL,
    status binding_status NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    released_at timestamptz,
    CONSTRAINT fk_submission_binding_candidate_exam FOREIGN KEY (candidate_id, exam_id)
        REFERENCES candidate(candidate_id, exam_id) ON DELETE RESTRICT
);

-- Index to enforce: one candidate can have at most one ACTIVE binding per exam
-- This prevents the same candidate from submitting multiple times for the same exam
CREATE UNIQUE INDEX uq_submission_binding_exam_candidate_active
    ON submission_binding (exam_id, candidate_id, status)
    WHERE status = 'ACTIVE';

-- Index for efficient lookup by submission_id (used by pseudonym service)
CREATE INDEX idx_submission_binding_submission
    ON submission_binding (submission_id);

-- Index for efficient lookup by exam_id (used for cleanup and reporting)
CREATE INDEX idx_submission_binding_exam
    ON submission_binding (exam_id);

-- Index for finding expired/abandoned bindings (for cleanup job)
CREATE INDEX idx_submission_binding_status_created
    ON submission_binding (status, created_at);
