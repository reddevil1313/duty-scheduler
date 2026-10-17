-- V5: default BUS Trooper. At most one, never a stay-out.

ALTER TABLE trooper ADD COLUMN default_bus BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE trooper ADD CONSTRAINT trooper_default_bus_not_stay_out CHECK (NOT (default_bus AND stay_out));
CREATE UNIQUE INDEX trooper_one_default_bus ON trooper (default_bus) WHERE default_bus;
