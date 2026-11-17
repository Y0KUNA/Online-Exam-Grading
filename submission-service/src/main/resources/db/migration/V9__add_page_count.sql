-- TICKET 02: Add page_count to submission table for VALIDATE worker
-- Track number of pages in PDF after validation

ALTER TABLE submission
ADD COLUMN page_count integer
CHECK (page_count IS NULL OR (page_count >= 1 AND page_count <= 20));
