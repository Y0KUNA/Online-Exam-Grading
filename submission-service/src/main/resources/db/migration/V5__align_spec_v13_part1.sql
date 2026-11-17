-- TICKET 06: Align with Data Model v1.3 - Part 1: Add missing columns to submission
-- declared_file_size_bytes and failure_code were already added by V3__upload_columns.sql;
-- this migration only adds file_size_bytes and widens failure_code to varchar(80).

-- Add file_size_bytes (ghi sau khi complete verify HEAD object)
ALTER TABLE submission
ADD COLUMN IF NOT EXISTS file_size_bytes bigint;

-- Widen failure_code (track lỗi upload: UPLOAD_ABANDONED, UPLOAD_NOT_FOUND, UPLOAD_EXPIRED, FILE_TOO_LARGE)
ALTER TABLE submission
ALTER COLUMN failure_code TYPE varchar(80);
