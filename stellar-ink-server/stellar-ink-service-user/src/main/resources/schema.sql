CREATE TABLE IF NOT EXISTS user (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL,
    password    VARCHAR(100) NOT NULL,
    nickname    VARCHAR(50),
    signature   VARCHAR(200),
    avatar_text VARCHAR(10),
    daily_goal  INT          DEFAULT 500,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO user (id, username, password, nickname, signature, avatar_text, daily_goal, created_at)
SELECT 1, 'stellar', '$2b$10$/Y2gZHVQbuUI8VDF96YrDONvBeVPz4H44LX2bTrrTTGeRaf1nZN4a', '拾星人',
       '在算法的洪流里，做一个缓慢的人。', '星', 500, '2024-03-07 23:00:00'
WHERE NOT EXISTS (SELECT 1 FROM user WHERE id = 1);
