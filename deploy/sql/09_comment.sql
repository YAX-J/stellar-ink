-- =============================================================
-- 09 文章评论
-- 适用：MySQL 8.0+，执行前请先选中 stellar_ink 数据库
-- =============================================================

CREATE TABLE IF NOT EXISTS `post_comment` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `post_id` BIGINT NOT NULL COMMENT '文章 ID',
    `user_id` BIGINT NOT NULL COMMENT '评论用户 ID',
    `content` VARCHAR(1000) NOT NULL COMMENT '评论正文',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1 正常 / 0 已删除（软删除）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发表时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_post_status_created` (`post_id`, `status`, `created_at`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文章评论';
