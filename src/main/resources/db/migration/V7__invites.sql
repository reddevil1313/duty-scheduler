-- ---------------------------------------------------------------------------
-- V7 — invite links.
--
-- How anyone but the owner gets an account. An admin (or the owner, for a
-- team's first admin) makes an invite for a role; the link goes out by chat,
-- since there is no email; whoever opens it picks their own username and
-- password and joins that team. No one ever knows anyone else's password.
--
-- The token is never stored, only its SHA-256: a copy of this table, from a
-- backup or a leak, holds no link that works. SHA-256 rather than bcrypt
-- because the token is 32 random bytes, not a password — there is nothing to
-- guess, and it has to be found by hash, which a salted hash cannot be.
--
-- An invite is used once. used_at is set in the same statement that checks it
-- is still unset, so two people opening the same link cannot both join.
-- ---------------------------------------------------------------------------

CREATE TABLE invite (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    team_id     BIGINT      NOT NULL REFERENCES team (id) ON DELETE CASCADE,
    role        TEXT        NOT NULL,
    token_hash  TEXT        NOT NULL UNIQUE,
    created_by  TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    used_by     TEXT,
    -- The owner is made at startup, never invited.
    CONSTRAINT invite_role_known CHECK (role IN ('ADMIN', 'USER')),
    CONSTRAINT invite_used_by_whom CHECK ((used_at IS NULL) = (used_by IS NULL))
);
CREATE INDEX invite_team_idx ON invite (team_id);
