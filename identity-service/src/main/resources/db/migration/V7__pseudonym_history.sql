-- TICKET 06 (task 4): Manual pseudonym edit support (spec 04 PseudonymHistory, D-10)
-- 1. pseudonym_history: audit trail for manual phách changes (old/new value,
--    changed_by = users.user_id per D-14, reason, changed_at). Retention-covered.
-- 2. Widen pseudonym.value varchar(8) -> varchar(64) to match spec data model
--    (generated phách remain 8 chars; manual edits may be longer).

CREATE TABLE IF NOT EXISTS pseudonym_history (
    history_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    pseudonym_id uuid NOT NULL REFERENCES pseudonym(pseudonym_id),
    old_value varchar(64),
    new_value varchar(64) NOT NULL,
    changed_by varchar(100) NOT NULL,
    reason text NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_pseudonym_history_pseudonym
    ON pseudonym_history (pseudonym_id);

ALTER TABLE pseudonym
    ALTER COLUMN value TYPE varchar(64);
