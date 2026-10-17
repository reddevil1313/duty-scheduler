-- V7: invite links. Only the token's SHA-256 is stored (it is random, so no
-- salt is needed, and it must be looked up by hash). Single use.

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
