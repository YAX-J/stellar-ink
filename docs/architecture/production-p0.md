# P0 生产准备与验收

本轮范围：生产初始化、账号级令牌失效、加密异地备份、邮件通知及站外探测。
代码与本地验证不等于生产已经部署。真实 SMTP、备份目标与独立监控机器需由部署者提供。

## 1. 发布顺序

1. 先备份，安排短暂维护窗口，停止入口写入。
2. 对已有库执行 `deploy/sql/24_auth_version.sql`（幂等），不要删除数据卷。新库由 Compose 自动执行。
3. 构建并同时发布四个 Java 服务。旧 JWT 没有 `authVersion`，升级后会要求重新登录。
4. 网关逐次经 Nacos 调用 user-service `/internal/auth/session`，复核数据库中的账号版本与角色。
   内部接口不配置网关路由，不接受客户端 userId 参数，不缓存版本；2 秒内不能完成则返回 503。
   这是首发安全取舍：每个带身份请求增加一次用户服务调用和按主键查库，压测需包含该开销。
5. 全部服务只读 Authorization 请求头，禁止 Cookie／查询参数／表单携带令牌绕过网关复核。
6. 改密、改角色在一条 SQL 中递增版本；普通资料更新不能回写口令、角色或版本。
   普通登出仍仅撤销当前令牌，每次登录包含随机会话标识；已在执行中的请求不会被追溯取消。

代码回滚不能直接退回缺少版本校验的旧网关，否则旧令牌会重新有效；必须保持新校验或在回滚前
同时轮换所有 Java 服务 JWT 密钥，并重新评估改密／降权语义。数据库新增列可以保留。

## 2. 生产管理员与默认凭据

主 Compose 不再挂载 `02_init-data.sql`。它仅由 `docker-compose.demo.yml` 显式加载，
只给全新开发数据库使用；生产禁止使用该覆盖文件。

先填 `.env` 的随机密钥，执行：

```bash
python3 deploy/scripts/production-preflight.py
cd deploy/docker
docker compose up -d --build
cd ../..
python3 deploy/scripts/bootstrap-admin.py
```

初始化命令直接连接数据库，不打开 HTTP、不启动 Nacos 客户端；用户名和密码通过标准输入传入，
不进入进程参数。密码为 16–64 字符且 UTF-8 不超过 72 字节，与登录长度限制一致。存在任何 ADMIN 时拒绝初始化；
MySQL 命名锁串行化并发初始化，不覆盖账号。

历史库如果仍有公开种子口令，先执行：

```bash
python3 deploy/scripts/bootstrap-admin.py --retire-demo-accounts
python3 deploy/scripts/bootstrap-admin.py
```

第一条仅匹配仓库种子 BCrypt 哈希：随机化口令、降为 READER、递增版本，保留文章和用户归属。
已修改口令的账号不受影响。若仍存在其他管理员，第二条会安全拒绝，继续使用已有管理员。
命令不是通用的用户删除、密码找回或账号封禁入口。

Nacos 客户端口令与服务端账号口令必须一致；修改 `.env` 不会修改 Nacos 内已经存在的账号。
先在 Nacos 管理端安全设置独立口令，再更新 `.env`。认证 Token 用 `openssl rand -base64 48`，
身份键与身份值用独立随机值；不要粘贴仓库示例。运行后检查：

```bash
python3 deploy/scripts/production-preflight.py --runtime
```

`--runtime` 适用于 selfcontained MySQL；宿主机数据库部署须用客户端私有配置执行同等检查：
`auth_version` 列存在、有 ADMIN、公开种子哈希账号数量为 0。

## 3. 加密异地备份

Linux 依赖：Python 3.11+、age 1.2.1+、rsync、util-linux（flock）、tar、gzip、SSH。
在独立恢复机器生成 age 身份：

```bash
age-keygen -o recovery-key.txt
age-keygen -y recovery-key.txt > backup-recipients.txt
```

只有公钥 `backup-recipients.txt` 放到生产 `/etc/stellar-ink/`；私钥保管在独立安全位置。
复制 `deploy/scripts/backup.env.example` 到 `/etc/stellar-ink/backup.env`，权限 600，填写异地 rsync 目标。
提前验证 SSH 主机密钥与无交互认证，不关闭主机密钥检查。

