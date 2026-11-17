-- TICKET 12 (task 2): Align exam_file with spec §5 / spec v1.5 line "ExamFile"
--   - version int NOT NULL DEFAULT 1 (default added; NOT NULL already since V2)
--   - UNIQUE(submission_id,type,version) already enforced (V2, kept by V6)
--   - partial unique (submission_id,type) WHERE is_current: at most one
--     current file per type per submission (replace-file keeps old versions)
-- Also adds Submission.binding_released (spec §5, same schema-sync bullet).

ALTER TABLE exam_file
    ALTER COLUMN version SET DEFAULT 1;

CREATE UNIQUE INDEX IF NOT EXISTS exam_file_current_unique
    ON exam_file(submission_id, type)
    WHERE is_current;

ALTER TABLE submission
    ADD COLUMN IF NOT EXISTS binding_released boolean NOT NULL DEFAULT false;
