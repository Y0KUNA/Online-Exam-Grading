CREATE TABLE paper_template (template_id uuid PRIMARY KEY, name varchar(160) NOT NULL, page_width numeric(8,2) NOT NULL, page_height numeric(8,2) NOT NULL, mask jsonb NOT NULL, active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE submission (
    submission_id uuid PRIMARY KEY, exam_id uuid NOT NULL, candidate_id uuid NOT NULL, template_id uuid, status varchar(32) NOT NULL,
    current_file_version integer NOT NULL DEFAULT 1, progress smallint NOT NULL DEFAULT 0, upload_expires_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(), version integer NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX submission_active_candidate ON submission(exam_id, candidate_id) WHERE status <> 'FAILED';
CREATE TABLE exam_file (file_id uuid PRIMARY KEY, submission_id uuid NOT NULL REFERENCES submission(submission_id) ON DELETE CASCADE, type varchar(16) NOT NULL, version integer NOT NULL, size_bytes bigint, sha256 varchar(64), is_current boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(submission_id, type, version));
CREATE TABLE processing_job (job_id uuid PRIMARY KEY, submission_id uuid NOT NULL REFERENCES submission(submission_id), step varchar(16) NOT NULL, file_version integer NOT NULL, status varchar(16) NOT NULL DEFAULT 'QUEUED', force boolean NOT NULL DEFAULT false, attempts integer NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now());
