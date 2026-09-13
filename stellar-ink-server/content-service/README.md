# 内容服务

`content-service` 聚合星笺的内容领域，端口为 `8102`，在 Nacos 中注册为 `content-service`。它取代原先独立的 post、meteor、echo、link 和 stats 服务，但内部仍按领域分包。

## 领域

| 包 | 接口 | 数据 |
|---|---|---|
| `post/` | `/posts/**`、`/tags/**`、`/search/**` | `post` 表 |
| `meteor/` | `/meteors/**` | `meteor` 表 |
| `echo/` | `/echos/**` | `echo` 表 |
| `link/` | `/links/**` | `link` 表 |
| `stats/` | `/stats/**` | 实时查询已发布文章 |

对外 URL 与合并前保持一致，前端无需感知服务重组。完整接口见 [`../../docs/api/README.md`](../../docs/api/README.md)。

## 边界

- 文章和流星记录 `user_id`；AUTHOR 只能维护自己的数据，ADMIN 可管理全部。
- 草稿列表只允许 AUTHOR 访问，草稿详情只允许作者本人或 ADMIN。
- 回声投递、友链申请和文章 glow 是公开写操作。
- 友链状态修改要求 ADMIN，网关和本服务都会校验。
- 统计与文章同进程，直接通过 `PostMapper` 查询，不使用 Feign。
- 本服务不读写 `user` 表；作者展示由前端通过 user-service 的批量作者接口补全。

## 数据库准备

新库执行 [`../../deploy/sql/01_schema.sql`](../../deploy/sql/01_schema.sql) 和 `02_init-data.sql`。旧库必须额外执行 `03_multi-author.sql`，否则 `post`、`meteor` 缺少 `user_id` 时相关查询会失败。

## 配置与安全

本服务使用 Druid、MyBatis-Plus 和无状态 Sa-Token JWT。`SA_TOKEN_JWT_SECRET` 必须与网关、user-service 相同；生产环境弱密钥会被 `SecretGuard` 拒绝。

## 测试与运行

```bash
mvn -pl content-service -am test
mvn -pl content-service -am package
java -jar content-service/target/content-service.jar
```

当前测试覆盖统计聚合和无状态 JWT 配置。接口改动还需启动网关后进行端到端验证。
