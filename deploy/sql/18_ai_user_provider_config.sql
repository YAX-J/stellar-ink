-- 18 AI 个人模型配置（M12）
-- 适用：MySQL 8.0+，执行前请先选中 stellar_ink 数据库
-- 幂等：ALTER 用 information_schema 判断后动态执行，重复跑不会报错
--
-- 改什么：给 `ai_provider_config` 加 `user_id`，并把唯一键从 `(role)` 改成 `(user_id, role)`。
--
-- 为什么要 `user_id`：此前只有站长能在 AI 实验室里配模型（一角色一行，全站共用）。
-- 现在读者/作者也能配自己的模型 —— 于是同一张表要同时装两类行：
--
--   * `user_id = 0`  → **全局配置**（站长配的，全站默认）
--   * `user_id = N`  → 用户 N 的个人配置
--
-- ⚠️ **为什么用 0 而不是 NULL 表示全局**：MySQL 的唯一索引里 NULL **互不相等**，
-- 于是 `(NULL, 'chat')` 可以有任意多行 —— 「一个角色一行」这条约束会静默失效，
-- 而表现是「面板里保存了，但生效的是另一行」。用 0 就没有这个歧义。
--
-- ⚠️ **唯一键必须带 `user_id`**：只留 `(role)` 的话，第二个用户保存 chat 会直接撞键
-- （报「已经有同名模型」），而真相是「这张表还没按用户分家」。
--
-- ⚠️ **只有 chat / fast / reasoning 会按用户生效**：`embedding` / `rerank` 由 Python 侧
-- 刻意忽略用户行（向量索引只有一份，换嵌入模型检索得到的是**错的**结果）。
-- 个人面板也不该放出这两个角色 —— 让用户配一个不会生效的东西是最糟的交互。
--
-- 执行：mysql -u root -p stellar_ink < deploy/sql/18_ai_user_provider_config.sql

-- 1) 加列：默认 0（迁移前已有的行天然就是「全局配置」，语义正确）
SET @user_id_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_provider_config'
      AND COLUMN_NAME = 'user_id'
);
SET @ddl := IF(
    @user_id_exists = 0,
    'ALTER TABLE `ai_provider_config` ADD COLUMN `user_id` BIGINT NOT NULL DEFAULT 0 COMMENT ''0 = 全局配置（站长）；其余为个人配置的 user_id'' AFTER `id`',
    'SELECT ''ai_provider_config.user_id 已存在，跳过'' AS skipped'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 唯一键：`(role)` → `(user_id, role)`
SET @uk_role_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_provider_config'
      AND INDEX_NAME = 'uk_role'
);
SET @ddl := IF(
    @uk_role_exists > 0,
    'ALTER TABLE `ai_provider_config` DROP INDEX `uk_role`',
    'SELECT ''uk_role 已不存在，跳过'' AS skipped'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @uk_user_role_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_provider_config'
      AND INDEX_NAME = 'uk_user_role'
);
SET @ddl := IF(
    @uk_user_role_exists = 0,
    'ALTER TABLE `ai_provider_config` ADD UNIQUE KEY `uk_user_role` (`user_id`, `role`)',
    'SELECT ''uk_user_role 已存在，跳过'' AS skipped'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3) 查询索引：个人配置按「谁 + 角色」取，全局按「角色」取
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_provider_config'
      AND INDEX_NAME = 'idx_user_enabled_role'
);
SET @ddl := IF(
    @idx_exists = 0,
    'ALTER TABLE `ai_provider_config` ADD KEY `idx_user_enabled_role` (`user_id`, `enabled`, `role`)',
    'SELECT ''idx_user_enabled_role 已存在，跳过'' AS skipped'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4) 回填：迁移前的行全部属于全局（DEFAULT 0 已经保证，这里只是把话说清楚）
UPDATE `ai_provider_config` SET `user_id` = 0 WHERE `user_id` IS NULL;

-- 5) 自检：跑完应当看到每个 (user_id, role) 只有一行
SELECT `user_id`, `role`, COUNT(*) AS rows_count
FROM `ai_provider_config`
GROUP BY `user_id`, `role`
HAVING COUNT(*) > 1;
