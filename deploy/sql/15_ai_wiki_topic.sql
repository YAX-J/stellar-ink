-- 15_ai_wiki_topic.sql —— LLM Wiki 的主题（E4-9）
--
-- 主题 = 共现图上的连通分量（见 app/rag/topics.py）。落库要解决的核心问题是
-- **「同一个主题」怎么认**：主题的成员会随文章增删而变化，而主题名（关键词组合）
-- 由成员算出来 —— 拿名字当锚点，成员一变就会多出一行，看起来像「发现了新主题」。
-- 所以锚点是 **signature**：成员规范化名字**排序后**拼接的 SHA-256。
--   成员没变 → 命中同一行（只更新权重与证据）；
--   成员变了 → 本来就该是一个新主题（旧的那行留着，它记录的是当时的知识状态）。
--
-- 与关系那套一致的两条口径：
--   * 主题与实体的关联、主题证据都**先清后写**：只增不删会让旧成员/旧证据永远留着，
--     而 weight 与证据条数一旦对不上，这一页就没法用来核对了；
--   * 主题页上的每段文字都能回到一句具体主张（claim_text + post_id + chunk_index）。
--
-- 执行：mysql -u root -p stellar_ink < deploy/sql/15_ai_wiki_topic.sql

CREATE TABLE IF NOT EXISTS `ai_wiki_topic`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `signature`  CHAR(64)     NOT NULL COMMENT '成员规范化名字排序后拼接的 SHA-256（幂等锚点）',
    `name`       VARCHAR(191) NOT NULL COMMENT '关键词组合（由成员算出来，不是模型拟的标题）',
    `keywords`   VARCHAR(191) NOT NULL DEFAULT '' COMMENT '前几个关键词，逗号分隔（列表展示用）',
    `size`       INT          NOT NULL DEFAULT 0 COMMENT '成员实体个数',
    `weight`     INT          NOT NULL DEFAULT 0 COMMENT '主题内共现边总权重',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_topic_signature` (`signature`),
    KEY `idx_topic_weight` (`weight`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='LLM Wiki 主题（共现图上的连通分量）';

CREATE TABLE IF NOT EXISTS `ai_wiki_topic_entity`
(
    `id`         BIGINT   NOT NULL AUTO_INCREMENT,
    `topic_id`   BIGINT   NOT NULL,
    `entity_id`  BIGINT   NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_topic_entity` (`topic_id`, `entity_id`),
    KEY `idx_topic_entity_entity` (`entity_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='主题包含哪些实体';

CREATE TABLE IF NOT EXISTS `ai_wiki_topic_evidence`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `topic_id`    BIGINT       NOT NULL,
    `post_id`     BIGINT       NOT NULL,
    `chunk_index` INT          NOT NULL,
    `claim_text`  VARCHAR(200) NOT NULL COMMENT '主题页上那段可核对的原文',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_topic_evidence` (`topic_id`, `post_id`, `chunk_index`, `claim_text`),
    KEY `idx_topic_evidence_post` (`post_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='主题证据（主题页也要能回到原文）';
