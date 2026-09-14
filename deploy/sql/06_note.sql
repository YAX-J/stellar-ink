-- =============================================================
-- 技术笔记表（已有数据库执行一次）
-- 适用：MySQL 8.0+，执行前请先选中 stellar_ink 数据库
--
-- 说明：note 与 post 是两张独立表，边界不同：
--   post 是「星」（文章，重文笔、天然公开）；note 是「标本」（技术笔记，结构化、可私有、会过期）。
-- 结构约定：正文用 `## 现象 / ## 环境 / ## 排查 / ## 结论 / ## 参考` 章节表达，
--           由前端渲染成目录，不额外占用数据库列。
-- =============================================================

SET @schema_name = DATABASE();

CREATE TABLE IF NOT EXISTS `note` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NOT NULL                COMMENT '作者用户 ID',
    `title`       VARCHAR(200) NOT NULL                COMMENT '标题（建议写报错原文或一句话症状）',
    `content`     TEXT                                  COMMENT '正文（Markdown）',
    `tags`        VARCHAR(200)                          COMMENT '技术栈标签，逗号分隔',
    `note_type`   VARCHAR(20)  NOT NULL DEFAULT 'FIX'  COMMENT 'FIX 问题解决 / PITFALL 踩坑 / TIL 学习笔记 / SCRAP 碎片',
    `visibility`  VARCHAR(20)  NOT NULL DEFAULT 'PRIVATE' COMMENT 'PUBLIC 公开 / PRIVATE 私有（仅作者可见）',
    `status`      TINYINT      NOT NULL DEFAULT 0      COMMENT '0 草稿 / 1 已发布',
    `word_count`  INT          DEFAULT 0               COMMENT '字数（正文去空白字符）',
    `view_count`  INT          NOT NULL DEFAULT 0      COMMENT '浏览量（仅公开笔记计数）',
    `verified_at` DATETIME                              COMMENT '上次验证结论仍有效的时间',
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_vis_status_id` (`user_id`, `visibility`, `status`, `id`),
    KEY `idx_vis_status_id` (`visibility`, `status`, `id`),
    KEY `idx_note_type` (`note_type`),
    KEY `idx_created_at` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '技术笔记';

-- 缺少任一列时补齐（幂等；便于手工建过表的环境对齐）
SET @sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'note' AND COLUMN_NAME = 'visibility'),
    'SELECT 1',
    'ALTER TABLE `note` ADD COLUMN `visibility` VARCHAR(20) NOT NULL DEFAULT ''PRIVATE'' COMMENT ''PUBLIC 公开 / PRIVATE 私有'' AFTER `note_type`'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'note' AND COLUMN_NAME = 'verified_at'),
    'SELECT 1',
    'ALTER TABLE `note` ADD COLUMN `verified_at` DATETIME NULL COMMENT ''上次验证结论仍有效的时间'' AFTER `view_count`'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;
