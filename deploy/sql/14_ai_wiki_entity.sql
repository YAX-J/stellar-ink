-- 14_ai_wiki_entity.sql —— LLM Wiki 的实体、提及与共现关系（E4-6）
--
-- 设计要点（延续 13_ai_wiki.sql 的口径：**没有证据的东西不许进库**）：
--   1. `ai_wiki_entity` 的幂等锚点是 **normalized**（归一化后的名字）：
--      「每天写五百字」与「　每天写五百字 」是同一个实体，只有一行。
--      `name` 存代表写法（出现最多的那种），`normalized` 才是键。
--   2. `ai_wiki_entity_mention` 是「实体出现在哪条主张里」，唯一键含 claim_text ——
--      **每个提及都能回到一句具体主张**，这是实体能回到原文的那条线（不是「大概出现过」）。
--   3. `ai_wiki_relation` 是**共现**关系（同一句主张里同时出现），不是语义关系。
--      它是**无向**的：写入前两端按 normalized 排序，唯一键 (source, target) 因此不会出现
--      (A,B) 与 (B,A) 各一行（那会让权重看起来只有实际的一半）。
--   4. `ai_wiki_relation_evidence` 让**边也能回到原文**：这条边是从哪几句主张里看出来的。
--      边的 weight 与它的证据条数必须一致 —— 不一致说明某一次重建只更新了一半。
--   5. 不建 `ai_wiki_alias`：别名合并目前只做确定性归一化（见 entities.py 的说明），
--      语义别名要等 ADMIN 审核流。**先不加一个永远为空的表**。
--
-- 执行：mysql -u root -p stellar_ink < deploy/sql/14_ai_wiki_entity.sql

CREATE TABLE IF NOT EXISTS `ai_wiki_entity`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `normalized`    VARCHAR(64)  NOT NULL COMMENT '归一化后的名字（幂等锚点，全角/大小写/空白/首尾标点已折叠）',
    `name`          VARCHAR(64)  NOT NULL COMMENT '代表写法（出现最多的那种）',
    `kind`          VARCHAR(16)  NOT NULL DEFAULT 'other' COMMENT 'person/concept/tool/org/place/other',
    `mention_count` INT          NOT NULL DEFAULT 0 COMMENT '出现在几条主张里（冗余计数，便于排序）',
    `post_count`    INT          NOT NULL DEFAULT 0 COMMENT '涉及几篇文章',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_entity` (`normalized`),
    KEY `idx_entity_kind` (`kind`, `mention_count`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='LLM Wiki 实体（E4）';

CREATE TABLE IF NOT EXISTS `ai_wiki_entity_mention`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `entity_id`   BIGINT       NOT NULL COMMENT '对应 ai_wiki_entity.id',
    `post_id`     BIGINT       NOT NULL COMMENT '来源文章',
    `chunk_index` INT          NOT NULL COMMENT '来源段落序号',
    `claim_text`  VARCHAR(200) NOT NULL COMMENT '它出现在这条主张（或它的原文片段）里',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 同一实体的同一句主张只记一次：重建时命中它即跳过
    UNIQUE KEY `uk_mention` (`entity_id`, `post_id`, `chunk_index`, `claim_text`),
    KEY `idx_mention_post` (`post_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='LLM Wiki 实体提及（每个提及都能回到一句主张）';

CREATE TABLE IF NOT EXISTS `ai_wiki_relation`
(
    `id`                BIGINT   NOT NULL AUTO_INCREMENT,
    `source_entity_id`  BIGINT   NOT NULL COMMENT '字典序较小的一端（无向边只有一种表示）',
    `target_entity_id`  BIGINT   NOT NULL COMMENT '字典序较大的一端',
    `weight`            INT      NOT NULL DEFAULT 1 COMMENT '被一起谈论的主张条数',
    `created_at`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_relation` (`source_entity_id`, `target_entity_id`),
    KEY `idx_relation_target` (`target_entity_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='LLM Wiki 实体共现关系（无向）';

CREATE TABLE IF NOT EXISTS `ai_wiki_relation_evidence`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `relation_id` BIGINT       NOT NULL COMMENT '对应 ai_wiki_relation.id',
    `post_id`     BIGINT       NOT NULL,
    `chunk_index` INT          NOT NULL,
    `claim_text`  VARCHAR(200) NOT NULL COMMENT '同时提到这两个实体的那句主张',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_relation_evidence` (`relation_id`, `post_id`, `chunk_index`, `claim_text`),
    KEY `idx_relation_evidence_post` (`post_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='LLM Wiki 关系证据（边也要能回到原文）';
