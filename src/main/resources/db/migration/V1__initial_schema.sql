-- ---------------------------------------------------------------------------
-- V1 — the duty scheduler's first schema.
-- ---------------------------------------------------------------------------

-- Who is on the team.
CREATE TABLE trooper (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        TEXT        NOT NULL UNIQUE,
    rank        TEXT,
    full_name   TEXT,
    stay_out    BOOLEAN     NOT NULL DEFAULT FALSE,
    perm_pac    BOOLEAN     NOT NULL DEFAULT FALSE,
    on_night    BOOLEAN     NOT NULL DEFAULT FALSE,
    remark      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One sheet per duty date. The date is the natural key and is unique, but the
-- table still carries a surrogate id.
CREATE TABLE duty_sheet (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    duty_date   DATE        NOT NULL UNIQUE,
    note        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  TEXT
);

-- One row per man per hour.
CREATE TABLE duty_assignment (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sheet_id    BIGINT      NOT NULL REFERENCES duty_sheet(id) ON DELETE CASCADE,
    trooper_id  BIGINT      NOT NULL REFERENCES trooper(id),
    slot        SMALLINT    NOT NULL,
    post        TEXT        NOT NULL,
    CONSTRAINT duty_assignment_slot_range CHECK (slot BETWEEN 0 AND 23),
    CONSTRAINT duty_assignment_post_known CHECK (post IN ('AP', 'GG', 'PAC', 'BUS')),
    -- A man stands one post at a time.
    CONSTRAINT duty_assignment_one_post_per_hour UNIQUE (sheet_id, trooper_id, slot)
);

CREATE INDEX duty_assignment_sheet_idx   ON duty_assignment (sheet_id);
CREATE INDEX duty_assignment_trooper_idx ON duty_assignment (trooper_id);

-- Leave, as an interval rather than a set of day flags.
--   FULL        whole days, midnight on from_date to 2000 on to_date
--   DAY_HALF    0800-2000 on from_date
--   NIGHT_HALF  2000 on from_date to 0800 the next morning
--   WINDOW      start_time to end_time on from_date
CREATE TABLE absence (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    trooper_id  BIGINT      NOT NULL REFERENCES trooper(id) ON DELETE CASCADE,
    kind        TEXT        NOT NULL,
    span        TEXT        NOT NULL,
    from_date   DATE        NOT NULL,
    to_date     DATE        NOT NULL,
    start_time  TIME,
    end_time    TIME,
    note        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  TEXT,
    CONSTRAINT absence_kind_known CHECK (kind IN ('MC', 'OL', 'AL', 'MA')),
    CONSTRAINT absence_span_known CHECK (span IN ('FULL', 'DAY_HALF', 'NIGHT_HALF', 'WINDOW')),
    CONSTRAINT absence_dates_ordered CHECK (to_date >= from_date),
    -- Only a full-day booking spans more than one date, and only a window has
    -- times.
    CONSTRAINT absence_shape CHECK (
        (span = 'FULL'   AND start_time IS NULL AND end_time IS NULL)
     OR (span IN ('DAY_HALF', 'NIGHT_HALF') AND from_date = to_date
                          AND start_time IS NULL AND end_time IS NULL)
     OR (span = 'WINDOW' AND from_date = to_date
                          AND start_time IS NOT NULL AND end_time IS NOT NULL
                          AND end_time > start_time)
    )
);

CREATE INDEX absence_range_idx   ON absence (from_date, to_date);
CREATE INDEX absence_trooper_idx ON absence (trooper_id);

-- Accounts.
CREATE TABLE app_user (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      TEXT        NOT NULL UNIQUE,
    display_name  TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    role          TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT app_user_role_known CHECK (role IN ('ADMIN', 'USER'))
);

-- The unit's own paperwork.
CREATE TABLE team_config (
    id            SMALLINT    PRIMARY KEY DEFAULT 1,
    unit          TEXT        NOT NULL DEFAULT '',
    min_req       TEXT        NOT NULL DEFAULT '0:0:5:0',
    cycle_start   DATE,
    cycle_in      SMALLINT    NOT NULL DEFAULT 3,
    cycle_length  SMALLINT    NOT NULL DEFAULT 6,
    stay_in_note  TEXT        NOT NULL DEFAULT 'Ex. Stay in',
    weekend_note  TEXT        NOT NULL DEFAULT 'Weekend Dismount',
    template      TEXT        NOT NULL DEFAULT '',
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT team_config_single_row CHECK (id = 1)
);

INSERT INTO team_config (id) VALUES (1);
