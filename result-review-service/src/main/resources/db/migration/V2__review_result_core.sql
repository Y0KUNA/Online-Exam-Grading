CREATE TABLE review (
    review_id uuid PRIMARY KEY, submission_id uuid NOT NULL, exam_id uuid NOT NULL, reason_type varchar(32) NOT NULL,
    reason text NOT NULL, status varchar(32) NOT NULL DEFAULT 'OPEN', technical_code varchar(64), detail jsonb,
    opened_at timestamptz NOT NULL DEFAULT now(), resolved_at timestamptz, version integer NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX one_open_review_per_submission ON review(submission_id) WHERE status IN ('OPEN', 'ACTION_AUTHORIZED');
CREATE TABLE review_note (note_id uuid PRIMARY KEY, review_id uuid NOT NULL REFERENCES review(review_id) ON DELETE CASCADE, grader_id uuid, note text NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE council_decision (decision_id uuid PRIMARY KEY, review_id uuid NOT NULL UNIQUE REFERENCES review(review_id), decision_type varchar(32) NOT NULL, final_score numeric(6,2), reason text NOT NULL, decided_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE final_result (result_id uuid PRIMARY KEY, submission_id uuid NOT NULL, exam_id uuid NOT NULL, final_score numeric(6,2) NOT NULL, decision_source varchar(32) NOT NULL, version integer NOT NULL DEFAULT 1, is_current boolean NOT NULL DEFAULT true, finalized_at timestamptz NOT NULL, retention_deadline timestamptz NOT NULL, retention_hold boolean NOT NULL DEFAULT false, purge_status varchar(20) NOT NULL DEFAULT 'PENDING');
CREATE UNIQUE INDEX current_result_per_submission ON final_result(submission_id) WHERE is_current;
