-- 实际模型调用明细与计量完整性。旧账保留原有 Token/单价口径，不伪造历史明细。
-- 幂等升级：新库及已有库均由 ai-migrations.txt 顺序执行。
SET @si_usage_json_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_call_log' AND COLUMN_NAME = 'model_calls_json'
);
SET @si_usage_ddl := IF(@si_usage_json_exists = 0,
    'ALTER TABLE ai_call_log ADD COLUMN model_calls_json JSON DEFAULT NULL COMMENT ''实际调用与价格快照''',
    'SELECT 1');
PREPARE si_usage_stmt FROM @si_usage_ddl;
EXECUTE si_usage_stmt;
DEALLOCATE PREPARE si_usage_stmt;
SET @si_usage_complete_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_call_log' AND COLUMN_NAME = 'usage_complete'
);
SET @si_usage_ddl := IF(@si_usage_complete_exists = 0,
    'ALTER TABLE ai_call_log ADD COLUMN usage_complete TINYINT DEFAULT NULL COMMENT ''1完整/0缺失/NULL旧账''',
    'SELECT 1');
PREPARE si_usage_stmt FROM @si_usage_ddl;
EXECUTE si_usage_stmt;
DEALLOCATE PREPARE si_usage_stmt;
