-- TICKET 06: Align with Data Model v1.3 - Part 4: Remove candidate_id and cleanup
-- Remove candidate_id from submission to enforce identity boundary (FR-022, FR-025)
-- Remove force and file_version from processing_job (not in spec)
-- Remove index that depends on candidate_id

-- WARNING: This migration removes candidate_id column which may have data
-- In production, this would require data migration to preserve pseudonym mapping
-- For dev environment, we proceed with direct removal

-- Drop index that depends on candidate_id
DROP INDEX IF EXISTS submission_active_candidate;

-- Remove candidate_id from submission table
ALTER TABLE submission
DROP COLUMN IF EXISTS candidate_id;

-- Remove force from processing_job (not in spec)
ALTER TABLE processing_job
DROP COLUMN IF EXISTS force;

-- Remove file_version from processing_job (not in spec)
ALTER TABLE processing_job
DROP COLUMN IF EXISTS file_version;

-- Remove version from exam_file unique constraint (already handled in V6, but verify)
-- Note: The exam_file table still has version column, but unique constraint is now (submission_id, type)
-- We keep version column for potential future use, but it's not part of unique constraint
