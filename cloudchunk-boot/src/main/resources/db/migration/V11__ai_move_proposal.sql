-- AI move proposals are immutable, user-owned, expiring, one-time confirmations.
-- The model never gets a file operation tool; execution requires live MySQL ACL.
CREATE TABLE ai_move_proposal (
    id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    user_file_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    original_name VARCHAR(512) NOT NULL,
    source_parent_id BIGINT NOT NULL,
    source_updated_at DATETIME(3) NOT NULL,
    destination_id BIGINT NOT NULL,
    destination_name VARCHAR(512) NOT NULL,
    destination_parent_id BIGINT NOT NULL,
    destination_updated_at DATETIME(3) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NOT NULL,
    applied_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_ai_move_user_time (user_id,created_at),
    KEY idx_ai_move_expiry (status,expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
