-- Optional derived vectors; deleting a knowledge chunk automatically invalidates its vector.
CREATE TABLE knowledge_vector (
 file_id VARCHAR(64) NOT NULL,
 generation BIGINT NOT NULL,
 chunk_index INT NOT NULL,
 model_key CHAR(64) NOT NULL,
 dimensions INT NOT NULL,
 vector_json JSON NOT NULL,
 PRIMARY KEY (file_id,generation,chunk_index,model_key),
 CONSTRAINT fk_knowledge_vector_chunk FOREIGN KEY (file_id,generation,chunk_index)
   REFERENCES knowledge_chunk(file_id,generation,chunk_index) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Each authenticated user opts in separately. A job is durable and claim-fenced.
CREATE TABLE knowledge_embedding_job (
 file_id VARCHAR(64) NOT NULL,
 generation BIGINT NOT NULL,
 model_key CHAR(64) NOT NULL,
 user_id BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 next_index INT NOT NULL DEFAULT 0,
 attempts INT NOT NULL DEFAULT 0,
 claim_token VARCHAR(36) NULL,
 lease_until DATETIME(6) NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY (file_id,generation,model_key,user_id),
 KEY idx_embedding_jobs (status,lease_until,updated_at),
 CONSTRAINT fk_embedding_job_document FOREIGN KEY (file_id)
    REFERENCES knowledge_document(file_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
