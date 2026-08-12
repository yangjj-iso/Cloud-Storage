CREATE TABLE IF NOT EXISTS user_account (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username VARCHAR(32) NOT NULL,
    email VARCHAR(128) DEFAULT NULL,
    password_hash VARCHAR(256) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'user',
    status TINYINT NOT NULL DEFAULT 1,
    last_login_at DATETIME(3) DEFAULT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_email (email),
    KEY idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS file_meta (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    file_id CHAR(32) NOT NULL,
    file_md5 CHAR(32) NOT NULL,
    file_name VARCHAR(512) NOT NULL,
    file_size BIGINT UNSIGNED NOT NULL,
    mime_type VARCHAR(128) NOT NULL,
    ext VARCHAR(16) DEFAULT NULL,
    storage_type VARCHAR(16) NOT NULL DEFAULT 'minio',
    bucket VARCHAR(64) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    transcode_status TINYINT NOT NULL DEFAULT 0,
    thumbnail_url VARCHAR(512) DEFAULT NULL,
    extra JSON DEFAULT NULL,
    owner_id BIGINT UNSIGNED NOT NULL,
    ref_count INT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at DATETIME(3) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_id (file_id),
    KEY idx_md5_status (file_md5, status),
    KEY idx_owner_created (owner_id, created_at),
    KEY idx_status_transcode (status, transcode_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS file_reference (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    file_id CHAR(32) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    file_name VARCHAR(512) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_user (file_id, user_id),
    KEY idx_user_file (user_id, file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chunk_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    file_id CHAR(32) NOT NULL,
    chunk_index INT UNSIGNED NOT NULL,
    chunk_md5 CHAR(32) NOT NULL,
    chunk_size INT UNSIGNED NOT NULL,
    etag VARCHAR(64) DEFAULT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_chunk (file_id, chunk_index),
    KEY idx_file_status (file_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS upload_session (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    file_id CHAR(32) NOT NULL,
    file_md5 CHAR(32) NOT NULL,
    file_name VARCHAR(512) NOT NULL,
    file_size BIGINT UNSIGNED NOT NULL,
    mime_type VARCHAR(128) NOT NULL DEFAULT 'application/octet-stream',
    chunk_size INT UNSIGNED NOT NULL,
    chunk_total INT UNSIGNED NOT NULL,
    owner_id BIGINT UNSIGNED NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    bucket VARCHAR(64) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    expire_at DATETIME NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_upload_file_id (file_id),
    KEY idx_upload_md5 (file_md5),
    KEY idx_owner_expire (owner_id, expire_at),
    KEY idx_status_expire (status, expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_quota (
    user_id BIGINT UNSIGNED NOT NULL,
    total_bytes BIGINT UNSIGNED NOT NULL DEFAULT 0,
    used_bytes BIGINT UNSIGNED NOT NULL DEFAULT 0,
    file_count INT UNSIGNED NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS transcode_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    file_id CHAR(32) NOT NULL,
    task_type VARCHAR(16) NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    retry_count INT UNSIGNED NOT NULL DEFAULT 0,
    result JSON DEFAULT NULL,
    error_msg VARCHAR(1024) DEFAULT NULL,
    started_at DATETIME(3) DEFAULT NULL,
    finished_at DATETIME(3) DEFAULT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_transcode_file_id (file_id),
    KEY idx_status_type (status, task_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_file (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    file_id CHAR(32) DEFAULT NULL,
    parent_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    file_name VARCHAR(512) NOT NULL,
    is_dir TINYINT(1) NOT NULL DEFAULT 0,
    file_size BIGINT UNSIGNED NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 0,
    deleted_at DATETIME(3) DEFAULT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_user_parent (user_id, parent_id, status),
    KEY idx_user_file (user_id, file_id),
    KEY idx_user_file_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS file_share (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    share_id VARCHAR(32) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    user_file_id BIGINT UNSIGNED NOT NULL,
    file_id CHAR(32) DEFAULT NULL,
    share_code VARCHAR(128) NOT NULL,
    expire_at DATETIME(3) DEFAULT NULL,
    view_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    save_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_share_id (share_id),
    KEY idx_user_share (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS email_verification (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    email VARCHAR(128) NOT NULL,
    code VARCHAR(8) NOT NULL,
    type VARCHAR(16) NOT NULL,
    used TINYINT(1) NOT NULL DEFAULT 0,
    expire_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_email_type (email, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS system_setting (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    setting_key VARCHAR(64) NOT NULL,
    setting_value TEXT DEFAULT NULL,
    updated_by BIGINT UNSIGNED DEFAULT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_setting_key (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS op_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED DEFAULT NULL,
    op_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(64) DEFAULT NULL,
    ip VARCHAR(64) DEFAULT NULL,
    ua VARCHAR(512) DEFAULT NULL,
    params JSON DEFAULT NULL,
    result TINYINT NOT NULL,
    cost_ms INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_user_time (user_id, created_at),
    KEY idx_op_time (op_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO user_quota (user_id, total_bytes, used_bytes, file_count)
VALUES (1, 107374182400, 0, 0)
ON DUPLICATE KEY UPDATE total_bytes = VALUES(total_bytes);
