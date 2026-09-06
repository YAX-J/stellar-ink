CREATE TABLE IF NOT EXISTS echo (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    nickname   VARCHAR(50),
    content    VARCHAR(500) NOT NULL,
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO echo (id, nickname, content, created_at)
SELECT 1, '远岛的鲸', '你的《缓慢的人》让我在地铁上坐过了站，谢谢。', '2026-08-31 22:00:00'
WHERE NOT EXISTS (SELECT 1 FROM echo WHERE id = 1);

INSERT INTO echo (id, nickname, content, created_at)
SELECT 2, 'Momo', '交换星链吗？我也在写夜间博客。', '2026-08-28 21:00:00'
WHERE NOT EXISTS (SELECT 1 FROM echo WHERE id = 2);

INSERT INTO echo (id, nickname, content, created_at)
SELECT 3, '匿名旅人', '今天也是忍住没发朋友圈的一天。', '2026-08-25 23:00:00'
WHERE NOT EXISTS (SELECT 1 FROM echo WHERE id = 3);

INSERT INTO echo (id, nickname, content, created_at)
SELECT 4, '老白', 'RSS 已订阅，请继续发射。', '2026-08-20 20:00:00'
WHERE NOT EXISTS (SELECT 1 FROM echo WHERE id = 4);

INSERT INTO echo (id, nickname, content, created_at)
SELECT 5, '青柠', '星图归档太浪漫了，偷走这个灵感（会注明出处！）。', '2026-08-15 22:00:00'
WHERE NOT EXISTS (SELECT 1 FROM echo WHERE id = 5);

INSERT INTO echo (id, nickname, content, created_at)
SELECT 6, 'K', '在破晓主题里读完了所有沉思标签。', '2026-08-10 21:00:00'
WHERE NOT EXISTS (SELECT 1 FROM echo WHERE id = 6);

INSERT INTO echo (id, nickname, content, created_at)
SELECT 7, '白昼梦', '第 21 天连续写作，怎么做到的？求一期方法论。', '2026-08-05 20:30:00'
WHERE NOT EXISTS (SELECT 1 FROM echo WHERE id = 7);
