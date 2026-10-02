-- 测试库结构：只建 AI 域的表（测试不碰 user / post 等业务表）。
-- 与 deploy/sql/10_ai-schema.sql + 11_ai_model_library.sql 保持一致；
-- H2 以 MODE=MySQL 运行，因此类型与 ` 引号写法通用。
-- 生产仍由 deploy/sql 下的脚本建表，这里只为让测试能真的读写配置。

CREATE TABLE IF NOT EXISTS `ai_provider_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `role` VARCHAR(32) NOT NULL,
    `model_id` BIGINT DEFAULT NULL,
    `provider` VARCHAR(32) NOT NULL DEFAULT 'openai_compatible',
    `display_name` VARCHAR(64) NOT NULL,
    `base_url` VARCHAR(255) NOT NULL,
    `model` VARCHAR(128) NOT NULL,
    `api_key_cipher` VARBINARY(512) DEFAULT NULL,
    `api_key_mask` VARCHAR(32) DEFAULT NULL,
    `dimension` INT DEFAULT NULL,
    `timeout_ms` INT NOT NULL DEFAULT 30000,
    `max_tokens` INT DEFAULT NULL,
    `temperature` DECIMAL(3,2) DEFAULT NULL,
    `price_input_per_million` DECIMAL(10,4) DEFAULT NULL,
    `price_output_per_million` DECIMAL(10,4) DEFAULT NULL,
    `enabled` TINYINT NOT NULL DEFAULT 1,
    `last_check_status` VARCHAR(16) DEFAULT NULL,
    `last_check_message` VARCHAR(500) DEFAULT NULL,
    `last_checked_at` DATETIME DEFAULT NULL,
    `updated_by` BIGINT DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role` (`role`)
);

CREATE TABLE IF NOT EXISTS `ai_model` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `display_name` VARCHAR(64) NOT NULL,
    `provider` VARCHAR(32) NOT NULL DEFAULT 'openai_compatible',
    `base_url` VARCHAR(255) NOT NULL,
    `model` VARCHAR(128) NOT NULL,
    `api_key_cipher` VARBINARY(512) DEFAULT NULL,
    `api_key_mask` VARCHAR(32) DEFAULT NULL,
    `cap_chat` TINYINT NOT NULL DEFAULT 0,
    `cap_embedding` TINYINT NOT NULL DEFAULT 0,
    `cap_rerank` TINYINT NOT NULL DEFAULT 0,
    `dimension` INT DEFAULT NULL,
    `timeout_ms` INT NOT NULL DEFAULT 30000,
    `max_tokens` INT DEFAULT NULL,
    `temperature` DECIMAL(3,2) DEFAULT NULL,
    `enabled` TINYINT NOT NULL DEFAULT 1,
    `last_check_status` VARCHAR(16) DEFAULT NULL,
    `last_check_message` VARCHAR(500) DEFAULT NULL,
    `last_checked_at` DATETIME DEFAULT NULL,
    `updated_by` BIGINT DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_endpoint_model` (`base_url`, `model`)
);

-- AI 调用账（E3-1）。与 deploy/sql/12_ai_call_log.sql 一致。
-- 只被 AiUsageServiceImpl 用到；切片测试里那个服务是替身，本表只有整上下文测试才真的碰。
CREATE TABLE IF NOT EXISTS `ai_call_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `trace_id` VARCHAR(64) DEFAULT NULL,
    `user_id` BIGINT DEFAULT NULL,
    `role` VARCHAR(16) DEFAULT NULL,
    `scene` VARCHAR(32) NOT NULL,
    `provider_role` VARCHAR(32) DEFAULT NULL,
    `model` VARCHAR(128) DEFAULT NULL,
    `prompt_tokens` INT DEFAULT NULL,
    `completion_tokens` INT DEFAULT NULL,
    `total_tokens` INT DEFAULT NULL,
    `latency_ms` INT DEFAULT NULL,
    `success` TINYINT NOT NULL DEFAULT 1,
    `error_code` VARCHAR(64) DEFAULT NULL,
    `price_input` DECIMAL(10,4) DEFAULT NULL,
    `price_output` DECIMAL(10,4) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

-- LLM Wiki 主张（E4-2）。与 deploy/sql/13_ai_wiki.sql 一致（含那条幂等唯一键 ——
-- 「重复构建不产生重复行」正是靠它，测试里必须真的建出来才有意义）。
CREATE TABLE IF NOT EXISTS `ai_wiki_claim` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `post_id` BIGINT NOT NULL,
    `chunk_index` INT NOT NULL,
    `post_version` VARCHAR(64) NOT NULL,
    `content_hash` VARCHAR(64) NOT NULL,
    `claim_text` VARCHAR(200) NOT NULL,
    `quote` VARCHAR(500) NOT NULL,
    `heading_path` VARCHAR(255) NOT NULL DEFAULT '',
    `confidence` DECIMAL(4,3) NOT NULL DEFAULT 0.500,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_claim` UNIQUE (`post_id`, `content_hash`, `claim_text`)
);

