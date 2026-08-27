-- ============================================================
-- Push: targeting, scheduling, localization, in-app inbox
-- Progress: (columns added here are read by TestService/GameService)
-- ============================================================

-- 1. push_broadcasts: async lifecycle + targeting + localization
ALTER TABLE push_broadcasts
    ADD COLUMN IF NOT EXISTS status         VARCHAR(20)  NOT NULL DEFAULT 'SENT',
    ADD COLUMN IF NOT EXISTS title_ky       VARCHAR(255),
    ADD COLUMN IF NOT EXISTS body_ky        TEXT,
    ADD COLUMN IF NOT EXISTS audience       VARCHAR(30)  NOT NULL DEFAULT 'ALL',
    ADD COLUMN IF NOT EXISTS audience_ref   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS data_type      VARCHAR(30),
    ADD COLUMN IF NOT EXISTS data_entity_id BIGINT,
    ADD COLUMN IF NOT EXISTS scheduled_at   TIMESTAMP,
    ADD COLUMN IF NOT EXISTS started_at     TIMESTAMP,
    ADD COLUMN IF NOT EXISTS finished_at    TIMESTAMP,
    ADD COLUMN IF NOT EXISTS dedup_key      VARCHAR(64);

-- counts are filled in only after the async send completes
ALTER TABLE push_broadcasts ALTER COLUMN recipient_count DROP NOT NULL;
ALTER TABLE push_broadcasts ALTER COLUMN success_count   DROP NOT NULL;
ALTER TABLE push_broadcasts ALTER COLUMN failure_count   DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_push_broadcasts_status_scheduled
    ON push_broadcasts (status, scheduled_at);
CREATE INDEX IF NOT EXISTS idx_push_broadcasts_sent_by
    ON push_broadcasts (sent_by);
CREATE UNIQUE INDEX IF NOT EXISTS uq_push_broadcasts_dedup
    ON push_broadcasts (dedup_key) WHERE dedup_key IS NOT NULL;

-- 2. device_tokens: last-seen for pruning + targeting indexes
ALTER TABLE device_tokens
    ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_device_tokens_platform  ON device_tokens (platform);
CREATE INDEX IF NOT EXISTS idx_device_tokens_last_seen ON device_tokens (last_seen_at);

-- 3. per-user notification opt-out flags (row created lazily, all-on by default)
CREATE TABLE IF NOT EXISTS user_notification_settings
(
    user_id    BIGINT PRIMARY KEY REFERENCES users (id),
    results    BOOLEAN   NOT NULL DEFAULT true,
    reminders  BOOLEAN   NOT NULL DEFAULT true,
    marketing  BOOLEAN   NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- 4. in-app notification inbox
CREATE TABLE IF NOT EXISTS user_notifications
(
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT       NOT NULL REFERENCES users (id),
    title          VARCHAR(255) NOT NULL,
    body           TEXT         NOT NULL,
    data_type      VARCHAR(30),
    data_entity_id BIGINT,
    broadcast_id   BIGINT       REFERENCES push_broadcasts (id),
    read_at        TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_user_notifications_user_unread
    ON user_notifications (user_id, read_at, created_at DESC);
