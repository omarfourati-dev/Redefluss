-- Gäste per Einladung (docs/superpowers/specs/2026-10-22-gaeste-design.md): mehrere Konten, jedes mit eigenen Daten.
-- Bisher gab es genau ein Konto, das des Besitzers; ihm gehören alle vorhandenen Zeilen.

ALTER TABLE app_user ADD COLUMN role TEXT NOT NULL DEFAULT 'guest' CHECK (role IN ('owner', 'guest'));
UPDATE app_user SET role = 'owner';

ALTER TABLE mistake          ADD COLUMN user_id BIGINT REFERENCES app_user (id) ON DELETE CASCADE;
ALTER TABLE practice_session ADD COLUMN user_id BIGINT REFERENCES app_user (id) ON DELETE CASCADE;
ALTER TABLE vocab_card       ADD COLUMN user_id BIGINT REFERENCES app_user (id) ON DELETE CASCADE;
ALTER TABLE usage_day        ADD COLUMN user_id BIGINT REFERENCES app_user (id) ON DELETE CASCADE;

UPDATE mistake          SET user_id = (SELECT min(id) FROM app_user);
UPDATE practice_session SET user_id = (SELECT min(id) FROM app_user);
UPDATE vocab_card       SET user_id = (SELECT min(id) FROM app_user);
UPDATE usage_day        SET user_id = (SELECT min(id) FROM app_user);

ALTER TABLE mistake          ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE practice_session ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE vocab_card       ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE usage_day        ALTER COLUMN user_id SET NOT NULL;

-- Eindeutigkeit und Indizes jetzt pro Konto
ALTER TABLE mistake DROP CONSTRAINT mistake_normalized_key;
ALTER TABLE mistake ADD CONSTRAINT mistake_user_normalized UNIQUE (user_id, normalized);
DROP INDEX mistake_rank;
CREATE INDEX mistake_rank ON mistake (user_id, resolved, count DESC, last_seen DESC);

DROP INDEX practice_session_started;
CREATE INDEX practice_session_started ON practice_session (user_id, started_at);

ALTER TABLE vocab_card DROP CONSTRAINT vocab_card_word_key_key;
ALTER TABLE vocab_card ADD CONSTRAINT vocab_card_user_word UNIQUE (user_id, word_key);
DROP INDEX vocab_card_due;
CREATE INDEX vocab_card_due ON vocab_card (user_id, due_on, id);
DROP INDEX vocab_card_created;
CREATE INDEX vocab_card_created ON vocab_card (user_id, created_on);

ALTER TABLE usage_day DROP CONSTRAINT usage_day_pkey;
ALTER TABLE usage_day ADD PRIMARY KEY (user_id, day);
CREATE INDEX usage_day_day ON usage_day (day);

-- Einladungen: gespeichert wird nur der SHA-256 des Tokens, nie das Token selbst
CREATE TABLE invitation (
    id         BIGSERIAL PRIMARY KEY,
    email      TEXT        NOT NULL,
    token_hash TEXT        NOT NULL UNIQUE,
    created_by BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ
);
CREATE INDEX invitation_created ON invitation (created_at DESC);
