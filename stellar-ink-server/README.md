# 星笺后端

本目录是 Spring Cloud Alibaba 多模块 Maven 工程。运行时由一个网关和两个业务服务组成，统一使用 Java 17、Nacos、MySQL、MyBatis-Plus 与 Sa-Token。

## 运行拓扑

| 模块 | 端口 | 职责 |
|---|---:|---|
| [`gateway-nacos-sentinel/`](gateway-nacos-sentinel/README.md) | 8080 | 对外入口、路由、CORS、鉴权、Sentinel |
| [`user-service/`](user-service/README.md) | 8101 | 登录注册、用户资料、角色和作者摘要 |
| [`content-service/`](content-service/README.md) | 8102 | 文章、标签、搜索、流星、回声、星链和统计 |

公共代码位于 [`common-components/`](common-components/README.md)。`stellar-ink-ai-client/` 仍是 AI 阶段的预留目录，当前不在 Maven reactor 中。

## 构建

```bash
mvn package
```

该命令会编译全部模块、运行测试并生成三个可执行 JAR。跳过测试只用于临时排查：

```bash
mvn -DskipTests package
```

## 启动顺序

1. 启动 MySQL 8，并初始化 `stellar_ink` 数据库。
2. 启动 Nacos，默认地址为 `127.0.0.1:8848`。
3. 启动 `user-service` 与 `content-service`。
4. 启动网关。
5. 通过 `http://localhost:8080` 验证接口。

```bash
java -jar user-service/target/user-service.jar
java -jar content-service/target/content-service.jar
java -jar gateway-nacos-sentinel/target/gateway-nacos-sentinel.jar
```

Windows 可使用受 `.gitignore` 管理的 `../deploy/scripts/start-all.bat`。生产部署见 [`../deploy/docker/README.md`](../deploy/docker/README.md)。

## 配置

每个运行模块包含五类资源：

| 文件 | 作用 |
|---|---|
| `application.yml` | 端口、应用名和默认 profile |
| `application-dev.yml` | 本地数据库、Nacos、文档和监控配置 |
| `application-prod.yml` | 生产配置，敏感值来自环境变量 |
| `nacos-application-dev.yml` | 可上传到 Nacos 的动态配置模板 |
| `logback-spring.xml` | 控制台与滚动文件日志 |

常用环境变量包括 `NACOS_ADDR`、`NACOS_NAMESPACE`、`MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DB`、`MYSQL_USER`、`MYSQL_PASSWORD` 和 `SA_TOKEN_JWT_SECRET`。

## 工程边界

- 对外接口统一返回 `Response<T>`，异常由 common-core 转换并附带 traceId。
- JWT 使用无状态模式，网关和业务服务必须使用相同密钥。
- 网关做第一层角色校验，业务服务使用 `AuthHelper` 防御性复核。
- user-service 只负责 `user` 表；content-service 负责 `post`、`meteor`、`echo`、`link` 表。
- 当前业务服务之间没有同步调用，不要为同进程领域引入 Feign。
- 新增或修改接口时同步更新 [`../docs/api/README.md`](../docs/api/README.md)。

完整架构见 [`../docs/architecture/README.md`](../docs/architecture/README.md)。
