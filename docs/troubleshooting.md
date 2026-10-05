# 排查手册（Troubleshooting）

> **用法：遇到问题先在这里按「症状」搜。** 每条都写成四段：**症状 → 真因 → 修法 → 怎么一次定位**。
> 本文件只收「已经真实发生过、并且知道原因」的问题；没发生过的猜测不写进来。
> 相关文档：[AGENTS.md](../AGENTS.md)（编码约定与红线）、[docs/status.md](status.md)（功能真相源）、
> [docs/architecture/README.md](architecture/README.md)（架构与深度排查）、[docs/ai/status.md](ai/status.md)（AI 逐阶段核验）。

---

## 0. 30 秒急救（最高频的四件事）

| 症状 | 先做这一件事 |
|---|---|
| 页面/接口一片 **503** | 看响应体里的 `hint`/`code`：`no-instance` = 服务没起或没注册进 Nacos；`redis` = 网关连不上 Redis（撤销校验 **fail-closed**）；`upstream` = 下游自己返回了 503 |
| 接口 **200 + code 500「系统繁忙」** | **5xx 的 message 会被前端丢弃**，所以必须拿响应体里的 **`traceId`** 去日志里搜（见 §7 工具箱） |
| `start-all.bat` 起不来 / 端口有的起有的不起 | 先查 **jar 是不是瘦 jar**（§4.3）+ 该服务的 `logs\<name>_service.log` 末尾 |
| `mvn package` 报 **`Unable to rename ... .jar`** | **jar 被正在运行的服务锁住了**：先 `deploy\scripts\stop-all.bat` 再打包（§4.2） |

---

## 1. 环境与启动

### 1.1 `start-all.bat` 报「系统找不到指定的批处理标签」
- **真因**：`.bat` 是 **LF 换行**。cmd 按**字节偏移**扫描标签，LF-only 会让偏移算错。
- **修法**：把文件存成 **CRLF**。`call :label` 有时反而能过 —— **别据此以为没事**。
- **一次定位**：复制一份，把 `call :kill_port` 与 `start "si-…"` 两类行换成 `echo` 再跑；控制流（标签、计数器、汇总）全都能验到，又不会真启服务。

### 1.2 服务起来但接口 503，日志里看不出异常
- **真因**：本机 dev 与测试环境**共用同一个 Nacos 命名空间**，本机服务注册进的是测试环境的注册中心；测试机一旦也跑同名服务，网关 `lb://` 就会在「本机 / 测试机」之间轮询，而两边连的不是同一个 MySQL。
- **修法**：本机起 `tools/nacos` 并把 dev 指向 `127.0.0.1:8848`；或确认测试机没有跑应用。
- **一次定位**：`NACOS_ADDR` / `NACOS_NAMESPACE` 两个值是否与测试机相同。

### 1.3 重启后第一个请求慢约 5 秒
- **真因**：**JCE 在校验 fat jar**（第一次用 HMAC 时 JVM 验「调用方所在 jar」的签名，嵌套 jar 下退化成逐条随机 `pread`）。特征是：公开接口快、第一个验签请求慢、并发请求同毫秒解开。
- **修法**：启动期预热（`JceWarmupRunner`，common-core 与网关各一份，**改一处同步另一处**），必须走生产入口 `SaJwtUtil` 的签 + 验（只从自己类里调 `javax.crypto` 实测 0~12ms，等于没预热）。
- **别误判**：这不是数据库慢（Druid 保活是另一件事，见 1.4）。

### 1.4 空闲一段后第一个 DB/Redis 请求卡十几秒或 503
- **真因**：跨公网的 NAT/防火墙**静默丢弃空闲 TCP 连接**，两端都以为还活着；借到半开连接时，要么等 JDBC `socketTimeout`（15s），要么网关撤销校验 fail-closed 直接 503（表现为「莫名 503，下一个请求又好了」）。
- **修法**：① JDBC URL 带 `connectTimeout=3000&socketTimeout=15000`；② Druid `keep-alive` + **`DataSourceKeepAliveHeartbeat` 每 30s 同时借出 initial-size 条**各跑一次 `SELECT 1`（只借一条还一条会反复热同一条，实测不够）；③ 网关 `RevokedTokenFilter` 重试一次 + 2s 整体上限；④ 四个服务必须连**同一个** Redis。
- **一次定位**：`SELECT time FROM information_schema.processlist WHERE host LIKE '<公网IP>%'` —— 不该有连接空闲超过 ~60 秒（排除已 kill 的旧 JVM 留下的僵尸连接）。

