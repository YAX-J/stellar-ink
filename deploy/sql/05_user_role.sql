-- =============================================================
-- 角色字段补齐脚本（已有数据库执行一次）
-- 适用：MySQL 8.0+，执行前请先选中 stellar_ink 数据库
--
-- 背景：角色模型（READER / AUTHOR / ADMIN）是在多作者改造之后引入的，
-- 但仓库里原先没有对应的 ALTER 脚本，早期数据库的 user 表因此缺 role 列，
-- 启动后所有用户查询都会报 Unknown column 'role'。本脚本补齐该列并回填种子站长。
-- =============================================================

SET @schema_name = DATABASE();

SET @sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'user' AND COLUMN_NAME = 'role'),
    'SELECT 1',
    'ALTER TABLE `user` ADD COLUMN `role` VARCHAR(20) NOT NULL DEFAULT ''READER'' COMMENT ''角色：READER 读者 / AUTHOR 作者 / ADMIN 站长'' AFTER `daily_goal`'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

-- 种子站长保持 ADMIN（与 docs/api/README.md 的说明一致）
UPDATE `user` SET `role` = 'ADMIN' WHERE `username` = 'stellar' AND `role` <> 'ADMIN';
