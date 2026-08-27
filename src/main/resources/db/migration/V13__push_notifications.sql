CREATE TABLE IF NOT EXISTS device_tokens
(
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id),
    token      VARCHAR(512) NOT NULL UNIQUE,
    platform   VARCHAR(20)  NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_device_tokens_user_id ON device_tokens (user_id);

CREATE TABLE IF NOT EXISTS push_broadcasts
(
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(255) NOT NULL,
    body            TEXT         NOT NULL,
    sent_by         BIGINT REFERENCES users (id),
    recipient_count INTEGER      NOT NULL,
    success_count   INTEGER      NOT NULL,
    failure_count   INTEGER      NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_push_broadcasts_created_at ON push_broadcasts (created_at DESC);
