-- Immutable user-confirmable proposal snapshots, also the durable AI rename audit trail.
-- The model cannot execute anything. Only the authenticated owner can apply an unexpired row once.
CREATE TABLE ai_rename_proposal (
    id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    user_file_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    old_name VARCHAR(512) NOT NULL,
    new_name VARCHAR(512) NOT NULL,
    original_updated_at DATETIME(3) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NOT NULL,
    applied_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_agent_proposal_user (user_id,created_at),
    KEY idx_agent_proposal_expiry (status,expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
