CREATE TABLE submission_outbox (
    event_id uuid PRIMARY KEY,
    message_type varchar(16) NOT NULL,
    payload jsonb NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    attempt smallint NOT NULL DEFAULT 0,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    sent_at timestamptz
);
CREATE INDEX submission_outbox_pending ON submission_outbox(status, created_at) WHERE status = 'PENDING';
