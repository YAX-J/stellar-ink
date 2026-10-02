-- 17_ai_retrieval_audit.sql —— 检索审计（M8）
--
-- 目的：回答「**这道问题当时检索到了什么**」，而且要能跨副本、跨天回头看
-- （进程内回放 E3-4 只能看「这一台、这一会儿」）。
--
-- ⚠️ **不存问题原文**（沿用本仓库「审计不记问题原文」的口径，问题里可能含个人信息）：
--   存 question_sha256 + question_chars —— 于是仍然能回答：
--     · 同一个问题被问过几次、是不是复现性问题（按 sha256 分组）；
--     · 问题长度分布（异常短的往往是测试或误触）。
--   要看原文/候选全文，按 trace_id 去进程内回放查 —— 那份**有内容**，但不落库。
--
-- ⚠️ 这张表**不是评测数据**：它记的是线上真实请求（含拒答、含失败），
--   用来发现「哪类问题总被拒答」，不能拿来算 recall（线上没有标注）。
--
--   候选数（candidates）与最终引用数（citations）**分开记**：
--   两者差距大说明「召回了一堆但没一条够格进答案」，那是提示词或门限的问题，
--   只记一个数会把这两种情况混成一样。
--
-- 执行：mysql -u root -p stellar_ink < deploy/sql/17_ai_retrieval_audit.sql

CREATE TABLE IF NOT EXISTS `ai_retrieval_audit`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `trace_id`       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '链路 id：据此回放进程内 trace 看候选全文',
    `user_id`        BIGINT       NULL COMMENT '提问者（未登录为空）',
    `scene`          VARCHAR(32)  NOT NULL COMMENT 'qa / qa_stream / agent / eval …',
    `strategy`       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '检索配置指纹（开关与门限的组合）',
    `question_hash`  CHAR(64)     NOT NULL COMMENT '问题原文的 SHA-256（**不存原文**）',
    `question_chars` INT          NOT NULL DEFAULT 0 COMMENT '问题长度（字）',
    `candidates`     INT          NOT NULL DEFAULT 0 COMMENT '召回候选数（去重前）',
    `citations`      INT          NOT NULL DEFAULT 0 COMMENT '最终进答案的引用数',
    `post_ids`       VARCHAR(512) NOT NULL DEFAULT '' COMMENT '命中的文章 id（逗号分隔，最多若干条）',
    `top_score`      DECIMAL(8, 5) NULL COMMENT '最高分（没命中为空）',
    `refused`        TINYINT      NOT NULL DEFAULT 0 COMMENT '是否拒答（1 = 没有依据）',
    `failed`         TINYINT      NOT NULL DEFAULT 0 COMMENT '这次调用是否失败（失败也要留痕）',
    `latency_ms`     INT          NOT NULL DEFAULT 0 COMMENT '端到端耗时',
    `model`          VARCHAR(128) NOT NULL DEFAULT '' COMMENT '实际答话的模型（降级后是备用模型）',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    PRIMARY KEY (`id`),
    -- 「最近 N 天某场景的拒答率」走它
    KEY `idx_scene_time` (`scene`, `created_at`),
    -- 「这个问题以前问过吗、答得怎么样」走它
    KEY `idx_question` (`question_hash`, `created_at`),
    -- 按 traceId 找回放
    KEY `idx_trace` (`trace_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='检索审计：线上每次检索的规模与结果（不存问题原文）';
