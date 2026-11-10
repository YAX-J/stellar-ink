-- 先迁移再发布 user-service 与网关；历史 JWT 缺少版本，将要求重新登录。
SET @si_auth_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user' AND COLUMN_NAME='auth_version'
);
SET @si_auth_ddl := IF(@si_auth_exists=0,
    'ALTER TABLE `user` ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0 COMMENT ''账号令牌版本''',
    'SELECT 1');
PREPARE si_auth_stmt FROM @si_auth_ddl;
EXECUTE si_auth_stmt;
DEALLOCATE PREPARE si_auth_stmt;
