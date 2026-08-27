-- Time-limited test access. NULL = permanent (purchases, legacy rows).
ALTER TABLE user_test_access
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_user_test_access_expires
    ON user_test_access (expires_at);
