# 用户服务

`user-service` 负责身份认证、用户资料、作者摘要和角色管理，端口为 `8101`，在 Nacos 中注册为 `user-service`。

## 接口范围

- `/auth/login`、`/auth/register`、`/auth/logout`
- `/user/profile`、`/user/password`
- `/user/authors`
- `/user/list`、`/user/{id}/role`

详细请求与响应见 [`../../docs/api/README.md`](../../docs/api/README.md)。

## 数据边界

本服务只读写 MySQL 的 ``user`` 表。表名在部分环境是保留字，实体必须保留 `@TableName("`user`")`。密码只以 BCrypt 哈希形式保存，不得写日志或出现在 VO 中。

## 权限

- 注册用户固定为 READER，注册成功后签发 JWT。
- 用户可以查看和修改自己的资料、修改密码。
- `/user/authors` 只返回公开作者摘要。
- 用户列表和角色调整只允许 ADMIN。
- JWT 使用 `StpLogicJwtForStateless`，密钥必须与网关、content-service 一致。

## 包结构

| 包 | 职责 |
|---|---|
| `controller/` | 认证与用户 HTTP 接口 |
| `service/` | 用户业务接口 |
| `service/impl/` | 登录、注册、资料、角色与防爆破逻辑 |
| `mapper/` | MyBatis-Plus Mapper |
| `pojo/` | `user` 表实体 |
| `config/` | Sa-Token 无状态 JWT 配置 |

## 构建与运行

```bash
mvn -pl user-service -am package
java -jar user-service/target/user-service.jar
```

启动需要 Nacos、MySQL 及正确的数据库和 JWT 环境变量。
