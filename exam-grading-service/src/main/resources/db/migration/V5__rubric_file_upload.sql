ALTER TABLE rubric
    ADD COLUMN file_name varchar(255),
    ADD COLUMN object_key varchar(512),
    ADD COLUMN file_size_bytes bigint,
    ADD COLUMN content_type varchar(100),
    ADD COLUMN upload_expires_at timestamptz;

CREATE UNIQUE INDEX rubric_object_key_unique
    ON rubric(object_key)
    WHERE object_key IS NOT NULL;
