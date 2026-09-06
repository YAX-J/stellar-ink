-- 星笺基础库表（H2 MySQL 模式与 MySQL 8 均可直接执行）

CREATE TABLE IF NOT EXISTS post (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(200) NOT NULL,
    content     TEXT,
    tags        VARCHAR(200),
    word_count  INT          DEFAULT 0,
    status      TINYINT      DEFAULT 1,
    glow        INT          DEFAULT 0,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS meteor (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    content    VARCHAR(500) NOT NULL,
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS echo (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    nickname   VARCHAR(50),
    content    VARCHAR(500) NOT NULL,
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS link (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    url         VARCHAR(200) NOT NULL,
    description VARCHAR(300),
    status      TINYINT      DEFAULT 0,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    username   VARCHAR(50)  NOT NULL,
    password   VARCHAR(100) NOT NULL,
    nickname   VARCHAR(50),
    signature  VARCHAR(200),
    avatar_text VARCHAR(10),
    daily_goal INT          DEFAULT 500,
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP
);
