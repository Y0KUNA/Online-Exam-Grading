-- TICKET 12 (task 4): submission.page_count type align with data model
-- Spec: page_count smallint, CHECK 1..20 (V9 already added the CHECK).

ALTER TABLE submission
    ALTER COLUMN page_count TYPE smallint;
