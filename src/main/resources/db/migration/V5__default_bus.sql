-- ---------------------------------------------------------------------------
-- V5 — a default BUS Trooper.
--
-- Who the board's BUS pick starts on, as on_night is who its night group starts
-- with. At most one, which the partial unique index holds: only rows with
-- default_bus true are indexed, so only one of them can exist. Never a
-- stay-out, who is not in until 0800 and could never take the 0700 run.
-- ---------------------------------------------------------------------------

ALTER TABLE trooper ADD COLUMN default_bus BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE trooper ADD CONSTRAINT trooper_default_bus_not_stay_out CHECK (NOT (default_bus AND stay_out));
CREATE UNIQUE INDEX trooper_one_default_bus ON trooper (default_bus) WHERE default_bus;
