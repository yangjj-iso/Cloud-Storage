-- =====================================================================
-- CloudChunk schema
-- MySQL 8.0, utf8mb4_unicode_ci
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `cloudchunk`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `cloudchunk`;

-- ---------------------------------------------------------------------
-- user_account
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `user_account`;
CREATE TABLE `user_account` (
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `username`        VARCHAR(32)     NOT NULL COMMENT '登录名，小写字母/数字/下划线',
    `email`           VARCHAR(128)    DEFAULT NULL COMMENT '邮箱，可用于登录',
    `password_hash`   VARCHAR(256)    NOT NULL COMMENT 'Argon2id 哈希（PHC 串）；兼容历史 PBKDF2，登录时透明升级',
    `role`            VARCHAR(16)     NOT NULL DEFAULT 'user' COMMENT 'user | admin',
    `status`          TINYINT         NOT NULL DEFAULT 1 COMMENT '1=启用 0=禁用',
    `last_login_at`   DATETIME(3)     DEFAULT NULL,
    `created_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`),
    KEY `idx_status_created` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录用户';

-- ---------------------------------------------------------------------
-- file_meta
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `file_meta`;
CREATE TABLE `file_meta` (
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `file_id`          CHAR(32)        NOT NULL COMMENT '业务文件 ID',
    `file_md5`         CHAR(32)        NOT NULL COMMENT '整文件 MD5',
    `file_name`        VARCHAR(512)    NOT NULL COMMENT '原始文件名',
    `file_size`        BIGINT UNSIGNED NOT NULL COMMENT '文件大小 byte',
    `mime_type`        VARCHAR(128)    NOT NULL COMMENT 'MIME 类型',
    `ext`              VARCHAR(16)     DEFAULT NULL COMMENT '扩展名',
    `storage_type`     VARCHAR(16)     NOT NULL DEFAULT 'minio' COMMENT 'minio/local/oss',
    `bucket`           VARCHAR(64)     NOT NULL COMMENT '桶名',
    `object_key`       VARCHAR(512)    NOT NULL COMMENT '对象 key',
    `status`           TINYINT         NOT NULL DEFAULT 0 COMMENT '0=上传中 1=已合并 2=已校验 3=损坏 4=已删除',
    `transcode_status` TINYINT         NOT NULL DEFAULT 0 COMMENT '0=未转码 1=转码中 2=成功 3=失败 4=不需要',
    `thumbnail_url`    VARCHAR(512)    DEFAULT NULL COMMENT '缩略图 URL',
    `extra`            JSON            DEFAULT NULL COMMENT '扩展字段',
    `owner_id`         BIGINT UNSIGNED NOT NULL COMMENT '所属用户 ID',
    `ref_count`        INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '引用计数',
    `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted_at`       DATETIME(3)     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_id` (`file_id`),
    KEY `idx_md5_status` (`file_md5`, `status`),
    KEY `idx_owner_created` (`owner_id`, `created_at`),
    KEY `idx_status_transcode` (`status`, `transcode_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件元数据';

-- ---------------------------------------------------------------------
-- file_reference
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `file_reference`;
CREATE TABLE `file_reference` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `file_id`    CHAR(32)        NOT NULL COMMENT '物理文件/共享上传会话 ID',
    `user_id`    BIGINT UNSIGNED NOT NULL COMMENT '拥有或参与该文件的用户 ID',
    `file_name`  VARCHAR(512)    NOT NULL COMMENT '用户侧原始文件名',
    `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_user` (`file_id`, `user_id`),
    KEY `idx_user_file` (`user_id`, `file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户文件引用/共享上传参与者';

-- ---------------------------------------------------------------------
-- chunk_record
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `chunk_record`;
CREATE TABLE `chunk_record` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `file_id`     CHAR(32)        NOT NULL COMMENT '上传会话的 fileId',
    `chunk_index` INT UNSIGNED    NOT NULL COMMENT '分片序号 0-based',
    `chunk_md5`   CHAR(32)        NOT NULL COMMENT '分片 MD5',
    `chunk_size`  INT UNSIGNED    NOT NULL COMMENT '分片大小',
    `etag`        VARCHAR(64)     DEFAULT NULL COMMENT 'MinIO ETag',
    `status`      TINYINT         NOT NULL DEFAULT 0 COMMENT '0=待上传 1=已完成 2=失败',
    `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_chunk` (`file_id`, `chunk_index`),
    KEY `idx_file_status` (`file_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分片记录';

-- ---------------------------------------------------------------------
-- upload_session
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `upload_session`;
CREATE TABLE `upload_session` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `file_id`     CHAR(32)        NOT NULL COMMENT '会话 ID',
    `file_md5`    CHAR(32)        NOT NULL COMMENT '整文件 MD5',
    `file_name`   VARCHAR(512)    NOT NULL,
    `file_size`   BIGINT UNSIGNED NOT NULL,
    `mime_type`   VARCHAR(128)    NOT NULL DEFAULT 'application/octet-stream',
    `chunk_size`  INT UNSIGNED    NOT NULL,
    `chunk_total` INT UNSIGNED    NOT NULL,
    `owner_id`    BIGINT UNSIGNED NOT NULL,
    `status`      TINYINT         NOT NULL DEFAULT 0 COMMENT '0=进行中 1=合并中 2=完成 3=失败 4=过期',
    `bucket`      VARCHAR(64)     NOT NULL,
    `object_key`  VARCHAR(512)    NOT NULL,
    `expire_at`   DATETIME        NOT NULL,
    `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_id` (`file_id`),
    KEY `idx_md5` (`file_md5`),
    KEY `idx_owner_expire` (`owner_id`, `expire_at`),
    KEY `idx_status_expire` (`status`, `expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传会话';

-- ---------------------------------------------------------------------
-- user_quota
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `user_quota`;
CREATE TABLE `user_quota` (
    `user_id`     BIGINT UNSIGNED NOT NULL,
    `total_bytes` BIGINT UNSIGNED NOT NULL DEFAULT 0,
    `used_bytes`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
    `file_count`  INT UNSIGNED    NOT NULL DEFAULT 0,
    `updated_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户配额';

-- ---------------------------------------------------------------------
-- transcode_record
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `transcode_record`;
CREATE TABLE `transcode_record` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `file_id`     CHAR(32)        NOT NULL,
    `task_type`   VARCHAR(16)     NOT NULL COMMENT 'image|video|doc',
    `status`      TINYINT         NOT NULL DEFAULT 0 COMMENT '0=排队 1=执行中 2=成功 3=失败',
    `retry_count` INT UNSIGNED    NOT NULL DEFAULT 0,
    `result`      JSON            DEFAULT NULL,
    `error_msg`   VARCHAR(1024)   DEFAULT NULL,
    `started_at`  DATETIME(3)     DEFAULT NULL,
    `finished_at` DATETIME(3)     DEFAULT NULL,
    `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_file_id` (`file_id`),
    KEY `idx_status_type` (`status`, `task_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='转码任务记录';

-- ---------------------------------------------------------------------
-- user_file （用户网盘目录树 / 回收站）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `user_file`;
CREATE TABLE `user_file` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT UNSIGNED NOT NULL COMMENT '所属用户',
    `file_id`    CHAR(32)        DEFAULT NULL COMMENT '文件节点指向 file_meta.file_id；目录为空',
    `parent_id`  BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '父目录 id，0=根',
    `file_name`  VARCHAR(512)    NOT NULL COMMENT '显示名',
    `is_dir`     TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '1=目录 0=文件',
    `file_size`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
    `status`     TINYINT         NOT NULL DEFAULT 0 COMMENT '0=正常 1=回收站',
    `deleted_at` DATETIME(3)     DEFAULT NULL,
    `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_user_parent` (`user_id`, `parent_id`, `status`),
    KEY `idx_user_file` (`user_id`, `file_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户网盘目录/文件';

-- ---------------------------------------------------------------------
-- file_share （分享链接）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `file_share`;
CREATE TABLE `file_share` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `share_id`     VARCHAR(32)     NOT NULL COMMENT '分享短 ID',
    `user_id`      BIGINT UNSIGNED NOT NULL COMMENT '分享者',
    `user_file_id` BIGINT UNSIGNED NOT NULL COMMENT '被分享的网盘节点 id',
    `file_id`      CHAR(32)        DEFAULT NULL COMMENT '文件节点的 file_id',
    `share_code`   VARCHAR(128)    NOT NULL COMMENT '提取码哈希（兼容历史明文）',
    `expire_at`    DATETIME(3)     DEFAULT NULL COMMENT 'NULL=永久',
    `view_count`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
    `save_count`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
    `status`       TINYINT         NOT NULL DEFAULT 0 COMMENT '0=有效 1=取消',
    `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_share_id` (`share_id`),
    KEY `idx_user_share` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分享链接';

-- ---------------------------------------------------------------------
-- email_verification （邮箱验证码：注册/找回/换绑）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `email_verification`;
CREATE TABLE `email_verification` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `email`      VARCHAR(128)    NOT NULL,
    `code`       VARCHAR(8)      NOT NULL,
    `type`       VARCHAR(16)     NOT NULL COMMENT 'register | reset | bind',
    `used`       TINYINT(1)      NOT NULL DEFAULT 0,
    `expire_at`  DATETIME(3)     NOT NULL,
    `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_email_type` (`email`, `type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邮箱验证码';

-- ---------------------------------------------------------------------
-- system_setting （管理端系统设置 KV）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `system_setting`;
CREATE TABLE `system_setting` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `setting_key`   VARCHAR(64)     NOT NULL COMMENT '配置键',
    `setting_value` TEXT            DEFAULT NULL COMMENT '配置值',
    `updated_by`    BIGINT UNSIGNED DEFAULT NULL,
    `updated_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_setting_key` (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统设置';

-- ---------------------------------------------------------------------
-- op_log
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `op_log`;
CREATE TABLE `op_log` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT UNSIGNED DEFAULT NULL,
    `op_type`    VARCHAR(32)     NOT NULL,
    `target_id`  VARCHAR(64)     DEFAULT NULL,
    `ip`         VARCHAR(64)     DEFAULT NULL,
    `ua`         VARCHAR(512)    DEFAULT NULL,
    `params`     JSON            DEFAULT NULL,
    `result`     TINYINT         NOT NULL,
    `cost_ms`    INT UNSIGNED    NOT NULL DEFAULT 0,
    `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `created_at`),
    KEY `idx_op_time` (`op_type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志';

-- ---------------------------------------------------------------------
-- 初始 dev 用户配额
-- ---------------------------------------------------------------------
INSERT INTO `user_quota` (`user_id`, `total_bytes`, `used_bytes`, `file_count`)
VALUES (1, 107374182400, 0, 0)
ON DUPLICATE KEY UPDATE total_bytes = VALUES(total_bytes);
