# 星笺后端架构（Spring Cloud Alibaba 微服务）

> v2 架构：单体改造为微服务。对外 API 路径与 v1 完全一致，前端无感。
> 依赖版本矩阵：Spring Boot 3.3.12 / Spring Cloud 2023.0.3 / Spring Cloud Alibaba 2023.0.3.3（官方匹配组合）。

## 拓扑

```
                      ┌────────────────────┐
  前端 / curl ────────▶│  gateway :8080     │ 统一入口：路由 + CORS + JWT 鉴权
                      └───────┬────────────┘
                              │ 按前缀路由（lb:// 服务发现负载均衡）
        ┌──────────┬──────────┼──────────┬──────────┬──────────┐
        ▼          ▼          ▼          ▼          ▼          ▼
   user-service post-service meteor-svc echo-svc  link-svc  stats-service
      :8101       :8102       :8103      :8104      :8105      :8106
        │           │                          │          │
        │ MySQL     │ MySQL  H2/MySQL  H2/MySQL │ MySQL    │ 无库
        └───────────┴──────────共享 stellar_ink 库─┴──────────┘
                              ▲
                              │ OpenFeign（/internal/posts/summary）
                       stats ─┘
        全部服务注册到 ──▶ Nacos :8848（standalone，控制台 /nacos）
```

## 服务清单

| 服务 | 端口 | 职责 | 拥有的表 |
|---|---|---|---|
| gateway-service | 8080 | 对外唯一入口：路由、CORS、JWT 校验并注入 `X-User-Id` | - |
| user-service | 8101 | 登录（JWT 签发）、站长资料 | `user` |
| post-service | 8102 | 文章 CRUD/分页/详情/相邻星/glow、标签光谱、搜索、内部汇总 | `post` |
| meteor-service | 8103 | 流星备忘录 | `meteor` |
| echo-service | 8104 | 回声漂流瓶 | `echo` |
| link-service | 8105 | 友链申请与确认 | `link` |
| stats-service | 8106 | 写作脉搏（OpenFeign 聚合 post-service） | - |

公共库 `stellar-ink-common`：`Result` 统一响应、`BusinessException`、`JwtUtil`、常量（零 Spring 依赖）。

## 鉴权边界（网关 AuthGlobalFilter）

- 放行：所有 GET/OPTIONS、`/auth/**`、公开写接口（`POST /echos`、`POST /links`、`POST /posts/{id}/glow`）
- 需 JWT：对 `/posts/**`、`/meteors/**`、`/links/**`、`/user/**` 的写请求
- 校验通过后网关**剥离客户端伪造的 `X-User-Id`**，注入可信用户头转发下游；下游只读 `X-User-Id`
- `/internal/**` 为服务间接口，网关不配路由，外部不可达

## 数据库策略

当前为共享库模式（一个 `stellar_ink` 库，各服务只读写自己的表）——兼容云数据库无建库权限的场景。
初始化脚本见 `deploy/sql/`。若将来拆库：每个服务有独立的 `MYSQL_DB` 环境变量，改环境变量即可。

## 演进路线（按需，暂不实施）

- Nacos 同时作为配置中心（`spring-cloud-starter-alibaba-nacos-config`）
- 服务间调用加 Sentinel 限流熔断
- stats 改为事件驱动（MQ）或物化视图，替代实时 Feign 聚合
- 共享库拆分为 per-service 数据库
