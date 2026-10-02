-- 16_ai_memory.sql —— 作者记忆与个性化（M9）
--
-- 设计要点（对齐 docs/ai/implementation-roadmap.md §13 的验收口径）：
--   1. **一行 = 一条能回到出处的记忆**，不是「聊天记录」。所以每条记忆都要有证据行
--      （`ai_memory_evidence`：回到某段原文，或用户自己确认过）——
--      没有出处的「事实」正是 M9 验收第三条要挡的东西（模型不能把推测写成永久用户事实）。
--   2. **幂等锚点**是 (user_id, memory_type, normalized, **status**)：同一用户、同一类型、
--      归一化后同一句话、同一状态只存一条。归一化（全角/半角、大小写、空白）由 Python 侧算好传进来，
--      放在库里比对是为了让「重复确认」不会一天天把表撑大 —— 那看起来像「记性越来越好」。
--      ⚠️ **状态必须在锚点里**（H2 的唯一键把这个设计问题顶出来过）：
--      「这条候选与一条已生效记忆重复」是必然发生的常见情况，而不带状态时 pending 与 active
--      无法共存 —— 表现有两种，都很难查：① 抽取接口直接 DuplicateKey（用户看到 500，
--      而不是「这条已经记过了」）；② 确认流程里「合并重复」的分支永远不会触发。
--      带上状态后，同一状态下的重复仍然不允许，而「候选 vs 已生效」能在确认时合并成一条。
--   3. **状态区分四档**（pending/active/disabled/deleted）：
--      · pending  = 模型提出的候选，等人确认（**不参与召回**）
--      · active   = 生效中
--      · disabled = 用户主动关掉（数据留着，可恢复）
--      · deleted  = 用户删除（先标状态，向量/缓存/画像的清理由服务负责，见 M9-3）
--      四档而不是布尔：`enabled=false` 分不清「用户暂时关了」与「用户要求删掉」，
--      而这两件事在「要不要真的清理数据」上处置完全不同。
--   4. `expires_at` 可空：空 = 不过期。**不写默认过期时间** ——
--      悄悄过期的记忆会让「它怎么不记得了」无从解释。
--   5. `ai_style_profile` 是派生数据（从文章统计出来的风格特征）。它必须**带版本**：
--      删除记忆时要连它一起清（M9 验收：删除后同步清除派生画像），有版本号才能说清
--      「清掉的是哪一版」，而不是让人对着一行无版本的数据猜。
--
-- 执行：mysql -u root -p stellar_ink < deploy/sql/16_ai_memory.sql

CREATE TABLE IF NOT EXISTS `ai_memory`
(
    `id`           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`      BIGINT        NOT NULL COMMENT '归属用户（业务库 user.id，本服务只读它、不 JOIN）',
    `memory_type`  VARCHAR(32)   NOT NULL COMMENT 'preference / fact / decision',
    `content`      VARCHAR(200)  NOT NULL COMMENT '记忆正文（一句话，长度与契约一致）',
    `normalized`   VARCHAR(200)  NOT NULL COMMENT '归一化正文：幂等锚点的一部分（全角/大小写/空白已抹平）',
    `confidence`   DECIMAL(4, 3) NOT NULL DEFAULT 0.500 COMMENT '可信度（模型推测的封顶 0.7，用户确认可更高）',
    `source`       VARCHAR(32)   NOT NULL COMMENT 'model_suggested / user_stated / user_confirmed',
    `status`       VARCHAR(16)   NOT NULL DEFAULT 'pending' COMMENT 'pending / active / disabled / deleted',
    `confirmed_at` DATETIME      NULL COMMENT '用户确认时间（NULL = 还没被确认过）',
    `expires_at`   DATETIME      NULL COMMENT '过期时间（NULL = 不过期；不设默认过期）',
    `created_at`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近一次变更时间',
    PRIMARY KEY (`id`),
    -- 幂等锚点：同一用户 + 同一类型 + 同一句话 = 同一条记忆（重复确认只更新，不新增）
    UNIQUE KEY `uk_memory` (`user_id`, `memory_type`, `normalized`, `status`),
    -- 召回取数：按用户 + 状态取（**用户隔离由这里保证**，不靠 Python 侧过滤）
    KEY `idx_user_status` (`user_id`, `status`),
    -- 按类型召回（「只带偏好进去」这类请求）
    KEY `idx_user_type` (`user_id`, `memory_type`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='作者记忆：带出处的长期记忆（M9）';

CREATE TABLE IF NOT EXISTS `ai_memory_evidence`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `memory_id`  BIGINT       NOT NULL COMMENT '所属记忆',
    `kind`       VARCHAR(16)  NOT NULL COMMENT 'quote（原文片段）/ user（用户确认）',
    `ref`        VARCHAR(512) NOT NULL COMMENT '原文片段或确认说明',
    `post_id`    BIGINT       NULL COMMENT '相关文章（可为空：用户确认类证据不一定有文章）',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    -- 同一条记忆的同一条证据只留一行（重复确认不会让证据列表无限变长）
    UNIQUE KEY `uk_evidence` (`memory_id`, `kind`, `ref`(191)),
    KEY `idx_memory` (`memory_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='记忆证据：一条记忆为什么成立（M9）';

CREATE TABLE IF NOT EXISTS `ai_style_profile`
(
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`    BIGINT      NOT NULL COMMENT '归属用户',
    `version`    INT         NOT NULL COMMENT '画像版本（从 1 递增；删除记忆时要能说清清掉的是哪一版）',
    `payload`    JSON        NOT NULL COMMENT '可解释的风格特征（句长/标点/关联词/反复字组等，**不含原句**）',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_version` (`user_id`, `version`),
    KEY `idx_user` (`user_id`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='派生风格画像：删除记忆时一并清除（M9）';
