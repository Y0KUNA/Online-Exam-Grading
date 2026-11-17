-- TICKET 06: Align with Data Model v1.3 - Part 3: Add processing_job columns
-- Add attempt, error_code, error_detail, next_retry_at for retry logic

-- Add attempt (track retry count)
ALTER TABLE processing_job
ADD COLUMN attempt int NOT NULL DEFAULT 0;

-- Add error_code (track error type)
ALTER TABLE processing_job
ADD COLUMN error_code varchar(80);

-- Add error_detail (error message)
ALTER TABLE processing_job
ADD COLUMN error_detail text;

-- Add next_retry_at (schedule next retry)
ALTER TABLE processing_job
ADD COLUMN next_retry_at timestamptz;
