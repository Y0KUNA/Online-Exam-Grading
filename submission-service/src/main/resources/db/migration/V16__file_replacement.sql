-- §4.4 replace-file: một yêu cầu thay bản scan ORIGINAL đang chờ upload.
-- PENDING tối đa 1/submission (partial unique); version unique per submission;
-- declared_size_bytes chặn ngay ở DB theo đúng ngưỡng spec.
CREATE TABLE file_replacement (
    replacement_id uuid PRIMARY KEY,
    submission_id uuid NOT NULL REFERENCES submission(submission_id),
    version integer NOT NULL,
    object_key varchar(512) NOT NULL,
    declared_size_bytes bigint NOT NULL CHECK (declared_size_bytes <= 52428800),
    reason text NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('PENDING','COMPLETED','EXPIRED','CANCELLED')),
    upload_expires_at timestamptz NOT NULL,
    requested_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    UNIQUE(submission_id, version)
);
CREATE UNIQUE INDEX file_replacement_one_pending
    ON file_replacement(submission_id) WHERE status = 'PENDING';
