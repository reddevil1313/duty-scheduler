-- V4: the turnout a sheet was logged with. Null is the free split.

ALTER TABLE duty_sheet ADD COLUMN turnout INTEGER;
ALTER TABLE duty_sheet ADD CONSTRAINT duty_sheet_turnout_range CHECK (turnout BETWEEN 5 AND 7);