备份包含 MySQL、`.env`、本地上传、评测报告、源站证书、生成的邮件配置和 Compose 快照。
暂存目录权限 700，文件权限受 umask 077 约束；整个 tar 用 age 加密后才传到异地。
文件 SHA-256 清单用于恢复完整性校验；成功时间只在加密和异地同步都完成后更新。
旧任务仍调用 `backup.sh`，会转到新实现。默认拒绝未设置异地目标的备份；仅演练可显式设置
`BACKUP_ALLOW_LOCAL_ONLY=1`。默认保留本机 7 天，异地保留策略由目标机独立配置。

MySQL 在宿主机时，设置 `BACKUP_MYSQL_MODE=host`、`MYSQL_DB` 和 `BACKUP_MYSQL_CNF`。
该私有文件按 MySQL `[client]` 格式保存 host/user/password，权限 600；不把口令放命令参数。
COS 头像需另开桶版本控制、保留策略或独立备份；本脚本不会下载 COS 对象。
Qdrant 可由公开语料重建，但需要保留模型配置并预留嵌入额度；Nacos 可调配置应单独导出或从模板重建。

安装定时任务（仓库不在 `/app/stellar-ink` 时先改 unit 两处路径）：

```bash
sudo install -m 644 deploy/systemd/stellar-ink-backup.* /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now stellar-ink-backup.timer
sudo systemctl start stellar-ink-backup.service
sudo journalctl -u stellar-ink-backup.service -n 40
```

每天上海时间 03:00 开始，重启补跑错过的任务。逻辑备份与文件不是跨存储事务快照：
要求严格一致时，在写入维护窗口执行备份；常规在线备份恢复后应检查文件引用和最近内容。

## 4. 恢复演练

只在独立空环境演练，禁止将验证命令指向生产库。

```bash
python3 deploy/scripts/restore-backup.py restore /backup/stellar-日期.tar.age \
  --identity /secure/recovery-key.txt --destination /restore/stellar-日期
```

目的目录必须不存在。命令先完成 age 认证解密、路径安全和逐文件校验，再输出暂存目录。
其中 `.env` 已自动生成新 JWT 密钥，**AI_SECRET_MASTER_KEY 保持原值**；`env.backup` 仅供核对，
绝不可用于恢复启动。校验失败不会生成可部署的最终目录。

人工恢复顺序：

1. 在空部署目录使用对应版本代码，将暂存 `.env` 放到 `deploy/docker/.env`，先只启动 MySQL。
2. 将 `mysql.sql.gz` 解压后通过 MySQL 客户端导入目标库；原数据库名由 `.env` 确定。
   使用 `docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot "$MYSQL_DATABASE"'`
   接收解压 SQL。不要把密码写在命令行。
3. 按需恢复 `data/uploads`、`data/qa-eval-reports`、`data/certs` 和通知配置，核对权限。
4. 确认四个 Java 服务共用新 JWT 密钥，使用 `up -d --force-recreate` 重建，不能只 restart。
5. 所有旧令牌必须失败；重新登录后核对公开文章、私有笔记隔离、头像、评测报告和模型凭据解密。
6. 需要向量索引时重建后再启用。记录备份时间、恢复耗时、内容数量与失败项。

Redis AOF 只保护部分运行故障，不是恢复凭证。撤销列表丢失、回退到旧快照或无法确认完整时，
必须使旧 JWT 全部失效，再开放入口。不能把 Redis 数据丢失描述成“只需重新登录”。

建议首发先约定“最多丢失 24 小时数据、2 小时内恢复”的目标，再用演练证明是否达到；这不是实测结论。

## 5. 邮件告警与站外探测

收件邮箱由用户指定，填在本机 `.env.alerts`；发件账号可以不同。
复制 `.env.alerts.example`，填写 SMTP 地址、发件账号与授权码，权限 600。
163 邮箱填写 `SMTP_SMARTHOST=smtp.163.com:465`，发件、认证账号和收件地址可以相同。
其他邮箱可填写支持 STARTTLS 的端口（通常 587）；地址不加 `ssl://` 前缀，不使用 994。

