ALTER TABLE transcode_record
    ADD COLUMN event_id CHAR(32) NULL AFTER id,
    ADD UNIQUE KEY uk_transcode_event_task (event_id, task_type);

CREATE TABLE event_outbox (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_id CHAR(32) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    schema_version INT UNSIGNED NOT NULL DEFAULT 1,
    destination VARCHAR(128) NOT NULL,
    aggregate_key VARCHAR(128) NOT NULL,
    payload JSON NOT NULL,
    trace_id VARCHAR(64) DEFAULT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=pending 1=publishing 2=published 3=retry',
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_by VARCHAR(128) DEFAULT NULL,
    locked_at DATETIME(3) DEFAULT NULL,
    last_error VARCHAR(1024) DEFAULT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at DATETIME(3) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (event_id),
    KEY idx_outbox_ready (status, next_attempt_at),
    KEY idx_outbox_locked (status, locked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE event_inbox (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(96) NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=processing 1=done 2=failed',
    attempt_count INT UNSIGNED NOT NULL DEFAULT 1,
    locked_at DATETIME(3) NOT NULL,
    last_error VARCHAR(1024) DEFAULT NULL,
    processed_at DATETIME(3) DEFAULT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_inbox_event_id (event_id),
    KEY idx_inbox_status_lock (status, locked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE object_gc_task (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    file_id CHAR(32) DEFAULT NULL,
    bucket VARCHAR(64) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    mark_file_deleted TINYINT(1) NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=pending 1=processing 2=done 3=retry',
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_by VARCHAR(128) DEFAULT NULL,
    locked_at DATETIME(3) DEFAULT NULL,
    last_error VARCHAR(1024) DEFAULT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_gc_object_action (bucket, object_key, mark_file_deleted),
    KEY idx_gc_ready (status, next_attempt_at),
    KEY idx_gc_locked (status, locked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
