
ALTER TABLE submission
    ADD COLUMN IF NOT EXISTS finalized_at timestamptz,
    ADD COLUMN IF NOT EXISTS retention_deadline timestamptz;
