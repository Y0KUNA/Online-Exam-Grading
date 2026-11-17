-- TICKET 12 (task 1): Align processing_job with spec §8 / data model
-- attempt, error_code, error_detail, next_retry_at were added in V7.
-- This migration:
--   1. adds updated_at (JdbcProcessingJobRepository already writes it)
--   2. constrains status to QUEUED|PROCESSING|SUCCEEDED|FAILED (spec §8)
--   3. migrates legacy 'COMPLETED' rows to 'SUCCEEDED' (spec term)
--   4. drops unused legacy `attempts` column (V2; code uses `attempt`)
--   5. adds index for reconciler / retry queries (data model §indexes)

ALTER TABLE processing_job
    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

UPDATE processing_job SET status = 'SUCCEEDED' WHERE status = 'COMPLETED';

ALTER TABLE processing_job DROP COLUMN IF EXISTS attempts;

ALTER TABLE processing_job
    ADD CONSTRAINT processing_job_status_check
    CHECK (status IN ('QUEUED', 'PROCESSING', 'SUCCEEDED', 'FAILED'));

CREATE INDEX IF NOT EXISTS processing_job_status_retry
    ON processing_job(status, next_retry_at);
