# 星笺后端架构（Spring Cloud Alibaba 微服务，对齐企业级参考架构）

> 版本矩阵：Spring Boot 3.2.12 / Spring Cloud 2023.0.6 / Spring Cloud Alibaba 2023.0.3.4 / Java 17 / MyBatis-Plus 3.5.15。
> 对外 API 路径与单体时期一致，前端无感。

## 拓扑

```
                      ┌──────────────────────────────┐
  前端 / curl ────────▶│ gateway-nacos-sentinel :8080 │ WebFlux 网关
                      │  SaReactorFilter 登录校验     │ Nacos 配置中心导入
                      │  Sentinel 网关限流            │ discovery locator 自动路由
                      └───────┬──────────────────────┘
                              │ 按前缀路由（lb:// 负载均衡）
        ┌──────────┬──────────┼──────────┬──────────┬──────────┐
        ▼          ▼          ▼          ▼          ▼          ▼
   user-service post-service meteor-svc echo-svc  link-svc  stats-service
      :8101       :8102       :8103      :8104      :8105      :8106
        │           │                                 │          │
        │      MySQL（共享 stellar_ink 库，表归属严格划分）     │ 无库
        └───────────┴──────────────────────────────────┴──────────┘
                              ▲
                              │ OpenFeign（service-api 契约 + fallback）
                       stats ─┘
        全部服务注册到 ──▶ Nacos :8848（standalone；注册中心 + 配置中心）
```

## 模块结构（对齐参考工程）

```
stellar-ink-server/
├── pom.xml                    父 POM：统一版本管理
├── common-components/         公共组件（被所有服务依赖，非独立运行）
│   ├── shared-model/          共享模型：Response/ErrorCode/异常/DTO/VO
│   ├── common-core/           核心基础设施：全局异常(Servlet+Reactive)/TraceId/MyBatis-Plus 配置/健康检查
│   └── service-api/           跨服务 Feign 契约：PostServiceClient + FallbackFactory
├── gateway-nacos-sentinel/    API 网关（8080，WebFlux）
├── user-service/              用户服务（8101）
├── post-service/              文章服务（8102）
├── meteor-service/            流星服务（8103）
├── echo-service/              回声服务（8104）
├── link-service/              星链服务（8105）
└── stats-service/             统计服务（8106，无库）
```

## 配置文件风格（每个服务统一）

| 文件 | 作用 |
|---|---|
| `application.yml` | 极简：port + 应用名 + `profiles.active: dev` |
| `application-dev.yml` | dev 全量配置：`spring.config.import: optional:nacos:<app>-dev.yaml` + Nacos 配置/发现 + **Druid** 数据源 + sa-token + springdoc/knife4j + actuator |
| `application-prod.yml` | 生产：敏感项全部走环境变量（`MYSQL_PASSWORD`、`SA_TOKEN_JWT_SECRET`、`NACOS_ADDR`） |
| `nacos-application-dev.yml` | 上传到 Nacos 的动态配置模板（Data ID：`<app>-dev.yaml`） |
| `logback-spring.xml` | 控制台 + 文件异步日志（`./logs/<app>.log`，按天+200MB 滚动，30 天） |

## 鉴权（Sa-Token，JWT 无状态模式）

- 登录：user-service `/auth/login` 调 `StpUtil.login(userId)` 签发 JWT，返回 `tokenName(Authorization) + tokenValue`
- 网关 `SaReactorFilter`：放行 GET/OPTIONS、`/auth/**`、公开写接口（`POST /echos`、`POST /links`、`POST /posts/{id}/glow`）；
  其余对 `/posts|/meteors|/links|/user` 的写请求 `StpUtil.checkLogin()`
- 无状态模式（`StpLogicJwtForStateless`）：token 自包含签名，网关与各服务用同一 `jwt-secret-key` 独立验签，无需 Redis 共享会话
- 鉴权失败由网关统一返回 `{"code":401,...}`（HTTP 200，SaResult 约定）

## 服务间调用

- 契约集中在 `service-api`：`@FeignClient(name="post-service", fallbackFactory=...)` + resilience4j 断路器（调用方配 `feign.circuitbreaker.enabled: true`）
- `/internal/**` 为服务间接口，网关不配路由，外部不可达
- `FeignTraceInterceptor` 透传 `X-Trace-Id` 与用户身份头

## 数据库策略

共享库模式（一个 `stellar_ink` 库，各服务只读写自己的表），兼容云数据库无建库权限场景。
初始化：`deploy/sql/01_schema.sql` + `02_init-data.sql`。拆库：改各服务 `MYSQL_DB` 环境变量。

## Docker 部署（deploy/docker）

生产环境一键编排：`deploy/docker/docker-compose.yml`（Nacos + 网关 + 6 服务 + 前端 Nginx，内部网络互通）。

- 服务器已有的 mysql(:3306)/redis(:6379)/qdrant(:6333-6334) 容器不归编排管；业务服务经
  `host.docker.internal`（host-gateway）访问宿主机 3306 上的 MySQL，Redis/Qdrant 暂未使用仅预留
- 敏感配置统一放 `deploy/docker/.env`（从 `.env.example` 复制，不入库）；各 Java 服务配 `mem_limit`
  并按 `MaxRAMPercentage=70` 控堆
- 后端统一镜像 `stellar-ink-server/Dockerfile`：Maven 多阶段构建全 reactor，各服务仅 build arg
  `JAR_PATH` 不同（jar 名对应各模块 `<finalName>`）
- 前端镜像 `stellar-ink-web/Dockerfile`：Vite 构建 → Nginx 托管 SPA（history 路由回退），
  `/posts` 等 API 前缀同源反代网关
- 首次部署 / 日常更新 / 运维命令见 `deploy/docker/README.md`

## 演进路线（按需，暂不实施）

- Sentinel 规则持久化到 Nacos（sentinel-datasource-nacos 已引入）
- Redis 分布式令牌桶限流（网关 RequestRateLimiter）
- stats 改事件驱动（RocketMQ）或物化视图
- 共享库拆分为 per-service 数据库
