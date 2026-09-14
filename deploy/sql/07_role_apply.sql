-- =============================================================
-- 读者申请成为作者的字段（已有数据库执行一次）
-- 适用：MySQL 8.0+，执行前请先选中 stellar_ink 数据库
--
-- 设计：待审状态每人最多一条，因此不建独立申请表，直接放在 user 表：
--   role_applied_at 非空 即「有一条待审核申请」，申请时间本身也是列表排序依据；
--   站长审核队列直接复用既有的 GET /user/list，前端不用再发第二个请求。
-- 审批通过/驳回都走既有的 PUT /user/{id}/role，并在服务层统一清空这两个字段，
-- 保证「直接改角色」与「点通过」两条路径不会留下自相矛盾的待审状态。
-- =============================================================

SET @schema_name = DATABASE();

SET @sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'user' AND COLUMN_NAME = 'role_applied_at'),
    'SELECT 1',
    'ALTER TABLE `user` ADD COLUMN `role_applied_at` DATETIME NULL COMMENT ''申请成为作者的时间；非空即有待审核申请'' AFTER `role`'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'user' AND COLUMN_NAME = 'role_apply_note'),
    'SELECT 1',
    'ALTER TABLE `user` ADD COLUMN `role_apply_note` VARCHAR(200) NULL COMMENT ''申请理由（供站长审核参考）'' AFTER `role_applied_at`'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @sql = IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'user' AND INDEX_NAME = 'idx_role_applied_at'),
    'SELECT 1',
    'ALTER TABLE `user` ADD INDEX `idx_role_applied_at` (`role_applied_at`)'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;