### 1.5 服务起不来：`UnsupportedClassVersionError: class file version 61.0`
- **真因**：本机 PATH 里 Oracle Java 8 排在 JDK 17 前面。
- **修法**：启动脚本把 JDK 17 的 `bin` 前置到 PATH；用绝对路径的 `java`/`javaw`。

### 1.6 Nacos 上的配置整份不生效（且**静默**）
- **真因**：JDK 17 在中文 Windows 上默认 `file.encoding=GBK`，Spring Cloud Alibaba 用默认字符集重编码 Nacos 配置内容 → UTF-8 的中文注释解析失败 `MalformedInputException`；而导入是 `optional:`，失败**不报错**，服务继续用本地 `application-dev.yml`。
- **修法**：启动参数加 `-Dfile.encoding=UTF-8`（`spring.cloud.nacos.config.encode=UTF-8` **不管用**）。
- **一次定位**：日志里搜 `MalformedInputException`；或把 Nacos 配置改成纯 ASCII 试。

---

## 2. 鉴权与会话

### 2.1 未设置 `SA_TOKEN_JWT_SECRET` 竟然也能启动
- **真因**：`${SA_TOKEN_JWT_SECRET}` 这种「无默认值」写法**不是** fail-fast —— Spring 会把**字面量字符串** `"${SA_TOKEN_JWT_SECRET}"` 当密钥传下去，服务照常启动（`/actuator/env` 里能看到）。等于公开可自签。
- **修法**：密钥规则收在 `shared-model` 的 `JwtSecretPolicy`（唯一实现），业务服务经 `SecretGuard`、网关经 `GatewaySecretGuard`：**dev 放行，其余档位**空值 / 字面量 / 仓库默认值 / 官方示例 / 长度 < 32 **一律拒绝启动**。
- **代价**：线上与测试机重启前必须确认密钥已真实注入，否则网关起不来（这正是想要的行为）。

### 2.2 令牌过期后页面只显示「读取失败」，不清会话
- **真因**：网关**放行 GET**，读接口的未登录由服务端兜底 → **HTTP 200 + body `code=401`**；只看 HTTP 状态就会漏判。
- **修法**：前端统一 `isAuthError()`（`code === 401 || status === 401`），出错 → `utils/bus.js` → 清会话带 `redirect` 跳登录；页面不要各写一套。

### 2.3 调整用户角色后不生效
- **真因**：`PUT /user/{id}/role` 只改库、不重签 JWT，而网关读的是 token 里的 `role` extra。
- **修法**：让该用户**重新登录**（或后续实现「重新签发 token」）。

### 2.4 明明登出了，旧令牌还能用
- **真因**：撤销列表在 Redis 里；如果网关与 user-service 连的**不是同一个 Redis**，写入与读取就分家了。
- **修法**：四个服务统一 `REDIS_HOST`；网关撤销校验 **fail-closed**（Redis 不可用返回 503，不得放行）；**绝不要动 `stellar-ink:auth:revoked:*`**（删掉等于让已登出的令牌复活）。

---

## 3. 数据与 MyBatis

### 3.1 「接口成功、刷新又回来了」
- **真因**：MyBatis-Plus 的 `updateById` **默认忽略 null 字段**，用它清空一列等于没清。
- **修法**：清空一律走 `LambdaUpdateWrapper.set(..., null)`（仓库里已有专用方法，如 `AiProviderConfigMapper.updateWithModelId`）。踩过的地方：清 `ai_provider_config.model_id`、清 `user.avatar_url`。

### 3.2 「我明明没配模型，怎么还在回答」
- **真因**：曾经有「配置读不到就退回 Fake」的设计 —— 把「忘了配」表现成「回答质量差」。
- **修法**：**空配置就报错**（「角色 X 尚未配置模型（请在 AI 实验室 → 模型配置里填写）」+ 400），`fake` 必须**显式**配置。

### 3.3 单测莫名连上真库 / 测试机起来却用了 H2
- **真因**：profile 名撞车 —— 单测的 `unittest` 与「测试环境档」`test` 若同名，同名资源只取 classpath 第一个，**且不报错**。
- **修法**：单测一律 `@ActiveProfiles("unittest")`；测试环境档固定 `test`。

