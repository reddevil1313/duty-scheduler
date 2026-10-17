-- ---------------------------------------------------------------------------
-- V6 — teams.
--
-- One server, many teams, and no team sees another's roster, sheets, leave or
-- records. Every one of those rows now carries the team it belongs to, and
-- what was unique for the whole database is unique within a team: two teams
-- can each have a TROOPER_A, and each log a sheet for the same evening.
--
-- The application filters every read by the signed-in account's team. The
-- database holds the other half: the composite foreign keys below make it
-- impossible for a sheet, a leave booking or a credit to point at another
-- team's Trooper, whatever the application does. Each child table carries
-- team_id itself, rather than reaching it through its parent, so that the
-- key can be checked.
--
-- Existing data goes into one team, created only when there is data to put
-- in it, so a fresh database starts with no teams at all.
-- ---------------------------------------------------------------------------

CREATE TABLE team (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Case-insensitive, so the owner cannot end up with "Alpha" and "alpha".
CREATE UNIQUE INDEX team_name_unique ON team (lower(name));

INSERT INTO team (name)
SELECT 'First team'
 WHERE EXISTS (SELECT 1 FROM trooper)
    OR EXISTS (SELECT 1 FROM duty_sheet)
    OR EXISTS (SELECT 1 FROM app_user);

-- ---- accounts --------------------------------------------------------------
-- Every account but the owner's belongs to exactly one team. The owner runs
-- the server, creates the teams, and belongs to none of them.
ALTER TABLE app_user ADD COLUMN team_id BIGINT REFERENCES team (id);
UPDATE app_user SET team_id = (SELECT min(id) FROM team);
ALTER TABLE app_user DROP CONSTRAINT app_user_role_known;
ALTER TABLE app_user ADD CONSTRAINT app_user_role_known CHECK (role IN ('OWNER', 'ADMIN', 'USER'));
ALTER TABLE app_user ADD CONSTRAINT app_user_team_unless_owner CHECK ((role = 'OWNER') = (team_id IS NULL));
CREATE INDEX app_user_team_idx ON app_user (team_id);

-- ---- the roster --------------------------------------------------------------
ALTER TABLE trooper ADD COLUMN team_id BIGINT REFERENCES team (id);
UPDATE trooper SET team_id = (SELECT min(id) FROM team);
ALTER TABLE trooper ALTER COLUMN team_id SET NOT NULL;
ALTER TABLE trooper DROP CONSTRAINT trooper_name_key;
ALTER TABLE trooper ADD CONSTRAINT trooper_name_per_team UNIQUE (team_id, name);
-- What the composite foreign keys below point at.
ALTER TABLE trooper ADD CONSTRAINT trooper_id_team UNIQUE (id, team_id);
-- One default BUS Trooper per team, not per server.
DROP INDEX trooper_one_default_bus;
CREATE UNIQUE INDEX trooper_one_default_bus ON trooper (team_id) WHERE default_bus;

-- ---- sheets ------------------------------------------------------------------
ALTER TABLE duty_sheet ADD COLUMN team_id BIGINT REFERENCES team (id);
UPDATE duty_sheet SET team_id = (SELECT min(id) FROM team);
ALTER TABLE duty_sheet ALTER COLUMN team_id SET NOT NULL;
ALTER TABLE duty_sheet DROP CONSTRAINT duty_sheet_duty_date_key;
ALTER TABLE duty_sheet ADD CONSTRAINT duty_sheet_date_per_team UNIQUE (team_id, duty_date);
ALTER TABLE duty_sheet ADD CONSTRAINT duty_sheet_id_team UNIQUE (id, team_id);

-- ---- what hangs off a sheet or a Trooper -----------------------------------
ALTER TABLE duty_assignment ADD COLUMN team_id BIGINT;
UPDATE duty_assignment a SET team_id = s.team_id FROM duty_sheet s WHERE s.id = a.sheet_id;
ALTER TABLE duty_assignment ALTER COLUMN team_id SET NOT NULL;
ALTER TABLE duty_assignment DROP CONSTRAINT duty_assignment_sheet_id_fkey;
ALTER TABLE duty_assignment DROP CONSTRAINT duty_assignment_trooper_id_fkey;
ALTER TABLE duty_assignment ADD CONSTRAINT duty_assignment_sheet_same_team
    FOREIGN KEY (sheet_id, team_id) REFERENCES duty_sheet (id, team_id) ON DELETE CASCADE;
ALTER TABLE duty_assignment ADD CONSTRAINT duty_assignment_trooper_same_team
    FOREIGN KEY (trooper_id, team_id) REFERENCES trooper (id, team_id);

ALTER TABLE absence ADD COLUMN team_id BIGINT;
UPDATE absence a SET team_id = t.team_id FROM trooper t WHERE t.id = a.trooper_id;
ALTER TABLE absence ALTER COLUMN team_id SET NOT NULL;
ALTER TABLE absence DROP CONSTRAINT absence_trooper_id_fkey;
ALTER TABLE absence ADD CONSTRAINT absence_trooper_same_team
    FOREIGN KEY (trooper_id, team_id) REFERENCES trooper (id, team_id) ON DELETE CASCADE;
CREATE INDEX absence_team_range_idx ON absence (team_id, from_date, to_date);

ALTER TABLE hour_credit ADD COLUMN team_id BIGINT;
UPDATE hour_credit c SET team_id = s.team_id FROM duty_sheet s WHERE s.id = c.sheet_id;
ALTER TABLE hour_credit ALTER COLUMN team_id SET NOT NULL;
ALTER TABLE hour_credit DROP CONSTRAINT hour_credit_sheet_id_fkey;
ALTER TABLE hour_credit DROP CONSTRAINT hour_credit_trooper_id_fkey;
ALTER TABLE hour_credit ADD CONSTRAINT hour_credit_sheet_same_team
    FOREIGN KEY (sheet_id, team_id) REFERENCES duty_sheet (id, team_id) ON DELETE CASCADE;
ALTER TABLE hour_credit ADD CONSTRAINT hour_credit_trooper_same_team
    FOREIGN KEY (trooper_id, team_id) REFERENCES trooper (id, team_id);

-- ---- a leftover ------------------------------------------------------------
-- V1's single-row team settings were never read by anything, and one row of
-- settings for the whole server says the opposite of what this migration does.
DROP TABLE team_config;
