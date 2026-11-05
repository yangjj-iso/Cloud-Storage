-- M4.5: explicit opt-out for future strategy recommendation calls.
-- Existing one-request consent is still required even when policy allows.
CREATE TABLE ai_strategy_user_policy (
    user_id BIGINT NOT NULL,
    allow_recommendations TINYINT(1) NOT NULL DEFAULT 1,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- NULL on legacy V16 rows means unknown source generation, fail closed when
-- showing a recommendation or submitting feedback until it is regenerated.
ALTER TABLE ai_strategy_recommendation
    ADD COLUMN source_generation BIGINT NULL;

-- Only user-submitted review cases; never store document name, prompt, source
-- excerpt, user-written reason, model answer or storage path.
CREATE TABLE ai_strategy_feedback_review (
    id CHAR(36) NOT NULL,
    recommendation_id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'REQUESTED',
    review_code VARCHAR(24) NULL,
    reviewer_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    reviewed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_strategy_review_recommendation (recommendation_id),
    KEY idx_strategy_review_queue (status,created_at),
    KEY idx_strategy_review_owner (user_id,created_at),
    CONSTRAINT fk_strategy_review_recommendation
       FOREIGN KEY (recommendation_id) REFERENCES ai_strategy_recommendation(id)
       ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