### 3.4 某条用例单独跑能过、全量跑就红
- **真因**：H2 内存库 `DB_CLOSE_DELAY=-1` 让**跨测试类共享**同一个库，前一个类留下的数据影响了后一个。
- **修法**：在写库的用例里 `@AfterEach` 清表（仓库里 `AiUsageServiceImplTest` 就是这么修的）。

### 3.5 直接写库后页面还是旧数据
- **真因**：绕过服务写库**不会推进 Redis 缓存版本**。
- **修法**：写完清 `stellar-ink:content:cache:*`（`KEYS` 后 `DEL`）。

### 3.6 列表时间倒挂
- **真因**：列表默认 `ORDER BY id DESC`，而种子数据的 id 与 `created_at` 反向。
- **修法**：种子数据的 id 必须与时间**同向递增**。

---

## 4. 构建与运行（Windows 特有，最容易反复踩）

### 4.1 `mvn package` 报 `Unable to rename ... .jar` to `.jar.original`
- **真因**：该服务的 **jar 正被运行中的进程打开**，Windows 不允许重命名。
- **修法**：先 `deploy\scripts\stop-all.bat`（它按端口杀进程），再打包，再 `start-all.bat`。
- **规则**：**改后端代码前先停进程**。

### 4.2 （上一条的后果）jar 变成**瘦 jar**，服务起不来
- **症状**：`mvn package` 失败后，`target\<name>.jar` 只有 **0.1~0.2MB**，`java -jar` 起不来（缺依赖）。
- **真因**：`maven-jar-plugin` **先**把 jar 覆盖成「只有自己的类」的瘦 jar，随后 `spring-boot:repackage` 想把它重命名/加胖时失败 —— 于是**fat jar 没了**。
- **修法**：停服务 → 重新 `mvn -DskipTests package` → 复核 jar（见下）→ 启动。
- **一次定位**（比看大小更准）：
  ```powershell
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $z=[System.IO.Compression.ZipFile]::OpenRead("stellar-ink-server\ai-service\target\ai-service.jar")
  ($z.Entries | Where-Object { $_.FullName -like 'BOOT-INF/*' } | Measure-Object).Count
  ```
  **0 = 瘦 jar（坏的）**；正常是 150~300。

