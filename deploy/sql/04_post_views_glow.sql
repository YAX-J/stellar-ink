-- =============================================================
-- 阅读体验升级脚本（已有数据库执行一次）
-- 适用：MySQL 8.0+，执行前请先选中 stellar_ink 数据库
-- 内容：
--   1. post 增加 view_count（浏览量，登录用户按天去重）
--   2. 新增 post_glow 表（点赞明细，实现「一人一赞」与「我是否已赞」）
--   3. 新增 post_view 表（浏览计数闸门，每用户每天一行）
--   4. 把 post.glow 与 post_glow 的明细对齐（老数据无明细，重算会让旧计数归零）
-- =============================================================

SET @schema_name = DATABASE();

-- -------------------------------------------------------------
-- 1. post.view_count
-- -------------------------------------------------------------
SET @sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'post' AND COLUMN_NAME = 'view_count'),
    'SELECT 1',
    'ALTER TABLE `post` ADD COLUMN `view_count` INT NOT NULL DEFAULT 0 COMMENT ''浏览量'' AFTER `glow`'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

-- -------------------------------------------------------------
-- 2. post_glow：点赞明细（唯一键保证同一用户对同一文章只有一条）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `post_glow` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `post_id`    BIGINT   NOT NULL                COMMENT '文章 ID',
    `user_id`    BIGINT   NOT NULL                COMMENT '点赞用户 ID',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '首次补充光芒时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_post_user` (`post_id`, `user_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '文章点赞明细';

-- -------------------------------------------------------------
-- 3. post_view：浏览计数闸门（每个登录用户一天一行，用于浏览量按天去重）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `post_view` (
    `user_id`   BIGINT NOT NULL                COMMENT '用户 ID',
    `viewed_at` DATE                        COMMENT '最近一次计数日期',
    PRIMARY KEY (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '浏览计数闸门';

-- -------------------------------------------------------------
-- 4. 明细与计数对齐：以 glow 明细为准重算 post.glow
--    （升级前的老点赞没有 user_id 明细，重算后会归零 —— 需要保留旧计数时跳过本段）
-- -------------------------------------------------------------
UPDATE `post` p
SET p.`glow` = (SELECT COUNT(*) FROM `post_glow` g WHERE g.`post_id` = p.`id`);
