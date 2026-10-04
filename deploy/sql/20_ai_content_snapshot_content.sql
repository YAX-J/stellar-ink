-- 20_ai_content_snapshot_content.sql —— 给语料投影补 `content` 列
--
-- 背景（为什么 19 之后马上要补这一列）：`ai_content_snapshot` 最初刻意不存正文，
-- 设想是「嵌入时按需向上游单篇取」。但 Python 只允许读 `ai_*` 表（AGENTS §5 红线），
-- 它没有别的途径拿正文 —— 于是投影必须自足，否则 Python 切块时手上没有文本。
--
-- ⚠️ 只跑一次即可；本脚本用 information_schema 判存，重复执行不会报错（MySQL 8 没有
--    `ADD COLUMN IF NOT EXISTS`，所以走 prepared statement 这一步）。
--
-- 执行：mysql -u root -p stellar_ink < deploy/sql/20_ai_content_snapshot_content.sql

SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_content_snapshot'
      AND COLUMN_NAME = 'content'
);

SET @ddl := IF(
    @column_exists = 0,
    'ALTER TABLE `ai_content_snapshot` ADD COLUMN `content` MEDIUMTEXT NULL COMMENT ''正文（Markdown 原文）。必须存：Python 只允许读 ai_* 表，没有别的途径拿到正文'' AFTER `title`',
    'SELECT ''content 列已存在，跳过'' AS note'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
