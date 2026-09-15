# 共享模型

`shared-model` 保存网关与业务服务共同使用的 Java 数据契约，不独立运行。

## 内容

| 包 | 说明 |
|---|---|
| `response/` | 统一响应 `Response<T>` |
| `enums/` | `ErrorCode`、`Role` 等共享枚举 |
| `exception/` | 业务异常与系统异常 |
| `dto/` | 接口请求模型，按 user/post/comment/meteor/echo/link 分包 |
| `vo/` | 接口响应模型，按领域分包（含文章评论） |

## 约束

- DTO 使用 Jakarta Validation 注解声明输入边界。
- VO 不包含密码、密钥等敏感字段。
- 不在这里放 Controller、Service、Mapper、数据库实体或业务实现。
- 修改对外 DTO/VO 时同步检查前端 store 和 [`../../../docs/api/README.md`](../../../docs/api/README.md)。
- 仅在多个模块确实共享时新增类型，模块内部模型应留在所属模块。

该模块由 [`../pom.xml`](../pom.xml) 聚合，通过后端父工程统一构建。
