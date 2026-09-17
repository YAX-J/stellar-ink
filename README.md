# 星笺 · STELLAR INK

星笺是一个将文章比作星辰的夜间写作博客。前端以深色星空、缓慢和诗意为视觉基调；后端使用 Spring Cloud Alibaba，将运行边界收敛为网关、用户服务和内容服务。

## 当前架构

```text
浏览器
  │
  ▼
Vue 3 + Nginx
  │ /auth /user /posts /meteors /echos /links /stats ...
  ▼
gateway-nacos-sentinel :8080
  ├── user-service    :8101  认证、用户、角色
  └── content-service :8102  文章、流星、回声、星链、统计
                │
                ▼
          MySQL stellar_ink
```

三个 Java 进程通过 Nacos 注册与读取配置。对外接口只经过网关，前端始终使用稳定的相对路径。

## 目录

| 目录 | 说明 |
|---|---|
| [`stellar-ink-web/`](stellar-ink-web/README.md) | Vue 3 前端应用 |
| [`stellar-ink-server/`](stellar-ink-server/README.md) | Spring Cloud Alibaba 后端 |
| [`stellar-ink-ai/`](stellar-ink-ai/README.md) | Python AI 编排服务预留目录，尚未实施 |
| [`deploy/`](deploy/README.md) | SQL、Docker Compose、Nginx 和本地脚本 |
| [`docs/`](docs/README.md) | 架构、接口与 AI 技术文档 |

## 本地开发

### 1. 准备基础设施

- Java 17、Maven 3.9+
- Node.js 20+、npm
- MySQL 8，数据库名默认 `stellar_ink`
- Nacos，默认 `127.0.0.1:8848`

按顺序执行 [`deploy/sql/`](deploy/sql/README.md) 中的数据库脚本。已有旧库还必须执行 `03_multi-author.sql`，否则文章和流星查询会因缺少 `user_id` 失败。

### 2. 构建后端

```bash
cd stellar-ink-server
mvn package
```

启动 Nacos 后，可分别运行三个 JAR：

```bash
java -jar gateway-nacos-sentinel/target/gateway-nacos-sentinel.jar
java -jar user-service/target/user-service.jar
java -jar content-service/target/content-service.jar
```

Windows 本地私有脚本 `deploy/scripts/start-all.bat` 可一次启动全部进程；该文件含环境连接信息，受 `.gitignore` 管理。

### 3. 启动前端

```bash
cd stellar-ink-web
npm install
npm run dev
```

访问 `http://localhost:5173`。Vite 会把 API 请求代理到网关 `http://localhost:8080`。

## 构建验证

```bash
cd stellar-ink-server && mvn package
cd ../stellar-ink-web && npm run build
```

接口变更还需要启动服务，并通过网关验证受影响的路径。完整接口见 [`docs/api/README.md`](docs/api/README.md)。

## 生产部署

生产环境使用 [`deploy/docker/docker-compose.yml`](deploy/docker/docker-compose.yml)，启动前端 Nginx、网关和两个业务服务，复用宿主机已有的 Nacos 与 MySQL。具体变量和命令见 [`deploy/docker/README.md`](deploy/docker/README.md)。

## 开发约定

开始修改代码前先阅读 [`AGENTS.md`](AGENTS.md)。重点约束包括：

- 回复、注释和提交信息使用中文，代码标识符使用英文。
- 前端视觉以 `styles/tokens/variables.css` 的语义变量为准，不引入 UI 组件库。
- 业务数据通过 Pinia store 获取，组件不直接请求后端。
- 后端接口统一返回 `Response<T>`，鉴权在网关和业务服务双重校验。
- AI、评论、上传、全文检索和 Redis 限流等能力，未有明确任务时不提前开发。

建议提交格式：`type(范围): 中文主题`。代码由项目维护者自行提交和推送。
