-- User-owned model consent event receipts and operator-governed retention evidence.
-- No document text, file ID, prompt, model response, credential or raw endpoint.
CREATE TABLE ai_strategy_consent_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    event_code VARCHAR(24) NOT NULL,
    candidate_count SMALLINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_strategy_consent_owner (user_id,created_at),
    KEY idx_strategy_consent_retention (created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_strategy_retention_run (
    id CHAR(36) NOT NULL,
    operator_user_id BIGINT NOT NULL,
    change_ticket VARCHAR(64) NOT NULL,
    expired_recommendations BIGINT NOT NULL,
    expired_consent_events BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_strategy_retention_receipt (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
