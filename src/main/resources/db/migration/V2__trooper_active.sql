-- ---------------------------------------------------------------------------
-- V2 — archiving a Trooper instead of deleting them.
--
-- duty_assignment references trooper with no cascade, on purpose: a logged
-- sheet is the record of what was actually used, and deleting someone must not
-- rewrite it. So a Trooper with logged duty is archived — off the roster, never
-- scheduled again — and stays on the sheets they stood. A Trooper with no logged
-- duty is deleted outright.
-- ---------------------------------------------------------------------------

ALTER TABLE trooper ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
