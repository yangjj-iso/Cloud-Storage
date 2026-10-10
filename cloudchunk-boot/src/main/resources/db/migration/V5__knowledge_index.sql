-- Derived knowledge is separate from file availability and can always be rebuilt.
CREATE TABLE knowledge_document (
    file_id VARCHAR(64) NOT NULL PRIMARY KEY,
    parser_version VARCHAR(64) NOT NULL DEFAULT 'tika-3.3.2-blocks-v1',
    generation BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    content_sha256 CHAR(64) NULL,
    content_chars INT NOT NULL DEFAULT 0,
    chunk_count INT NOT NULL DEFAULT 0,
    attempt_count INT NOT NULL DEFAULT 0,
    claim_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    reason VARCHAR(128) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_knowledge_status (status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE knowledge_chunk (
    file_id VARCHAR(64) NOT NULL,
    generation BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    start_offset INT NOT NULL,
    end_offset INT NOT NULL,
    content TEXT NOT NULL,
    PRIMARY KEY (file_id, generation, chunk_index),
    CONSTRAINT fk_knowledge_document FOREIGN KEY (file_id)
        REFERENCES knowledge_document(file_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
