-- TICKET 03: columns the upload-init flow needs. exam_file.object_key stays
-- nullable here (backfilled + NOT NULL enforced from TICKET 05 once files land).
ALTER TABLE submission
    ADD COLUMN declared_file_size_bytes bigint CHECK (declared_file_size_bytes <= 52428800),
    ADD COLUMN object_key varchar(512),
    ADD COLUMN failure_code varchar(32);

ALTER TABLE exam_file
    ADD COLUMN object_key varchar(512);

CREATE INDEX submission_upload_due ON submission(status, upload_expires_at) WHERE status = 'UPLOADING';
