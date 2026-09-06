CREATE TABLE IF NOT EXISTS link (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    url         VARCHAR(200) NOT NULL,
    description VARCHAR(300),
    status      TINYINT      DEFAULT 0,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO link (id, name, url, description, status, created_at)
SELECT 1, '夜航书店', 'https://nightbooks.blog', '在书页之间巡航的人，书评写得比书还慢。', 1, '2024-06-01 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM link WHERE id = 1);

INSERT INTO link (id, name, url, description, status, created_at)
SELECT 2, '雾中电台', 'https://fogradio.fm', '每周一期的声音随笔，适合雨夜收听。', 1, '2024-09-01 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM link WHERE id = 2);

INSERT INTO link (id, name, url, description, status, created_at)
SELECT 3, '盐的实验室', 'https://saltlab.dev', '前端 × 生成艺术，Canvas 重度玩家。', 1, '2025-01-01 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM link WHERE id = 3);

INSERT INTO link (id, name, url, description, status, created_at)
SELECT 4, '山月记', 'https://shanmoon.com', '古典文学慢读计划，一年只读十二本。', 1, '2025-05-01 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM link WHERE id = 4);

INSERT INTO link (id, name, url, description, status, created_at)
SELECT 5, '废纸篓', 'https://trashcan.ink', '写废稿比写成稿多，但篇篇真诚。', 1, '2025-10-01 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM link WHERE id = 5);

INSERT INTO link (id, name, url, description, status, created_at)
SELECT 6, '零点公园', 'https://0park.life', '城市夜游观察，凌晨街灯收藏家。', 1, '2026-02-01 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM link WHERE id = 6);
