-- 19_ai_content_snapshot.sql —— RAG 语料投影（B 方案的落点）
--
-- 目的：让 Python 侧的「知识库应该包含哪些内容」变成**一张自己的只读表**，
--   于是它既能直接读库（要的就是这个），又不必碰 `post` / `note`（AGENTS §5 红线：
--   Python 不读写 user/post）。规则仍然只有一份 —— 「什么算公开内容」由 content-service 定义，
--   /internal/corpus 输出什么，这张表就存什么。
--
-- ⚠️ 它是一张**投影/缓存**，不是事实源：事实源永远是 post / note。
--   所以这张表**可以被整表重建**，而「表里有、上游已删」的行必须被删掉（否则删掉的文章
--   还会被问答引用出来 —— 这是本表最重要的一条语义，见 ai-service 的 CorpusSyncService）。
--
-- ⚠️ **私有笔记与草稿永远不该出现在这里**。上游（content-service 的 /internal/corpus）
--   已经不返回它们；这张表再被谁手工写脏，就必须能被下一次同步删干净。
--
-- ⚠️ `doc_hash` 是**整篇**（标题+正文）的 SHA-256，用途只有一个：判断「这篇变了没有」，
--   从而避免重新切块/重嵌。它与向量库里**每个子块**的 contentHash（Python 切块时算，
--   用来核验索引锚点）不是一回事 —— 不要拿这个去和 payload 里的 contentHash 比。
--
-- `kind + content_id` 才是文档标识：文章与笔记的 id 各自自增，只存 id 会指错文档。
--
-- 执行：mysql -u root -p stellar_ink < deploy/sql/19_ai_content_snapshot.sql

CREATE TABLE IF NOT EXISTS `ai_content_snapshot`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `kind`        VARCHAR(16)  NOT NULL COMMENT 'post / note（与 CorpusKind 的小写字面量一致）',
    `content_id`  BIGINT       NOT NULL COMMENT '该种类下的主键（post.id 或 note.id）',
    `title`       VARCHAR(255) NOT NULL DEFAULT '' COMMENT '标题（清单用，避免为了显示标题再回查上游）',
    `doc_hash`    CHAR(64)     NOT NULL COMMENT '整篇（标题+正文）SHA-256：判断是否变更、是否需要重嵌',
    `word_count`  INT          NOT NULL DEFAULT 0 COMMENT '字数（上游清单暂未提供时为 0）',
    `updated_at`  DATETIME     NULL COMMENT '上游的最后修改时间（增量拉取的游标）',
    `synced_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本次同步写入时间',
    PRIMARY KEY (`id`),
    -- 幂等锚点：同一种类的同一篇只有一行（重复同步是常态，没这个约束表会一天天膨胀）
    UNIQUE KEY `uk_kind_content` (`kind`, `content_id`),
    -- 增量同步按上游修改时间走它
    KEY `idx_updated` (`updated_at`),
    -- 「这次同步之后，哪些行没被提到」→ 删除它们（对账的删除侧）
    KEY `idx_synced` (`synced_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='RAG 语料投影：已发布文章 + 已发布且公开的笔记（可整表重建）';

-- 正文**不进这张表**：语料正文在嵌入时按需向上游单篇取（/internal/corpus/{kind}/{id}）。
-- 理由：这张表的用途是「有哪些文档、各自什么版本」，把整库正文复制一份既占空间，
-- 又多了一处「两份正文不一致」的可能 —— 而索引只需要正文一次（嵌入那一刻）。
