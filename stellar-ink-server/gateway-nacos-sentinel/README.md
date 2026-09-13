# API 网关

`gateway-nacos-sentinel` 是后端唯一对外入口，端口为 `8080`，使用 Spring Cloud Gateway WebFlux。

## 职责

- 通过 Nacos 发现 `user-service` 和 `content-service`。
- 显式维护业务路由，不启用 discovery locator 自动路由。
- 处理 CORS、Sa-Token JWT 登录校验与角色门槛。
- 接入 Sentinel 网关限流与 Actuator 健康指标。
- 返回统一的 401/403 JSON 错误。

## 路由

| 下游 | 路径 |
|---|---|
| user-service | `/auth/**`、`/user/**` |
| content-service | `/posts/**`、`/tags/**`、`/search/**`、`/meteors/**`、`/echos/**`、`/links/**`、`/stats/**` |

`/internal/**` 不得配置为外部路由。新增业务路径时必须同时检查网关路由、鉴权规则、前端代理和 Nginx 代理。

## 鉴权规则

- GET、HEAD、OPTIONS 默认公开，但 `/user/list` 与 `/posts/mine` 在公开判断之前单独校验角色。
- 登录、注册、回声投递、友链申请和文章 glow 为显式公开写接口。
- 文章和流星写操作要求 AUTHOR。
- 友链审核与用户角色调整要求 ADMIN。
- 其他请求默认要求登录。

业务服务必须继续执行防御性权限复核，不能只信任网关。

## 运行

```bash
mvn -pl gateway-nacos-sentinel -am package
java -jar gateway-nacos-sentinel/target/gateway-nacos-sentinel.jar
```

启动前确保 Nacos 可访问，且 `SA_TOKEN_JWT_SECRET` 与两个业务服务一致。配置文件说明见 [`../README.md`](../README.md)。