### 4.3 `start-all.bat` 部分服务起不来
- **一次定位**：① 端口 `8080/8101/8102/8107/8200` 谁在听；② 各服务 `logs\<name>_service.log` 末尾 20 行；
  ③ 注意 **logback 的日志文件名是 `*_service.log`（下划线）**，不是 `<artifactId>.log。
- **注意**：`SHOW_LOGS=1`（默认）时脚本最后会进入「同终端跟随日志」并**一直停在那里**，`Ctrl+C` 只停跟随不停服务。

### 4.4 端口被占用 / 旧进程残留
- **修法**：`stop-all.bat`（按端口杀，含 8200 与本地 nacos 8848/9848）。

---

## 5. AI 链路

### 5.1 接口 200 + `code 500「系统繁忙」`（最值得记住的一类）
- **真因（真实案例）**：`/ai/writing/style` 500。链条是三层套娃：
  1. Java 用 `WritingStyleRequestDTO.builder().authorId(id).build()` 构造请求，`maxSamples` 是 `Integer` 且**没有 Builder 默认值** → Jackson 默认把没设的包装类型序列化成 `"maxSamples": null`；
  2. Python 的 `max_samples: int` 不接受 null → **422**；422 的响应体是 FastAPI 的 `{detail:[…]}`，属 `PythonErrorDecoder` 眼里的「契约外」→ 退回默认解码器；
  3. `FeignException` → 全局处理器 → `HTTP 200 + code=500`。
- **修法**：给该字段 `@Builder.Default = 20`（可选字段没给就用默认值）。
- **⚠️ 不要用「全局不序列化 null」去修**：先给 11 个 DTO 加 `@JsonInclude(NON_NULL)` 会让 **`AiContractTest` 直接红** —— 契约 fixture 里**就是带显式 null 的**，「允许发 null」是冻结契约的一部分。
- **一次定位**：拿 `traceId` 搜日志，能看到 `PythonErrorDecoder - Python 返回了契约外的错误：… status=422` 与 `LogInterceptor - API-ACCESS … 200`（**200 是 HTTP 状态，code 在 body 里**）。

### 5.2 Python 的 401/403 不能当成「用户没登录」
- **真因**：Python 侧的 401/403 只可能来自**内部签名校验**；映射成 `UNAUTHORIZED` 会让前端清会话、把用户踢出去。
- **修法**：`PythonErrorDecoder` 里这两个状态映射成「服务不可用」，429 保留上游那句「稍后重试」；契约外的错误体不得回显原文。

### 5.3 内部调用报 401（签名不过）
- **真因**：标准串不一致。跨语言固定为：
  `METHOD \n PATH \n TIMESTAMP_MS \n NONCE \n SHA256_HEX(BODY) \n USER_ID \n ROLE`，HMAC-SHA256 小写十六进制放 `X-AI-Signature`。**身份字段必须参与签名**（否则内网中间人改 `X-AI-User-Id` 就能冒充 ADMIN）。
- **修法**：一致性由 `tests/fixtures/signature_vector.json` 的固定向量守住（两侧单测都读它）；时间窗 ±60s；nonce 不重复；校验顺序固定「时间戳 → 签名 → nonce」。

### 5.4 模型报「名字不存在」，其实是路径拼错
- **真因**：`base_url` 是 **API 根**，路径由代码拼（`/chat/completions`、`/embeddings`、`/rerank`）。填成完整端点会拼出 `/rerank/rerank` → 404，而报出来的话是「模型名不存在」。
- **修法**：`base_url` 只填根。面板的「测试连接」是 `scope: tcp_only`，对这类错误**一声不响** —— 验证配置要跑 `uv run python scripts/provider_smoke.py`。

### 5.5 「额度用尽」被说成「稍后重试」
- **真因**：免费档是**每模型每日 50 次**（次日 UTC 零点重置），退避几秒救不了。
- **修法**：`ProviderQuotaExhaustedError`（`retryable=False`），消息里必须带「每日上限 + 重置时间 + 重试无用」；对外仍是 `AI_RATE_LIMITED`。

### 5.6 三个 dense 管道各嵌一遍整库 → 全被降级成「拒答」
- **真因**：没有嵌入缓存时，一次评测把免费额度打光。
- **修法**：`app/providers/embedding_cache.py` 只包**嵌入**（不包 chat），键含**模型指纹**、有界 LRU、失败不缓存。

### 5.7 「链路全对、结果全错」：换了嵌入模型却还用旧索引
- **真因**：向量库对**维度**有硬约束（不同维度直接失败），但**同维度不同模型不会失败**，只是相似度毫无意义。
- **修法**：写入时把 `modelFingerprint` 记进 payload（`QdrantVectorStore.point()`），检索/写入前用 `assert_model_fingerprint()` 比对：**空索引或旧数据没这字段就放行**（不能因为「没记录」挡住第一次写入），不一致则**抛错**并提示「换嵌入模型必须整库重建」。

### 5.8 Qdrant：脚本报 404 `Collection doesn't exist`
- **真因**：索引还没建（集合不存在），不是脚本坏了。
- **修法**：`index_reconcile.py` 现在把它当成「一篇都没索引」如实说明；建索引走 `POST /admin/index/rebuild`（Python）或 `/ai/admin/index/rebuild`（网关）。
- **⚠️ 路径必须逐字一致**：`AiContractPaths.INDEX_REBUILD = "/admin/index/rebuild"`，而 `PythonAiClient` **没有 path 前缀** —— 一度被写成 `/index/rebuild`，接上去必然 404，而报出来的话是「Python 不可用」。

### 5.9 **应用其实不用 Qdrant**
- **事实**：`assembly.pipeline_for` 默认**不传** `dense_store`，dense 检索走**内存通路**（整库嵌入 + 本地余弦）。应用不碰 Qdrant 时，Qdrant 没起也不影响问答。
- **要切换**：`AI_DENSE_STORE_ENABLED=true`（部署期开关，进程内不切换；管道是缓存的，所以用进程级单例持有客户端）。**打开前必须先验收**（黄金集 30 题「不劣化才切」）。
- **⚠️ 不要先加 payload 过滤**：payload 里没有对应字段时 Qdrant 过滤会**静默命中零条**，问答变成「没有依据」而日志一切正常。顺序必须是「先写字段，再过滤」。

### 5.10 「发布/删除后索引不更新」
- **机制**：增量靠**对账**而不是事件（事件会丢，对账每轮自愈）：
  投影（`POST /ai/admin/corpus/sync`）→ 对账（`POST /ai/admin/index/reconcile`）。
- **注意**：对账定时任务**默认关闭**（它要花嵌入额度）；投影同步默认开启（只读清单不花钱）。

