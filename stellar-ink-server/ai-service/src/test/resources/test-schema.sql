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
