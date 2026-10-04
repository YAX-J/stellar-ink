-- 21_ai_content_snapshot_tags_author.sql —— 投影加 `tags` 与 `author_id`
--
-- 为什么必须补这两列（不是「顺手多存点」）：
--   Python 的 `app/api/v1/style.py`（写作画像）用的是语料对象的 `post.tags` 与 `post.author_id`：
--       owned = [post for post in load_corpus() if post.author_id == author_id]
--       tags_per_title=[post.tags for post in posts]
--   投影缺这两列时，一旦把语料来源切到这张表，写作画像就会运行时 AttributeError ——
--   而且是「表非空时才炸」：本地因表还没建而回退种子包，根本发现不了。
--
-- ⚠️ 只跑一次即可；用 information_schema 判存，重复执行不报错（MySQL 8 没有 ADD COLUMN IF NOT EXISTS）。
--
-- 执行：mysql -u root -p stellar_ink < deploy/sql/21_ai_content_snapshot_tags_author.sql

SET @has_tags := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_content_snapshot' AND COLUMN_NAME = 'tags'
);
SET @ddl_tags := IF(
    @has_tags = 0,
    'ALTER TABLE `ai_content_snapshot` ADD COLUMN `tags` VARCHAR(255) NOT NULL DEFAULT '''' COMMENT ''标签原文（逗号分隔）：写作画像要用'' AFTER `content`',
    'SELECT ''tags 列已存在，跳过'' AS note'
);
PREPARE stmt FROM @ddl_tags;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_author := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_content_snapshot' AND COLUMN_NAME = 'author_id'
);
SET @ddl_author := IF(
    @has_author = 0,
    'ALTER TABLE `ai_content_snapshot` ADD COLUMN `author_id` BIGINT NOT NULL DEFAULT 0 COMMENT ''作者 id：写作画像按作者取样要用'' AFTER `tags`',
    'SELECT ''author_id 列已存在，跳过'' AS note'
);
PREPARE stmt FROM @ddl_author;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
