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
