-- TICKET 03: submission_event table for SSE and audit (10-implementation-spec.md §4.3)
-- Drop old table from V1 (different schema) and recreate with correct schema
DROP TABLE IF EXISTS submission_event CASCADE;
CREATE TABLE submission_event (
    event_id bigserial PRIMARY KEY,
    submission_id uuid NOT NULL REFERENCES submission(submission_id) ON DELETE CASCADE,
    exam_id uuid NOT NULL,
    status varchar(32) NOT NULL,
    progress smallint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX submission_event_submission ON submission_event(submission_id, event_id);
CREATE INDEX submission_event_exam ON submission_event(exam_id, event_id);
