-- ---------------------------------------------------------------------------
-- V3 — what the records need beyond the logged grids.
--
-- Opening hours: what each Trooper had done before this tracker existed, so a
-- long-serving Trooper's total does not start from zero beside a new one's.
--
-- Credited hours: hours counted toward someone's total for a logged day they
-- did not stand, such as an MC or OL day the unit credits. Kept apart from the
-- grid on purpose. The grid is what was stood; changing it to reach a total
-- would put people on posts they never stood.
-- ---------------------------------------------------------------------------

ALTER TABLE trooper ADD COLUMN opening_hours INTEGER NOT NULL DEFAULT 0;
ALTER TABLE trooper ADD CONSTRAINT trooper_opening_hours_nonnegative CHECK (opening_hours >= 0);

-- One row per Trooper per logged day, and only where something is credited.
-- Goes with its sheet; never with its Trooper, for the same reason a
-- duty_assignment does not: removing someone must not rewrite the record.
CREATE TABLE hour_credit (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sheet_id    BIGINT  NOT NULL REFERENCES duty_sheet(id) ON DELETE CASCADE,
    trooper_id  BIGINT  NOT NULL REFERENCES trooper(id),
    hours       INTEGER NOT NULL,
    CONSTRAINT hour_credit_once_per_sheet UNIQUE (sheet_id, trooper_id),
    CONSTRAINT hour_credit_hours_in_a_day CHECK (hours BETWEEN 1 AND 24)
);

CREATE INDEX hour_credit_trooper_idx ON hour_credit (trooper_id);