### 5.11 私有笔记/草稿会不会进知识库
- **不变量**：可见性规则只在 content-service 一处实现（文章 `status=1`；笔记 `status=1 AND visibility='PUBLIC'`），投影的删除侧会把「转私有/下架」的行清掉。
- **两道闸门**：清单里不出现 + 按 id 取正文也 404。**改任何一边都要重跑 `InternalCorpusServiceImplTest` 的私有笔记用例。**

---

## 6. 前端

### 6.1 「保存按钮是假的」
- **真因**：写成功之后的**刷新失败**（如刷新列表拿到 503）被冒泡成「保存失败」。
- **修法**：① 先**就地更新本地状态**；② 刷新走 best-effort（失败只记错误、不抛）。`scripts/ai-store-selfcheck.mjs` 把这条变成 `npm run check` 的一部分。

### 6.2 禁用按钮和可点按钮长得一样
- **修法**：`.btn:disabled` 必须有可见差异；保存类按钮**只在提交中**禁用；字段没填全时点得动并就地提示「还差：…」。

### 6.3 多根组件上 `v-show` 无效
- **修法**：组件模板保持**单根节点**（Vue 只警告不报错）。

### 6.4 `.reveal` 之后改不动透明度
- **真因**：入场动画 `fill-mode: both` 把 opacity 钉死在 1。
- **修法**：需要动态透明度的元素不加 reveal，或同时 `animation: none`。

### 6.5 `position:fixed` 的浮件行为怪异
- **真因**：祖先元素的 `filter`/`transform` 会成为 fixed 后代的包含块，`opacity` 会连带全部后代。
- **修法**：别把这些属性写在需要 fixed 悬浮件的父级上。

### 6.6 编辑器依赖进了首屏包
- **规则**：CodeMirror 6 只允许 `views/notes/NoteEditView.vue` 通过 `defineAsyncComponent` 懒加载，其它页面 import 会把 520KB（gzip 180KB）带进首屏。

### 6.7 笔记正文被空内容覆盖
- **真因**：父组件 `modelValue` 在保存/切换时会短暂变空，编辑器照单全收。
- **修法**：`applyExternalContent` 应在已有内容时拒绝被空字符串覆盖。

---

## 7. 排查工具箱（照着敲）

```powershell
# 1) 用 traceId 定位 5xx（响应体里就有；前端会丢弃 5xx 的 message，所以这是唯一可靠入口）
Select-String -Path D:\myproject\stellar-ink\logs\*_service.log -Pattern '<traceId>' -Encoding UTF8

# 2) 端口与进程
foreach ($p in 8080,8101,8102,8107,8200) {
  $c = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  "$p -> " + $(if ($c) { "PID $($c.OwningProcess)" } else { '空闲' })
}

# 3) 健康与 AI 链路
foreach ($p in 8101,8102,8080,8107) { (Invoke-RestMethod "http://localhost:$p/actuator/health").status }
(Invoke-RestMethod 'http://localhost:8107/ai/health').data.available   # Java -> Python

# 4) jar 是否健康（0 = 瘦 jar，坏的）
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z=[System.IO.Compression.ZipFile]::OpenRead("D:\myproject\stellar-ink\stellar-ink-server\ai-service\target\ai-service.jar")
($z.Entries | Where-Object { $_.FullName -like 'BOOT-INF/*' } | Measure-Object).Count

# 5) 语料/索引核对（Python，直连测试机 Qdrant）
cd D:\myproject\stellar-ink\stellar-ink-ai
uv run python scripts/qdrant_smoke.py        # 协议 + 端到端（建临时集合，跑完删掉）
uv run python scripts/index_reconcile.py     # 语料 vs 索引的对账（只读，不动索引）
```

