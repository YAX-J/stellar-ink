-- =============================================================
-- 星笺 STELLAR INK 数据库初始化脚本（DDL）
-- 适用：MySQL 8.0+
-- 用法：mysql -uroot -p < 01_schema.sql
-- 说明：H2 dev 环境使用 stellar-ink-api/src/main/resources/schema.sql，二者表结构保持一致
-- =============================================================

CREATE DATABASE IF NOT EXISTS stellar_ink
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE stellar_ink;

-- -------------------------------------------------------------
-- 文章（星）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `post` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`      VARCHAR(200) NOT NULL                COMMENT '标题',
    `content`    TEXT                                  COMMENT '正文',
    `tags`       VARCHAR(200)                          COMMENT '标签，逗号分隔',
    `word_count` INT          DEFAULT 0               COMMENT '字数（正文去空白字符）',
    `status`     TINYINT      DEFAULT 1               COMMENT '0 草稿 / 1 已发布',
    `glow`       INT          DEFAULT 0               COMMENT '补充光芒数',
    `created_at` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（即点亮日期）',
    `updated_at` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_status_id` (`status`, `id`),
    KEY `idx_created_at` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '文章';

-- -------------------------------------------------------------
-- 流星备忘录（碎片）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `meteor` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `content`    VARCHAR(500) NOT NULL                COMMENT '碎片内容',
    `created_at` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '发射时间',
    PRIMARY KEY (`id`),
    KEY `idx_created_at` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '流星备忘录';

-- -------------------------------------------------------------
-- 回声漂流瓶留言
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `echo` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `nickname`   VARCHAR(50)                           COMMENT '署名，空为匿名旅人',
    `content`    VARCHAR(500) NOT NULL                COMMENT '留言内容',
    `created_at` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '投瓶时间',
    PRIMARY KEY (`id`),
    KEY `idx_created_at` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '回声漂流瓶';

-- -------------------------------------------------------------
-- 友链（友邻星座）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `link` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(100) NOT NULL                COMMENT '站点名',
    `url`         VARCHAR(200) NOT NULL                COMMENT '站点地址',
    `description` VARCHAR(300)                          COMMENT '一句话介绍',
    `status`      TINYINT      DEFAULT 0               COMMENT '0 待确认 / 1 已接入',
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    PRIMARY KEY (`id`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '友链';

-- -------------------------------------------------------------
-- 站长用户（单用户博客；身份舱/星籍资料合并于此）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`    VARCHAR(50)  NOT NULL                COMMENT '登录名',
    `password`    VARCHAR(100) NOT NULL                COMMENT 'BCrypt 哈希',
    `nickname`    VARCHAR(50)                           COMMENT '笔名',
    `signature`   VARCHAR(200)                          COMMENT '星图签名',
    `avatar_text` VARCHAR(10)                           COMMENT '头像底字',
    `daily_goal`  INT          DEFAULT 500             COMMENT '每日星尘目标（字）',
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '注册星历',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '站长用户';
