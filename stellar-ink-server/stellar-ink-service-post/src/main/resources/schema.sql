CREATE TABLE IF NOT EXISTS post (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(200) NOT NULL,
    content     TEXT,
    tags        VARCHAR(200),
    word_count  INT          DEFAULT 0,
    status      TINYINT      DEFAULT 1,
    glow        INT          DEFAULT 0,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO post (id, title, content, tags, word_count, status, glow, created_at, updated_at)
SELECT 1, '在算法的洪流里，做一个缓慢的人',
 '我们总是以为，写得快才算活着。可真正留下来的句子，都是在慢里熬出来的。夜越深，世界越安静，一个念头才有空间舒展成它本来该有的样子。\n有人问我为什么坚持写博客，而不是把一切都交给时间线。我的答案是：时间线属于别人，博客属于自己。在这里，每一篇文章都有固定的坐标，不会被新的噪音冲走。\n写到这里，夜已经深了。窗外的城市像一张摊开的星图，每一盏灯都是某个人的此刻。',
 '随笔,沉思', 4382, 1, 120, '2026-08-30 23:47:00', '2026-08-30 23:47:00'
WHERE NOT EXISTS (SELECT 1 FROM post WHERE id = 1);

INSERT INTO post (id, title, content, tags, word_count, status, glow, created_at, updated_at)
SELECT 2, '雨夜出租车观察笔记',
 '写作是一种延迟的艺术。让情绪先沉淀，让事实先冷却，等到某个不经意的夜晚，它们会自己浮上来，带着更清晰的轮廓。\n雨夜的出租车司机话最多，也最诚实。他们说房价，说孩子，说故乡的雨。城市在雨里变小，人在车里变得具体。',
 '城市,速写', 1860, 1, 127, '2026-08-24 22:30:00', '2026-08-24 22:30:00'
WHERE NOT EXISTS (SELECT 1 FROM post WHERE id = 2);

INSERT INTO post (id, title, content, tags, word_count, status, glow, created_at, updated_at)
SELECT 3, '论“已读不回”的礼仪',
 '你读到的每一段，都改过至少五遍。删掉的字比留下的多。但那些删掉的部分并没有消失——它们变成了这篇文章的骨密度。\n已读不回不是冷漠，是一种迟到的诚实。有些话不必立刻有回声。',
 '随笔,社交', 2340, 1, 134, '2026-08-17 23:10:00', '2026-08-17 23:10:00'
WHERE NOT EXISTS (SELECT 1 FROM post WHERE id = 3);

INSERT INTO post (id, title, content, tags, word_count, status, glow, created_at, updated_at)
SELECT 4, '我的桌面改造：从效率到呼吸',
 '如果说写作有什么秘诀，那就是：诚实面对那些让你犹豫要不要写下来的东西。犹豫的地方，往往就是光进来的地方。\n桌面清空一半之后，效率没有下降，呼吸变深了。工具要少到只剩下喜欢。',
 '工具,生活', 3120, 1, 141, '2026-07-29 21:45:00', '2026-07-29 21:45:00'
WHERE NOT EXISTS (SELECT 1 FROM post WHERE id = 4);

INSERT INTO post (id, title, content, tags, word_count, status, glow, created_at, updated_at)
SELECT 5, '读《夜航西飞》三次',
 '我们总是以为，写得快才算活着。可真正留下来的句子，都是在慢里熬出来的。\n第一遍读故事，第二遍读句子，第三遍读孤独。柏瑞尔的夜航是写给所有在深夜清醒的人的。',
 '读书,沉思', 2710, 1, 148, '2026-06-15 23:55:00', '2026-06-15 23:55:00'
WHERE NOT EXISTS (SELECT 1 FROM post WHERE id = 5);

INSERT INTO post (id, title, content, tags, word_count, status, glow, created_at, updated_at)
SELECT 6, '把博客写成一座花园',
 '写作是一种延迟的艺术。让情绪先沉淀，让事实先冷却。\n博客不是简历，是花园。有的文章是乔木，有的是苔藓，都允许生长。',
 '写作,工具', 1980, 1, 155, '2025-12-02 22:10:00', '2025-12-02 22:10:00'
WHERE NOT EXISTS (SELECT 1 FROM post WHERE id = 6);

INSERT INTO post (id, title, content, tags, word_count, status, glow, created_at, updated_at)
SELECT 7, '凌晨四点的便利店',
 '夜越深，世界越安静。\n便利店的灯是城市的星星。关东煮、关东煮的汤、和还没睡的人。',
 '城市,速写', 1520, 1, 162, '2025-10-11 04:20:00', '2025-10-11 04:20:00'
WHERE NOT EXISTS (SELECT 1 FROM post WHERE id = 7);

INSERT INTO post (id, title, content, tags, word_count, status, glow, created_at, updated_at)
SELECT 8, '一年写 18 万字的方法',
 '有人问我为什么坚持写博客。我的答案是：时间线属于别人，博客属于自己。\n方法只有一个：不追求每天都写得好，只追求每天都写。数量会变成习惯，习惯会变成肌肉。',
 '写作,沉思', 3650, 1, 169, '2025-08-08 23:30:00', '2025-08-08 23:30:00'
WHERE NOT EXISTS (SELECT 1 FROM post WHERE id = 8);

INSERT INTO post (id, title, content, tags, word_count, status, glow, created_at, updated_at)
SELECT 9, '告别朋友圈的第九十天',
 '时间线属于别人，博客属于自己。\n第九十天，我发现自己没有错过任何真正重要的消息。重要的东西会自己找到你。',
 '社交,生活', 2230, 1, 176, '2025-05-19 22:50:00', '2025-05-19 22:50:00'
WHERE NOT EXISTS (SELECT 1 FROM post WHERE id = 9);

INSERT INTO post (id, title, content, tags, word_count, status, glow, created_at, updated_at)
SELECT 10, '海边的路由器',
 '窗外的城市像一张摊开的星图。\n海边民宿的路由器密码是海浪的声音。信号很差，睡眠很好。',
 '旅行,速写', 1740, 1, 183, '2024-11-23 23:05:00', '2024-11-23 23:05:00'
WHERE NOT EXISTS (SELECT 1 FROM post WHERE id = 10);

INSERT INTO post (id, title, content, tags, word_count, status, glow, created_at, updated_at)
SELECT 11, '第一行代码与第一首诗',
 '犹豫的地方，往往就是光进来的地方。\n第一行代码打印出 Hello World，第一首诗只写给自己。它们是同一种心跳。',
 '工具,随笔', 2890, 1, 190, '2024-06-30 23:40:00', '2024-06-30 23:40:00'
WHERE NOT EXISTS (SELECT 1 FROM post WHERE id = 11);

INSERT INTO post (id, title, content, tags, word_count, status, glow, created_at, updated_at)
SELECT 12, '搬家：物品的告别式',
 '写到这里，夜已经深了。\n搬家是一次小型告别式。每一件被丢掉的东西，都替你记住了一段时间。',
 '生活,沉思', 2050, 1, 197, '2024-03-14 21:30:00', '2024-03-14 21:30:00'
WHERE NOT EXISTS (SELECT 1 FROM post WHERE id = 12);

-- 近三夜短文：让「连续写作 / 今晚字数」统计有真实数据
INSERT INTO post (id, title, content, tags, word_count, status, glow, created_at, updated_at)
SELECT 13, '今夜的碎片',
 '把今天的三个念头捡进同一颗星星里。\n今夜写下的第一句：慢一点，再慢一点。',
 '随笔,速写', 500, 1, 88, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM post WHERE id = 13);

INSERT INTO post (id, title, content, tags, word_count, status, glow, created_at, updated_at)
SELECT 14, '昨日补记',
 '昨天没来得及发射的念头，今天补上。\n有时迟到也是一种完成。',
 '随笔,生活', 620, 1, 92, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM post WHERE id = 14);

INSERT INTO post (id, title, content, tags, word_count, status, glow, created_at, updated_at)
SELECT 15, '前夜的星尘',
 '前夜的月亮很好，字很少。\n少，也是一种密度。',
 '随笔,沉思', 480, 1, 96, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM post WHERE id = 15);
