-- =============================================================
-- 系列 / 专栏（已有数据库执行一次；新库由 01_schema.sql 建好）
-- 适用：MySQL 8.0+，执行前请先选中 stellar_ink 数据库
--
-- 为什么单独一张表而不是在 post/note 上放一个字符串：
--   · 系列要有标题与简介，字符串会把同一段说明复制到每一篇；
--   · 改名只需改一行，不会出现「有的篇目还叫旧名」；
--   · 序号（series_order）与内容类型无关：一个系列可以同时收文章与笔记（技术博客常见），
--     排序由 (series_order, id) 决定，两种内容混排也稳定。
--
-- 归属：series.user_id 是创建者；只有创建者能改/删系列，
--       也只能把自己的内容放进自己的系列（服务层校验，见 SeriesServiceImpl）。
-- 可见性：系列列表与详情是公开的；只统计/展示**已发布文章 + 公开已发布笔记**。
-- =============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `series` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NOT NULL                COMMENT '创建者用户 ID',
    `title`       VARCHAR(100) NOT NULL                COMMENT '系列标题',
    `description` VARCHAR(300)                         COMMENT '系列简介（一句话说清读它能得到什么）',
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '系列/专栏';

-- post / note 上的归属列（幂等补齐，便于手工建过表的环境对齐）
SET @schema_name = DATABASE();

SET @sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'post' AND COLUMN_NAME = 'series_id'),
    'SELECT 1',
    'ALTER TABLE `post` ADD COLUMN `series_id` BIGINT NULL COMMENT ''所属系列（NULL = 不属任何系列）'' AFTER `tags`'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'post' AND COLUMN_NAME = 'series_order'),
    'SELECT 1',
    'ALTER TABLE `post` ADD COLUMN `series_order` INT NULL COMMENT ''系列内序号（小到大）'' AFTER `series_id`'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'note' AND COLUMN_NAME = 'series_id'),
    'SELECT 1',
    'ALTER TABLE `note` ADD COLUMN `series_id` BIGINT NULL COMMENT ''所属系列（NULL = 不属任何系列）'' AFTER `tags`'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'note' AND COLUMN_NAME = 'series_order'),
    'SELECT 1',
    'ALTER TABLE `note` ADD COLUMN `series_order` INT NULL COMMENT ''系列内序号（小到大）'' AFTER `series_id`'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

-- 按系列取篇目是唯一的读路径，索引与 ORDER BY 对齐
SET @sql = IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'post' AND INDEX_NAME = 'idx_series_order'),
    'SELECT 1',
    'ALTER TABLE `post` ADD KEY `idx_series_order` (`series_id`, `series_order`)'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @sql = IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'note' AND INDEX_NAME = 'idx_series_order'),
    'SELECT 1',
    'ALTER TABLE `note` ADD KEY `idx_series_order` (`series_id`, `series_order`)'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;
