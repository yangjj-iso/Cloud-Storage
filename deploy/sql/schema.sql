-- =====================================================================
-- CloudChunk schema
-- MySQL 8.0, utf8mb4_unicode_ci
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `cloudchunk`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `cloudchunk`;

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
