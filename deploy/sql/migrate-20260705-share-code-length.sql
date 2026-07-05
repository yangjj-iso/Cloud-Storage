-- Existing deployments only: allow hashed share extraction codes.
ALTER TABLE `file_share`
    MODIFY COLUMN `share_code` VARCHAR(128) NOT NULL COMMENT '提取码哈希（兼容历史明文）';
