CREATE TABLE vocab_card (
    id            BIGSERIAL PRIMARY KEY,
    word          TEXT    NOT NULL,
    word_key      TEXT    NOT NULL UNIQUE,
    article       TEXT    NOT NULL DEFAULT '',
    plural        TEXT    NOT NULL DEFAULT '',
    meaning       TEXT    NOT NULL,
    example       TEXT    NOT NULL,
    theme         TEXT    NOT NULL CHECK (theme IN ('it', 'alltag', 'redewendung')),
    source        TEXT    NOT NULL CHECK (source IN ('daily', 'mistake')),
    ease          DOUBLE PRECISION NOT NULL DEFAULT 2.5,
    interval_days INT     NOT NULL DEFAULT 0,
    reps          INT     NOT NULL DEFAULT 0,
    due_on        DATE    NOT NULL,
    created_on    DATE    NOT NULL,
    last_grade    INT
);
CREATE INDEX vocab_card_due ON vocab_card (due_on, id);
CREATE INDEX vocab_card_created ON vocab_card (created_on);

ALTER TABLE usage_day ADD COLUMN vocab_reviews INT NOT NULL DEFAULT 0;