**日志位置**：`D:\myproject\stellar-ink\logs\` —— 四个 Java 服务是 `<name>_service.log`
（`gateway_service` / `user_service` / `content_service` / `ai_service`），Python 是 `ai_python.log`。
`start-all.bat` 默认（`SHOW_LOGS=1`）会在同一个终端里跟随这五份日志，带 `[服务名]` 前缀与颜色；
`Ctrl+C` 只停止跟随，**不停服务**。

---

## 8. 协作与工具链（这几条最容易反复犯）

### 8.1 `docs/` 下新增文件默认**不入库**
- **真因**：`docs/*` 被 gitignore，只放开了 `docs/api`、`docs/architecture`、`docs/ai`、`docs/status.md`。
- **修法**：新文档要么放进已放开的目录，要么在 `.gitignore` 里显式收窄（如本文件与 `docs/status.md`），要么 `git add -f`。
- **教训**：曾经多轮「同步了 docs/status.md」其实**没进任何提交**，克隆出来的仓库里那些链接是死的。

### 8.2 PowerShell 的三个静默陷阱（本仓库踩过多次）
- **单引号里的 `` `r`n `` 不是换行**，是字面量 → 会把 `` `r`n `` 写进文件（曾把 Java 代码写坏）。
- **`\"` 不是转义** → 整条命令在**解析期**失败、什么都没执行（看起来像「跑完了没输出」）。要用反引号 `` `" `` 或字符串拼接。
- **`-replace '^import '` 会替换所有匹配** → 曾给一个文件插入 7 份重复 import。要限定次数就用 `[regex]::Replace($s,$p,$r,1)` 或行级处理。
- **字符串 `Replace` 匹配不上 CRLF 文件**：多行改动请用编辑工具，别用带真实换行的 `Replace`。
- **`[System.IO.File]::ReadAllText('相对路径')` 用的是进程工作目录**，不是 PowerShell 的 `cd`。

### 8.3 `Select-Object -First N` 会掐断管道
- **表现**：`mvn … | Select-Object -First 20` 会让 mvn 中途收到断管，退出码变成 -1，输出也被截断。
- **修法**：重定向到文件（`mvn … > $log 2>&1`）再筛文件。

### 8.4 `git checkout -b test/xxx` 静默失败
- **真因**：`test` 已经是分支名，git 不允许 `refs/heads/test/...`。
- **后果**：`checkout -b` 失败**不会让命令链中断**，后续命令照跑 → 提交直接落在 `test` 上、合并也没发生。
- **修法**：分支名别用已有分支前缀（用 `fix/`、`feature/`、`chore/`）；建完分支**显式检查** `git branch --show-current`。

### 8.5 `.ps1` 必须带 BOM（PowerShell 5.1）
- **症状**：脚本报一个**假的**「意外的 }」，行号指向完全无关的地方。
- **真因**：PowerShell 5.1 把**无 BOM 的 UTF-8** `.ps1` 当 ANSI(GBK) 解码，中文注释里被误读的字节**吃掉了换行**。
- **修法**：脚本存成 **UTF-8 with BOM**（仓库里 `deploy/scripts/tail-logs.ps1` 文件头写明了这条）。

### 8.6 契约/路径这类「字符串」错误
- **症状**：接上去必然 404 / 参数校验失败，但报出来的是「Python 不可用」「系统繁忙」。
- **修法**：改动跨服务路径/字段时，**两侧一起改**，并让用例钉住（契约 fixture、`EXPOSED_PATHS` 白名单、`AiContractTest`）。
  本仓库的规矩：改契约要同时改 Python 模型 + fixture + Java DTO + `docs/api/README.md`。

### 8.7 FastAPI 的响应模型推断会让**所有**测试一起挂
- **症状**：`Invalid args for response field`，13 个测试文件全部 collection 失败。
- **真因**：返回注解写成 `X | JSONResponse`（union）时 FastAPI 会尝试推断响应模型并失败。
- **修法**：路由加 `response_model=None`，返回注解写 `Any`（仓库里 `eval.py` 就是这个写法）。

---

## 9. 不变量（改动前先读，破坏了就是事故）

1. **私有笔记与草稿绝不进索引**：可见性规则只有 content-service 一份；投影的删除侧兜底；
   检索侧将来还要加 payload 过滤（但**必须先把字段写进 payload**，否则过滤会静默命中零条）。
2. **Python 只读 `ai_*` 表**：不读写 `user`/`post`（身份只由 Java 经签名头传入）。
3. **ai-service 只拥有 `ai_*` 表**：Mapper 只允许 `com.stellarink.ai.**.mapper`。
4. **`/internal/**` 不配网关路由**：那是一个无鉴权的内部口子，配上路由就等于暴露到公网。
5. **AI 能力写在 Python，Java 只做鉴权/配额/转换/审计**：判据是「换成另一个模型要不要改」。
6. **5xx 文案不透明是刻意的**：前端丢弃 5xx 的 message，**排障必须靠 traceId**；想让人看懂就要用 4xx + 可读 message。
7. **代码由用户提交、AI 不执行 push**。
