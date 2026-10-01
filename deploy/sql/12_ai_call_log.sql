-- =============================================================
-- 12 AI 调用账（审计 + 成本）+ 角色单价
-- 适用：MySQL 8.0+，执行前请先选中 stellar_ink 数据库
-- 幂等：CREATE 用 IF NOT EXISTS，ALTER 用 information_schema 判断后动态执行，可重复跑
--
-- 为什么需要它（E3-1）：
--   `ai_provider_config` 记的是「**现在**用哪个模型」，而没人记「**过去**调了什么、花了多少」。
--   于是「这个月 AI 花了多少钱」「谁把配额用完了」「哪次调用失败率最高」都只能翻日志 ——
--   日志会滚动、无法聚合、也不含成本。
--
-- 记账为什么落在 **Java（ai-service）** 而不是 Python：
--   ① 身份（userId / role）只在 Java 侧（Sa-Token），Python 只有一个签名头里的 userId；
--   ② `ai_*` 表归 ai-service 所有，Python 对配置表是**只读**的（见 providers/config_source.py）；
--   ③ 每次 AI 调用都必经 Java 出口，记账点与将来的配额拦截点在同一层。
--
-- 成本口径：单价**按角色**存在 `ai_provider_config`（一线程一模型，角色即模型），
--   记账时把**当时的单价快照**写进账里。不这么做的话，改一次单价会把历史账目一起改写。
-- =============================================================

CREATE TABLE IF NOT EXISTS `ai_call_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `trace_id` VARCHAR(64) DEFAULT NULL COMMENT '链路 traceId（网关→Java→Python 同一个）',
    `user_id` BIGINT DEFAULT NULL COMMENT '调用者 user_id（未登录为空，但目前所有 AI 出口都要登录）',
    `role` VARCHAR(16) DEFAULT NULL COMMENT '调用者角色：READER/AUTHOR/ADMIN',
    `scene` VARCHAR(32) NOT NULL COMMENT '调用场景：qa/qa_stream/writing_suggest/agent/eval',
    `provider_role` VARCHAR(32) DEFAULT NULL COMMENT '用到的模型角色：chat/embedding/rerank（评测一次用多个，故为空）',
    `model` VARCHAR(128) DEFAULT NULL COMMENT '实际使用的模型名（Python 回报的 usage.model）',
    `prompt_tokens` INT DEFAULT NULL COMMENT '输入 token；为空表示上游没回报（不是 0）',
    `completion_tokens` INT DEFAULT NULL COMMENT '输出 token；为空表示上游没回报（不是 0）',
    `total_tokens` INT DEFAULT NULL COMMENT '总 token；为空表示上游没回报（不是 0）',
    `latency_ms` INT DEFAULT NULL COMMENT 'Java 侧端到端耗时（毫秒）',
    `success` TINYINT NOT NULL DEFAULT 1 COMMENT '1 成功 / 0 失败（失败也要记，否则失败率无从统计）',
    `error_code` VARCHAR(64) DEFAULT NULL COMMENT '失败分类（异常类名，不含报文）',
    `price_input` DECIMAL(10,4) DEFAULT NULL COMMENT '记账那一刻的输入单价快照（元/百万 token）',
    `price_output` DECIMAL(10,4) DEFAULT NULL COMMENT '记账那一刻的输出单价快照（元/百万 token）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_created` (`created_at`),
    KEY `idx_user_time` (`user_id`, `created_at`),
    KEY `idx_scene_time` (`scene`, `created_at`),
    KEY `idx_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 调用账（谁、什么场景、用了多少 token、花了多少钱）';

-- 角色单价：空 = 未定价。**未定价的调用成本按「未知」处理，绝不当 0** ——
-- 当 0 算会让看板显示「本月花了 ¥0.00」，那是看起来最正常的一种假数据。
SET @price_in_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_provider_config'
      AND COLUMN_NAME = 'price_input_per_million'
);
SET @ddl := IF(
    @price_in_exists = 0,
    'ALTER TABLE `ai_provider_config` ADD COLUMN `price_input_per_million` DECIMAL(10,4) DEFAULT NULL COMMENT ''输入单价（元/百万 token）；空=未定价'' AFTER `temperature`',
    'SELECT ''ai_provider_config.price_input_per_million 已存在，跳过'' AS skipped'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @price_out_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_provider_config'
      AND COLUMN_NAME = 'price_output_per_million'
);
SET @ddl := IF(
    @price_out_exists = 0,
    'ALTER TABLE `ai_provider_config` ADD COLUMN `price_output_per_million` DECIMAL(10,4) DEFAULT NULL COMMENT ''输出单价（元/百万 token）；空=未定价'' AFTER `price_input_per_million`',
    'SELECT ''ai_provider_config.price_output_per_million 已存在，跳过'' AS skipped'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