Alertmanager v0.27.0 已支持 465：先直接建立 TLS，再执行 SMTP。生成器仅在该端口设置
`smtp_require_tls: false`，跳过重复 STARTTLS 升级；它不关闭连接加密，仍验证服务器证书并要求 TLS 1.2+。
其他端口保持 `smtp_require_tls: true`，不得手工改为 false 或跳过证书校验。
源码依据：[v0.27.0 邮件发送实现](https://github.com/prometheus/alertmanager/blob/v0.27.0/notify/email/email.go)。

```bash
python3 deploy/scripts/configure-alerts.py
cd deploy/docker
mkdir -p data/node-exporter
chmod 755 data/node-exporter
docker compose -f docker-compose.yml -f docker-compose.ops.yml run --rm --no-deps \
  --entrypoint /bin/amtool alertmanager check-config /etc/alertmanager/alertmanager.yml
docker compose -f docker-compose.yml -f docker-compose.ops.yml up -d prometheus alertmanager node-exporter
cd ../..
python3 deploy/scripts/test-alert.py
```

已有 Alertmanager 时，重新生成配置后显式重建以加载新文件（生成器通过原子替换写入文件）：

```bash
cd deploy/docker
docker compose -f docker-compose.yml -f docker-compose.ops.yml up -d --no-deps --force-recreate alertmanager
```

配置含 SMTP 授权码，已 gitignore；容器内只读挂载，Alertmanager 用 root 读取 600 文件且移除 capabilities。
Prometheus 通过配置中的 `alerting.alertmanagers` 发送告警，**没有 `--alertmanager.url` 这个启动参数**。
首批覆盖服务不可用、撤销与账号版本校验、磁盘、备份失败／过期／从未成功、邮件投递失败。
SMTP 失效的告警本身也可能发不出，必须同时在看板检查，并定期执行通知演练。

`test-alert.py` 经本机 9093 注入一条三分钟后结束的告警。必须实际收到故障和恢复两封邮件，
HTTP 接收成功不能当作邮件送达。还需在测试环境停服务、模拟备份失败验证真实规则。

本地协议回归不连接真实邮箱：准备 OpenSSL 与同版本 Alertmanager，执行
`python3 deploy/scripts/test-alerts-smtp.py --alertmanager /path/to/alertmanager`。
它在回环地址监听 465，Linux 需有低端口绑定权限，且 465 不能被占用；仅使用模拟账号与临时证书。
已验证故障／恢复通知通过及不受信任证书被拒绝，真实 163 账号送达仍需上面的通知演练。

站外部署位于 `deploy/monitor`，应运行在另一台机器：修改 `prometheus.yml` 中实际站点域名，
在该机器生成自己的私有邮件配置，再执行 `docker compose up -d`。同时检查首页内容与公开 API 的业务 code。
所有探测器和 Prometheus 均不发布公网端口；生产整机停机时由站外监控发信。
站外监控自身仍有单点，需人工定期检查其存活与收件记录。

## 6. 验收记录

- Java：账号版本／旧权限／原子 SQL／初始化拒绝覆盖测试通过，完整 Maven 打包通过。
- 管理员初始化：使用生产 fat jar 在本机隔离 MySQL 库验证空库创建、已有管理员拒绝覆盖、超长密码拒绝，演练库已清理。
- Python：恢复换钥、保留 AI 主密钥、清单篡改、路径穿越、符号链接、SMTP 必填项回归通过。
- 网关：本机真实双设备、单设备登出、改密全设备失效、角色降级、Cookie／查询参数不作为认证来源验证通过，临时账号已清理。
- 恢复：本机两个隔离 MySQL 库完成导出 → age 加密 → 校验解密 → 导入，中文数据一致、JWT 换钥、AI 主密钥保留；篡改密文被拒绝。演练库与临时私钥已清理，不代表生产数据规模下的恢复耗时。
- 前端：`npm run check`（含 Vite 生产构建）通过；编辑器独立懒加载包仍有原有 500KB 提示。
- 告警：同版本 amtool／promtool 配置检查通过；`deploy/docker/observability/tests/production-p0.test.yml` 验证从未备份、失败恢复和26小时过期。仅做 Compose YAML 语法解析，本机没有 Docker，未执行 Linux 容器启动验收。
- 生产待验证：真实 SMTP 发信与恢复通知、异地同步目标、定时任务、COS 保护与站外机器部署。
