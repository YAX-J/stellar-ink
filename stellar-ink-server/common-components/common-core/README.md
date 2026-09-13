# 公共基础设施

`common-core` 为 Servlet 业务服务和 WebFlux 网关提供通用基础设施，不独立运行。

## 主要能力

- `GlobalExceptionHandler` / `GlobalReactiveExceptionHandler`：统一异常响应。
- `TraceIdFilter`：生成或透传 `X-Trace-Id`，写入 MDC 和响应。
- `LogInterceptor`：记录 API 方法、路径、状态与耗时。
- `AuthHelper`：读取登录用户和角色，并执行业务层权限复核。
- `MybatisPlusConfig`：分页插件与分页上限。
- `SecretGuard`：生产环境 JWT 密钥启动检查。
- `SimpleHealthController`：提供 `/health`。

## 约束

- 不依赖 user、content 等业务包。
- 不存放领域 DTO、数据库实体或业务规则。
- 安全校验默认失败关闭，日志不得输出密码、token 或密钥。
- 同时支持 Servlet 与 Reactive 的代码需要保持类型边界清晰，避免把阻塞逻辑带入网关。
- 当前没有 Feign 调用，不在此模块保留 Feign 拦截器或 OpenFeign 依赖。

修改公共基础设施会影响全部运行服务，提交前必须执行后端全量 `mvn package`。
