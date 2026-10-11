-- M4.3: user-owned AI-to-template recommendations with bounded categorical feedback.
-- Never stores evidence text, prompts, raw model output, credentials or file paths.
-- A rating records preference only. It cannot move a file or retrain a model.
CREATE TABLE ai_strategy_recommendation (
    id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    template_id CHAR(36) NOT NULL,
    move_proposal_id CHAR(36) NOT NULL,
    user_file_id BIGINT NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    feedback_code VARCHAR(28) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    feedback_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_strategy_recommendation_proposal (move_proposal_id),
    KEY idx_strategy_recommendation_owner (user_id,created_at),
    CONSTRAINT fk_strategy_recommendation_template
        FOREIGN KEY (template_id) REFERENCES ai_organize_template(id),
    CONSTRAINT fk_strategy_recommendation_move
        FOREIGN KEY (move_proposal_id) REFERENCES ai_move_proposal(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
