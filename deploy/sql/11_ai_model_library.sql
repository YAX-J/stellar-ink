-- =============================================================
-- 11 AI 模型库（可选模型池）+ 角色绑定列
-- 适用：MySQL 8.0+，执行前请先选中 stellar_ink 数据库
-- 幂等：CREATE 用 IF NOT EXISTS，ALTER 用 information_schema 判断后动态执行，可重复跑
--
-- 为什么需要它：
--   `ai_provider_config` 是 **UNIQUE KEY uk_role**（一个角色一行），所以「再加一个 chat 模型」
--   会覆盖原来那行 —— 面板上没法在两个模型之间切换。这一版把两件事分开：
--     `ai_model`            模型库：你加进来的每一个模型（可以有很多条，没有角色概念）
--     `ai_provider_config`  仍然是「每个角色**当前生效**的那一份配置」
--   Python 只读 `ai_provider_config`，因此**Python 侧零改动**；
--   `model_id` 记录这份生效配置来自库里哪一条（面板手填时为 NULL），
--   改库里某条模型时由后端同步到绑定了它的角色上，避免「改了 Key 却不生效」。
-- =============================================================

CREATE TABLE IF NOT EXISTS `ai_model` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `display_name` VARCHAR(64) NOT NULL COMMENT '面板展示名，如「DeepSeek Chat」',
    `provider` VARCHAR(32) NOT NULL DEFAULT 'openai_compatible' COMMENT '协议实现：openai_compatible/fake',
    `base_url` VARCHAR(255) NOT NULL COMMENT 'OpenAI 兼容端点，形如 https://api.deepseek.com/v1',
    `model` VARCHAR(128) NOT NULL COMMENT '模型名，如 deepseek-chat / BAAI/bge-m3',
    `api_key_cipher` VARBINARY(512) DEFAULT NULL COMMENT '加密后的 API Key（AES-GCM，主密钥只在环境变量）',
    `api_key_mask` VARCHAR(32) DEFAULT NULL COMMENT '掩码后的 Key（sk-…abcd），仅用于面板回显',
    `cap_chat` TINYINT NOT NULL DEFAULT 0 COMMENT '1 = 能当对话模型用（chat/fast/reasoning 角色可选它）',
    `cap_embedding` TINYINT NOT NULL DEFAULT 0 COMMENT '1 = 能当嵌入模型用（embedding 角色可选它）',
    `cap_rerank` TINYINT NOT NULL DEFAULT 0 COMMENT '1 = 能当重排模型用（rerank 角色可选它）',
    `dimension` INT DEFAULT NULL COMMENT '向量维度（embedding 用；与向量集合维度必须一致）',
    `timeout_ms` INT NOT NULL DEFAULT 30000 COMMENT '单次调用超时（毫秒）',
    `max_tokens` INT DEFAULT NULL COMMENT '单次生成上限（对话模型用），null 表示用模型默认',
    `temperature` DECIMAL(3,2) DEFAULT NULL COMMENT '采样温度，null 表示用模型默认',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用（停用后不能被新绑定）',
    `last_check_status` VARCHAR(16) DEFAULT NULL COMMENT '最近一次自检：ok/failed/unknown',
    `last_check_message` VARCHAR(500) DEFAULT NULL COMMENT '自检结论（脱敏，不含 Key）',
    `last_checked_at` DATETIME DEFAULT NULL COMMENT '最近一次自检时间',
    `updated_by` BIGINT DEFAULT NULL COMMENT '最后修改人 user_id（审计）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    -- 同一个端点下的同一个模型只该有一条：否则下拉框里会出现两个一模一样的选项，
    -- 而它们可能各有一把不同的 Key，选错哪条都不报错
    UNIQUE KEY `uk_endpoint_model` (`base_url`, `model`),
    KEY `idx_capability` (`enabled`, `cap_chat`, `cap_embedding`, `cap_rerank`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 模型库（面板里加进来的模型，可被各角色选用）';

-- 给生效配置加上「来自库里哪一条」。写成条件 DDL 是为了这条脚本能重复执行：
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，直接 ALTER 第二次会报 Duplicate column。
SET @model_id_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_provider_config'
      AND COLUMN_NAME = 'model_id'
);
SET @ddl := IF(
    @model_id_exists = 0,
    'ALTER TABLE `ai_provider_config` ADD COLUMN `model_id` BIGINT DEFAULT NULL COMMENT ''来自模型库的哪一条（面板下拉框选择的结果；手填为 NULL）'' AFTER `role`',
    'SELECT ''ai_provider_config.model_id 已存在，跳过'' AS skipped'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
