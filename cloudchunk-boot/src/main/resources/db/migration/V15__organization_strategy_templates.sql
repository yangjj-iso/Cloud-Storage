-- M4.2: private deterministic organization policies and short-lived dry-run snapshots.
-- No AI model output is executable. No bulk apply or automatic job scheduling.
CREATE TABLE ai_organize_template (
    id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    mime_category VARCHAR(12) NOT NULL,
    destination_id BIGINT NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_organize_template_owner (user_id,status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_organize_run (
    id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    template_id CHAR(36) NOT NULL,
    template_updated_at DATETIME(3) NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE',
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_organize_run_user (user_id,created_at),
    CONSTRAINT fk_organize_run_template FOREIGN KEY (template_id) REFERENCES ai_organize_template(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_organize_run_step (
    run_id CHAR(36) NOT NULL,
    step_no INT NOT NULL,
    user_file_id BIGINT NOT NULL,
    original_name VARCHAR(512) NOT NULL,
    original_parent_id BIGINT NOT NULL,
    source_updated_at DATETIME(3) NOT NULL,
    dest_updated_at DATETIME(3) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'READY',
    PRIMARY KEY (run_id,step_no),
    UNIQUE KEY uk_organize_run_file (run_id,user_file_id),
    CONSTRAINT fk_organize_step_run FOREIGN KEY (run_id) REFERENCES ai_organize_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_organize_run_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    step_no INT NULL,
    action VARCHAR(20) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_organize_events (run_id,id),
    CONSTRAINT fk_organize_event_run FOREIGN KEY (run_id) REFERENCES ai_organize_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
