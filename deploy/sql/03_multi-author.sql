-- =============================================================
-- 多作者归属升级脚本（已有数据库执行一次）
-- 适用：MySQL 8.0+，执行前请先选中 stellar_ink 数据库
-- 已有文章和流星归属到种子站长 user_id=1
-- =============================================================

SET @schema_name = DATABASE();

SET @sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'post' AND COLUMN_NAME = 'user_id'),
    'SELECT 1',
    'ALTER TABLE `post` ADD COLUMN `user_id` BIGINT NULL COMMENT ''作者用户 ID'' AFTER `id`'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

UPDATE `post` SET `user_id` = 1 WHERE `user_id` IS NULL;
ALTER TABLE `post` MODIFY COLUMN `user_id` BIGINT NOT NULL COMMENT '作者用户 ID';

SET @sql = IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'post' AND INDEX_NAME = 'idx_user_status_id'),
    'SELECT 1',
    'ALTER TABLE `post` ADD INDEX `idx_user_status_id` (`user_id`, `status`, `id`)'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'meteor' AND COLUMN_NAME = 'user_id'),
    'SELECT 1',
    'ALTER TABLE `meteor` ADD COLUMN `user_id` BIGINT NULL COMMENT ''作者用户 ID'' AFTER `id`'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

UPDATE `meteor` SET `user_id` = 1 WHERE `user_id` IS NULL;
ALTER TABLE `meteor` MODIFY COLUMN `user_id` BIGINT NOT NULL COMMENT '作者用户 ID';

SET @sql = IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'meteor' AND INDEX_NAME = 'idx_user_id'),
    'SELECT 1',
    'ALTER TABLE `meteor` ADD INDEX `idx_user_id` (`user_id`)'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;
