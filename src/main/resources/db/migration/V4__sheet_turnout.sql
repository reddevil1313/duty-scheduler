-- ---------------------------------------------------------------------------
-- V4 — which turnout a logged sheet was made with.
--
-- Null is the free split. A number is a fixed turnout: everyone did that many
-- hours and the MP team covered the rest. The MP team's hours themselves are
-- still not stored — they are whatever the sheet's posts leave uncovered — but
-- knowing the turnout is what tells an MP hour from a mistake, and what lets a
-- new sheet reuse a logged one made the same way.
-- ---------------------------------------------------------------------------

ALTER TABLE duty_sheet ADD COLUMN turnout INTEGER;
ALTER TABLE duty_sheet ADD CONSTRAINT duty_sheet_turnout_range CHECK (turnout BETWEEN 5 AND 7);
