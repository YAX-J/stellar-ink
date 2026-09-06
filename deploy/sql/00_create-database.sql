-- =============================================================
-- 星笺 STELLAR INK 建库脚本（可选！）
-- 仅当账号有建库权限时需要执行（如本机 root）。
-- 云数据库/托管 MySQL 通常由控制台分配数据库，账号无建库权限，
-- 此时【跳过本文件】，直接在分配的数据库里执行 01_schema.sql 和 02_init-data.sql。
--
-- 用法（本机 root 示例）：
--   mysql -uroot -p < 00_create-database.sql
-- =============================================================

CREATE DATABASE IF NOT EXISTS stellar_ink
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
