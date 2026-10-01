-- 13_ai_wiki.sql —— LLM Wiki 的主张表（E4-2）
--
-- 设计要点（对齐 docs/ai/implementation-roadmap.md §14 的验收口径「事实性文本必须能回到证据」）：
--   1. **一行 = 一条带证据的原子主张**，不是「一段模型写的摘要」。
--      证据四件套必须同时存在，缺一条这张表就退化成了「模型印象集」：
--        post_id      → 回到哪篇文章
--        chunk_index  → 回到哪一段
--        post_version → 回到**哪个版本**（文章改了，这条主张就该重算）
--        content_hash → 段落内容哈希（只失效受影响的那几条，而不是整篇重建）
--        quote        → 原文片段（人肉眼可核对，也是校验时比对的那份）
--   2. **幂等锚点**是 (post_id, content_hash, claim_text)：同一段落的同一版本重复抽取不会产生重复行，
--      重建时按它命中即更新（confidence / heading_path 可能变）。claim_text 是 VARCHAR(200)，
--      与契约里的长度上限一致（200×4 字节 = 800，远低于 InnoDB 3072 字节的索引上限）。
--   3. 不建 `status` / 审核字段：审核流（低置信项进 ADMIN 队列）是后续切片，
--      **先不加一个永远为默认值的列** —— 那种列会让人以为「审核已经做过了」。
--
-- 执行：mysql -u root -p stellar_ink < deploy/sql/13_ai_wiki.sql

CREATE TABLE IF NOT EXISTS `ai_wiki_claim`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `post_id`       BIGINT       NOT NULL COMMENT '来源文章 ID（业务库的 post.id，本服务只读它、不 JOIN）',
    `chunk_index`   INT          NOT NULL COMMENT '来源段落序号（从 0 开始）',
    `post_version`  VARCHAR(64)  NOT NULL COMMENT '文章内容版本：文章改了，这条主张就该重算',
    `content_hash`  VARCHAR(64)  NOT NULL COMMENT '段落内容哈希：用于只失效受影响的那几条',
    `claim_text`    VARCHAR(200) NOT NULL COMMENT '原子主张（一句话一件事）',
    `quote`         VARCHAR(500) NOT NULL COMMENT '原文片段：**必须真的出现在该段落里**（抽取时已校验）',
    `heading_path`  VARCHAR(255) NOT NULL DEFAULT '' COMMENT '段落所属章节路径',
    `confidence`    DECIMAL(4, 3) NOT NULL DEFAULT 0.500 COMMENT '模型自评把握（0-1）',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次抽取时间',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近一次重建时间',
    PRIMARY KEY (`id`),
    -- 幂等锚点：同一段落的同一版本 + 同一句主张 = 同一条记录
    UNIQUE KEY `uk_claim` (`post_id`, `content_hash`, `claim_text`),
    -- 读者侧按文章列主张、以及「这篇文章重建时先删哪些」都走它
    KEY `idx_post` (`post_id`, `chunk_index`),
    -- 「文章改了 → 找出旧版本的主张」走它
    KEY `idx_version` (`post_id`, `post_version`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='LLM Wiki：带证据的原子主张（E4）';
