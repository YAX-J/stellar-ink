-- =============================================================
-- 星笺 STELLAR INK 数据库初始化脚本（DDL）
-- 适用：MySQL 8.0+
--
-- ⚠️ 本文件不建库、不 USE——执行前请先在客户端选中目标数据库：
--   · Navicat / DBeaver：先双击左侧目标库（或右键库 → 运行 SQL 文件）
--   · 命令行：mysql -uroot -p 数据库名 < 01_schema.sql
--   · 数据库还没建？本地 root 先执行 00_create-database.sql；
--     云数据库请在控制台/面板创建或使用分配的库名
--
-- 说明：H2 dev 环境使用 stellar-ink-api/src/main/resources/schema.sql，二者表结构保持一致
-- =============================================================

-- ⚠️ 必须显式声明客户端字符集，否则中文（COMMENT 与种子数据）会变成乱码：
--   mysql 客户端的默认值 auto 会按操作系统 locale 推断，容器里 locale 是 POSIX/C，
--   于是退回 latin1 —— UTF-8 的中文被当成 latin1 读入再转存，表现为网页上出现
--   「æ×–â®¹」这类乱码。Docker 的 mysql 镜像自动执行 /docker-entrypoint-initdb.d/*.sql 时
--   不会带 --default-character-set，所以这行是给「自动初始化」兜底的。
SET NAMES utf8mb4;

-- -------------------------------------------------------------
-- 文章（星）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `post` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`    BIGINT       NOT NULL                COMMENT '作者用户 ID',
    `title`      VARCHAR(200) NOT NULL                COMMENT '标题',
    `content`    TEXT                                  COMMENT '正文',
    `tags`       VARCHAR(200)                          COMMENT '标签，逗号分隔',
    `word_count` INT          DEFAULT 0               COMMENT '字数（正文去空白字符）',
    `status`     TINYINT      DEFAULT 1               COMMENT '0 草稿 / 1 已发布',
    `glow`       INT          DEFAULT 0               COMMENT '补充光芒数',
    `view_count` INT          NOT NULL DEFAULT 0      COMMENT '浏览量',
    `created_at` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（即点亮日期）',
    `updated_at` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_status_id` (`user_id`, `status`, `id`),
    KEY `idx_status_id` (`status`, `id`),
    KEY `idx_created_at` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '文章';

-- -------------------------------------------------------------
-- 文章点赞明细（一人一赞；post.glow 为计数冗余，两者以本表为准）
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
-- 浏览计数闸门（每个登录用户一天一行，用于浏览量按天去重）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `post_view` (
    `user_id`   BIGINT NOT NULL                COMMENT '用户 ID',
    `viewed_at` DATE                        COMMENT '最近一次计数日期',
    PRIMARY KEY (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '浏览计数闸门';

-- -------------------------------------------------------------
-- 文章评论（软删除）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `post_comment` (
    `id`         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `post_id`    BIGINT        NOT NULL                COMMENT '文章 ID',
    `user_id`    BIGINT        NOT NULL                COMMENT '评论用户 ID',
    `content`    VARCHAR(1000) NOT NULL                COMMENT '评论内容',
    `status`     TINYINT       NOT NULL DEFAULT 1      COMMENT '1 正常 / 0 已删除',
    `created_at` DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '评论时间',
    `updated_at` DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_post_status_created` (`post_id`, `status`, `created_at`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '文章评论';

-- -------------------------------------------------------------
-- 技术笔记（标本）：与文章同为内容，但结构化、可检索、可私有、会过期
-- 结构约定：正文用 `## 现象 / ## 环境 / ## 排查 / ## 结论 / ## 参考` 章节表达，
--          读取端据此自动生成目录，不额外占用数据库列
-- -------------------------------------------------------------
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

-- -------------------------------------------------------------
-- 流星备忘录（碎片）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `meteor` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`    BIGINT       NOT NULL                COMMENT '作者用户 ID',
    `content`    VARCHAR(500) NOT NULL                COMMENT '碎片内容',
    `created_at` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '发射时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
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
    `status`      TINYINT      DEFAULT 0               COMMENT '0 待审核 / 1 已接入 / 2 已驳回',
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
    `avatar_text` VARCHAR(10)                           COMMENT '头像底字（未上传图片时使用）',
    `avatar_url`  VARCHAR(255)                          COMMENT '头像图片相对路径（/uploads/avatars/x.png），空则用底字',
    `daily_goal`  INT          DEFAULT 500             COMMENT '每日星尘目标（字）',
    `role`        VARCHAR(20)  NOT NULL DEFAULT 'READER' COMMENT '角色：READER 读者 / AUTHOR 作者 / ADMIN 站长',
    `role_applied_at` DATETIME                          COMMENT '申请成为作者的时间；非空即有待审核申请',
    `role_apply_note` VARCHAR(200)                      COMMENT '申请理由（供站长审核参考）',
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '注册星历',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_role_applied_at` (`role_applied_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '站长用户';
