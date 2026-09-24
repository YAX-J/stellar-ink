-- =============================================================
-- 10 AI 域表（provider 配置 + 评测骨架）
-- 适用：MySQL 8.0+，执行前请先选中 stellar_ink 数据库
--
-- 归属：所有 ai_* 表归 AI 域（ai-service 所有 / stellar-ink-ai 可读）；
--       user-service 与 content-service 不得读写这些表。
-- 幂等：全部 IF NOT EXISTS，可重复执行。
-- =============================================================

-- -------------------------------------------------------------
-- 模型供应商配置：一份配置对应一个「逻辑角色」（chat / fast / reasoning / embedding / rerank）
-- 前端面板填写，后端加密存储；列表接口只回脱敏值
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_provider_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `role` VARCHAR(32) NOT NULL COMMENT '逻辑角色：chat/fast/reasoning/embedding/rerank',
    `provider` VARCHAR(32) NOT NULL DEFAULT 'openai_compatible' COMMENT '协议实现：openai_compatible/fake',
    `display_name` VARCHAR(64) NOT NULL COMMENT '面板展示名，如「DeepSeek Chat」',
    `base_url` VARCHAR(255) NOT NULL COMMENT 'OpenAI 兼容端点，形如 https://api.deepseek.com/v1',
    `model` VARCHAR(128) NOT NULL COMMENT '模型名，如 deepseek-chat / BAAI/bge-m3',
    `api_key_cipher` VARBINARY(512) DEFAULT NULL COMMENT '加密后的 API Key（AES-GCM，主密钥只在环境变量）',
    `api_key_mask` VARCHAR(32) DEFAULT NULL COMMENT '掩码后的 Key（sk-…abcd），仅用于面板回显，不可解密出原文',
    `dimension` INT DEFAULT NULL COMMENT '向量维度（embedding/rerank 用；换模型必须与集合维度一致）',
    `timeout_ms` INT NOT NULL DEFAULT 30000 COMMENT '单次调用超时（毫秒）',
    `max_tokens` INT DEFAULT NULL COMMENT '单次生成上限（chat 用），null 表示用模型默认',
    `temperature` DECIMAL(3,2) DEFAULT NULL COMMENT '采样温度，null 表示用模型默认',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用（停用后该角色回退到上游默认或直接报错）',
    `last_check_status` VARCHAR(16) DEFAULT NULL COMMENT '最近一次自检：ok/failed/unknown',
    `last_check_message` VARCHAR(500) DEFAULT NULL COMMENT '自检结论（脱敏，不含 Key）',
    `last_checked_at` DATETIME DEFAULT NULL COMMENT '最近一次自检时间',
    `updated_by` BIGINT DEFAULT NULL COMMENT '最后修改人 user_id（审计）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role` (`role`),
    KEY `idx_enabled_role` (`enabled`, `role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 模型供应商配置（按逻辑角色一行）';

-- -------------------------------------------------------------
-- 评测数据集：一组问题 + 期望命中的文章（可见性由评测时按角色过滤，不在这里存权限）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_eval_dataset` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name` VARCHAR(64) NOT NULL COMMENT '数据集名，如「公开文章黄金集 v1」',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '用途说明与标注口径',
    `question_count` INT NOT NULL DEFAULT 0 COMMENT '题目数（冗余，便于列表展示）',
    `created_by` BIGINT DEFAULT NULL COMMENT '创建人 user_id',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 评测数据集';

CREATE TABLE IF NOT EXISTS `ai_eval_case` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `dataset_id` BIGINT NOT NULL COMMENT '所属数据集',
    `question` VARCHAR(500) NOT NULL COMMENT '问题',
    `expected_post_ids` VARCHAR(500) DEFAULT NULL COMMENT '期望命中的文章 ID（逗号分隔，空表示无答案题）',
    `expected_answer` VARCHAR(1000) DEFAULT NULL COMMENT '参考答案要点（可选，用于人工核对）',
    `case_type` VARCHAR(16) NOT NULL DEFAULT 'answerable' COMMENT 'answerable / unanswerable（无答案题用于测拒答）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_dataset` (`dataset_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 评测题目';

-- -------------------------------------------------------------
-- 评测运行：一次「策略配置 → 指标」的记录，供前端对比表使用
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_eval_run` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `dataset_id` BIGINT NOT NULL COMMENT '使用的数据集',
    `strategy_key` VARCHAR(64) NOT NULL COMMENT '策略标识，如 dense / hybrid / hybrid_rerank',
    `config_json` JSON NOT NULL COMMENT '本次运行的全部参数（召回数、权重、是否重排、切块参数…）',
    `status` VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending/running/succeeded/failed',
    `metrics_json` JSON DEFAULT NULL COMMENT '指标结果：recall@k / precision@k / mrr / ndcg / 命中率 / 拒答率 / p95 延迟',
    `case_result_json` JSON DEFAULT NULL COMMENT '逐题明细（命中与否、命中的 chunk、耗时），供前端下钻',
    `message` VARCHAR(500) DEFAULT NULL COMMENT '失败原因（脱敏）',
    `started_at` DATETIME DEFAULT NULL COMMENT '开始时间',
    `finished_at` DATETIME DEFAULT NULL COMMENT '结束时间',
    `created_by` BIGINT DEFAULT NULL COMMENT '触发人 user_id',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_dataset_created` (`dataset_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 评测运行记录';
