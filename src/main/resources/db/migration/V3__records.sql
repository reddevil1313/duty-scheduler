-- V3: opening hours (before the app was used) and credited hours (counted
-- for a logged day but not on the grid).

ALTER TABLE trooper ADD COLUMN opening_hours INTEGER NOT NULL DEFAULT 0;
ALTER TABLE trooper ADD CONSTRAINT trooper_opening_hours_nonnegative CHECK (opening_hours >= 0);

-- Deleted with its sheet, never with its Trooper.
CREATE TABLE hour_credit (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sheet_id    BIGINT  NOT NULL REFERENCES duty_sheet(id) ON DELETE CASCADE,
    trooper_id  BIGINT  NOT NULL REFERENCES trooper(id),
    hours       INTEGER NOT NULL,
    CONSTRAINT hour_credit_once_per_sheet UNIQUE (sheet_id, trooper_id),
    CONSTRAINT hour_credit_hours_in_a_day CHECK (hours BETWEEN 1 AND 24)
);

CREATE INDEX hour_credit_trooper_idx ON hour_credit (trooper_id);
