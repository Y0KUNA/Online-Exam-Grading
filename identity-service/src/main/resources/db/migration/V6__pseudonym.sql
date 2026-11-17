-- TICKET 02: Add pseudonym table for Identity service
-- Stores candidate pseudonyms (số phách) for anonymization

CREATE TABLE IF NOT EXISTS pseudonym (
    pseudonym_id uuid PRIMARY KEY,
    exam_id uuid NOT NULL,
    submission_id uuid NOT NULL UNIQUE,
    candidate_id uuid NOT NULL,
    value varchar(8) NOT NULL,
    idempotency_key varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(exam_id, value)
);

CREATE INDEX IF NOT EXISTS idx_pseudonym_submission ON pseudonym(submission_id);
CREATE INDEX IF NOT EXISTS idx_pseudonym_exam_value ON pseudonym(exam_id, value);
