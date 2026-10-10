-- Strict per-user UTC-day call ceilings shared across API and Worker processes.
-- Charged before provider calls, including failed attempts.
CREATE TABLE ai_model_daily_usage (
    user_id BIGINT NOT NULL,
    usage_date DATE NOT NULL,
    model_kind VARCHAR(16) NOT NULL,
    used_calls INT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, usage_date, model_kind),
    KEY idx_ai_usage_retention (usage_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
