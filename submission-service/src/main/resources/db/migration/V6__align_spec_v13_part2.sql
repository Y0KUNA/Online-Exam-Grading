-- TICKET 06: Align with Data Model v1.3 - Part 2: Fix exam_file unique constraint
-- The V2 UNIQUE(submission_id, type, version) is a table constraint, so its
-- backing index cannot be DROP INDEX'ed - drop the constraint instead and
-- recreate the same uniqueness until TICKET 12 task 2 applies the spec §5
-- shape (UNIQUE(submission_id,type,version) + partial unique on is_current).

ALTER TABLE exam_file
    DROP CONSTRAINT IF EXISTS exam_file_submission_id_type_version_key;

ALTER TABLE exam_file
    ADD CONSTRAINT exam_file_submission_id_type_version_key
    UNIQUE (submission_id, type, version);
