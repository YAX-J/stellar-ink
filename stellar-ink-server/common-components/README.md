# 公共组件

本目录是后端公共 Maven 聚合模块，不独立启动，也不生成业务服务。

## 子模块

| 模块 | 职责 |
|---|---|
| [`shared-model/`](shared-model/README.md) | 统一响应、错误码、异常、DTO 和 VO |
| [`common-core/`](common-core/README.md) | 异常处理、TraceId、访问日志、鉴权辅助和 MyBatis-Plus 配置 |

## 使用原则

- 只有被多个运行模块真正复用的代码才进入公共组件。
- 业务规则、Mapper、实体和领域服务留在所属业务模块。
- shared-model 尽量保持轻量，不引入 Web、数据库或服务发现依赖。
- common-core 提供基础设施，不依赖具体业务包。
- 当前没有跨服务 Feign 调用，因此不保留空的契约模块；未来出现真实消费者时再按调用方向建立专用契约。

从后端父工程执行 `mvn package` 即可构建本目录全部模块。
