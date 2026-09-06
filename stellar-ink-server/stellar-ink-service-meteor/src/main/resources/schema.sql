CREATE TABLE IF NOT EXISTS meteor (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    content    VARCHAR(500) NOT NULL,
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO meteor (id, content, created_at)
SELECT 1, '便利店的关东煮，在凌晨两点最有尊严。', CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM meteor WHERE id = 1);

INSERT INTO meteor (id, content, created_at)
SELECT 2, '忽然明白：所谓成长，就是把“为什么是我”换成“接下来呢”。', CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM meteor WHERE id = 2);

INSERT INTO meteor (id, content, created_at)
SELECT 3, '楼下的猫今天允许我摸了三秒。历史性突破。', CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM meteor WHERE id = 3);

INSERT INTO meteor (id, content, created_at)
SELECT 4, '把待办清单烧掉一半，效率反而翻倍。', CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM meteor WHERE id = 4);

INSERT INTO meteor (id, content, created_at)
SELECT 5, '雨声是最好的白噪音，前提是屋里没漏。', CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM meteor WHERE id = 5);
