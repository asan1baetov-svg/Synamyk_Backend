-- ============================================================================
-- Feature 1: independent sub-test purchase (each sub-test has its own price)
-- Feature 2: "free window" schedule on test and sub-test
-- ============================================================================

-- 1. Per-sub-test price ---------------------------------------------------------
ALTER TABLE sub_tests
    ADD COLUMN IF NOT EXISTS price NUMERIC(10, 2) NOT NULL DEFAULT 0;

-- Backfill: already-paid sub-tests inherit the parent test price so the feature
-- does not silently unlock them for free.
UPDATE sub_tests s
SET price = COALESCE((SELECT t.price FROM tests t WHERE t.id = s.test_id), 0)
WHERE s.is_paid = TRUE AND s.price = 0;

-- 2. Free-window schedule on test and sub-test --------------------------------
ALTER TABLE tests
    ADD COLUMN IF NOT EXISTS free_from  TIMESTAMP,
    ADD COLUMN IF NOT EXISTS free_until TIMESTAMP;

ALTER TABLE sub_tests
    ADD COLUMN IF NOT EXISTS free_from  TIMESTAMP,
    ADD COLUMN IF NOT EXISTS free_until TIMESTAMP;

-- 3. Sub-test level access (mirror of user_test_access) -----------------------
CREATE TABLE IF NOT EXISTS user_sub_test_access
(
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    sub_test_id BIGINT    NOT NULL REFERENCES sub_tests (id) ON DELETE CASCADE,
    granted_at  TIMESTAMP NOT NULL DEFAULT now(),
    expires_at  TIMESTAMP,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_sub_test UNIQUE (user_id, sub_test_id)
);
CREATE INDEX IF NOT EXISTS idx_user_sub_test_access_expires ON user_sub_test_access (expires_at);
CREATE INDEX IF NOT EXISTS idx_user_sub_test_access_user    ON user_sub_test_access (user_id);

-- 4. Payment can target a single sub-test (NULL = whole-test bundle, as before)
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS sub_test_id BIGINT REFERENCES sub_tests (id);
