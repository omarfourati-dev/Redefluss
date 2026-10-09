CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    email         TEXT        NOT NULL UNIQUE,
    password_hash TEXT        NOT NULL,
    token_version INT         NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- "correct" instead of "right": RIGHT is an SQL keyword
CREATE TABLE mistake (
    id         BIGSERIAL PRIMARY KEY,
    category   TEXT        NOT NULL,
    wrong      TEXT        NOT NULL,
    correct    TEXT        NOT NULL,
    rule       TEXT        NOT NULL,
    example    TEXT        NOT NULL,
    normalized TEXT        NOT NULL UNIQUE,
    count      INT         NOT NULL DEFAULT 1,
    first_seen TIMESTAMPTZ NOT NULL,
    last_seen  TIMESTAMPTZ NOT NULL,
    resolved   BOOLEAN     NOT NULL DEFAULT false
);
CREATE INDEX mistake_rank ON mistake (resolved, count DESC, last_seen DESC);

CREATE TABLE practice_session (
    id         UUID PRIMARY KEY,
    mode       TEXT        NOT NULL CHECK (mode IN ('conversation', 'interview', 'pronunciation', 'vocabulary')),
    topic      TEXT        NOT NULL DEFAULT '',
    started_at TIMESTAMPTZ NOT NULL,
    last_at    TIMESTAMPTZ NOT NULL,
    turns      INT         NOT NULL DEFAULT 0,
    mistakes   INT         NOT NULL DEFAULT 0,
    summary    TEXT        NOT NULL DEFAULT ''
);
CREATE INDEX practice_session_started ON practice_session (started_at);

CREATE TABLE usage_day (
    day             DATE PRIMARY KEY,
    turns           INT NOT NULL DEFAULT 0,
    live_seconds    INT NOT NULL DEFAULT 0,
    pronunciations  INT NOT NULL DEFAULT 0,
    azure_seconds   INT NOT NULL DEFAULT 0,
    vocab_generated INT NOT NULL DEFAULT 0
);
