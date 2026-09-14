-- -------------------------------------------------------------
-- 08 用户头像图片
-- 背景：`user.avatar_text` 只是「头像底字」（一个字），无法承载真实图片。
-- 本期新增 avatar_url 记录上传头像的公开访问路径；未设置时前端回落到底字。
--
-- 口径：
--   1) 只存相对路径（如 /uploads/avatars/1_ab12cd34.png），不存域名 ——
--      换域名/换协议不用刷库；前端与 <img src> 天然同源。
--   2) 文件本体落 user-service 本地磁盘（stellar.ink.upload.dir，默认 ./data/uploads），
--      user-service 通过 /uploads/** 静态映射对外提供，网关加同前缀路由。
--   3) 允许为空 = 用底字头像；删除头像即置空。
-- -------------------------------------------------------------

ALTER TABLE `user`
    ADD COLUMN `avatar_url` VARCHAR(255) NULL COMMENT '头像图片相对路径（如 /uploads/avatars/1_ab12cd34.png）；空则用 avatar_text 底字' AFTER `avatar_text`;