-- LLM Wiki 知识图（E4-6）。与 deploy/sql/14_ai_wiki_entity.sql 一致（含幂等锚点：
-- 实体按 normalized 唯一、提及含 claimText、无向关系按 (source,target) 唯一）。
CREATE TABLE IF NOT EXISTS `ai_wiki_entity` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `normalized` VARCHAR(64) NOT NULL,
    `name` VARCHAR(64) NOT NULL,
    `kind` VARCHAR(16) NOT NULL DEFAULT 'other',
    `mention_count` INT NOT NULL DEFAULT 0,
    `post_count` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_entity` UNIQUE (`normalized`)
);

CREATE TABLE IF NOT EXISTS `ai_wiki_entity_mention` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `entity_id` BIGINT NOT NULL,
    `post_id` BIGINT NOT NULL,
    `chunk_index` INT NOT NULL,
    `claim_text` VARCHAR(200) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_mention` UNIQUE (`entity_id`, `post_id`, `chunk_index`, `claim_text`)
);

CREATE TABLE IF NOT EXISTS `ai_wiki_relation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `source_entity_id` BIGINT NOT NULL,
    `target_entity_id` BIGINT NOT NULL,
    `weight` INT NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_relation` UNIQUE (`source_entity_id`, `target_entity_id`)
);

CREATE TABLE IF NOT EXISTS `ai_wiki_relation_evidence` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `relation_id` BIGINT NOT NULL,
    `post_id` BIGINT NOT NULL,
    `chunk_index` INT NOT NULL,
    `claim_text` VARCHAR(200) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_relation_evidence` UNIQUE (`relation_id`, `post_id`, `chunk_index`, `claim_text`)
);

-- LLM Wiki 主题（E4-9）。与 deploy/sql/15_ai_wiki_topic.sql 一致（幂等锚点是**成员签名**，
-- 不是主题名 —— 名字由成员算出来，拿它当锚点会凭空多出一行）。
CREATE TABLE IF NOT EXISTS `ai_wiki_topic` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `signature` CHAR(64) NOT NULL,
    `name` VARCHAR(191) NOT NULL,
    `keywords` VARCHAR(191) NOT NULL DEFAULT '',
    `size` INT NOT NULL DEFAULT 0,
    `weight` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_topic_signature` UNIQUE (`signature`)
);

CREATE TABLE IF NOT EXISTS `ai_wiki_topic_entity` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `topic_id` BIGINT NOT NULL,
    `entity_id` BIGINT NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_topic_entity` UNIQUE (`topic_id`, `entity_id`)
);

CREATE TABLE IF NOT EXISTS `ai_wiki_topic_evidence` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `topic_id` BIGINT NOT NULL,
    `post_id` BIGINT NOT NULL,
    `chunk_index` INT NOT NULL,
    `claim_text` VARCHAR(200) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_topic_evidence` UNIQUE (`topic_id`, `post_id`, `chunk_index`, `claim_text`)
);

-- 作者记忆（M9-2）。与 deploy/sql/16_ai_memory.sql 一致：
--   幂等锚点是 (user_id, memory_type, normalized)；
--   状态四档而不是布尔（enabled=false 分不清「暂时关了」与「要求删掉」）。
CREATE TABLE IF NOT EXISTS `ai_memory` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `memory_type` VARCHAR(32) NOT NULL,
    `content` VARCHAR(200) NOT NULL,
    `normalized` VARCHAR(200) NOT NULL,
    `confidence` DECIMAL(4, 3) NOT NULL DEFAULT 0.500,
    `source` VARCHAR(32) NOT NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'pending',
    `confirmed_at` DATETIME NULL,
    `expires_at` DATETIME NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 锚点**含状态**：pending 与 active 必须能共存，否则「候选与已生效记忆重复」
    -- 根本表达不出来（抽取会 DuplicateKey、确认时的合并分支永远不触发）。
    -- 详见 deploy/sql/16_ai_memory.sql 的注释。
    CONSTRAINT `uk_memory` UNIQUE (`user_id`, `memory_type`, `normalized`, `status`)
);

CREATE TABLE IF NOT EXISTS `ai_memory_evidence` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `memory_id` BIGINT NOT NULL,
    `kind` VARCHAR(16) NOT NULL,
    `ref` VARCHAR(512) NOT NULL,
    `post_id` BIGINT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `ai_style_profile` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `version` INT NOT NULL,
    `payload` CLOB NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_user_version` UNIQUE (`user_id`, `version`)
);
-- 检索审计（M8）。与 deploy/sql/17_ai_retrieval_audit.sql 一致：
-- **不存问题原文**（只存哈希与长度），失败与拒答分开记。
CREATE TABLE IF NOT EXISTS `ai_retrieval_audit` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `trace_id` VARCHAR(64) NOT NULL DEFAULT '',
    `user_id` BIGINT NULL,
    `scene` VARCHAR(32) NOT NULL,
    `strategy` VARCHAR(64) NOT NULL DEFAULT '',
    `question_hash` CHAR(64) NOT NULL,
    `question_chars` INT NOT NULL DEFAULT 0,
    `candidates` INT NOT NULL DEFAULT 0,
    `citations` INT NOT NULL DEFAULT 0,
    `post_ids` VARCHAR(512) NOT NULL DEFAULT '',
    `top_score` DECIMAL(8, 5) NULL,
    `refused` TINYINT NOT NULL DEFAULT 0,
    `failed` TINYINT NOT NULL DEFAULT 0,
    `latency_ms` INT NOT NULL DEFAULT 0,
    `model` VARCHAR(128) NOT NULL DEFAULT '',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);