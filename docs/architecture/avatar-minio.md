# 头像接入对象存储 —— 方案（MinIO / 腾讯云 COS）

> 状态：**方案中的 P0–P2 已实施，落地的是腾讯云 COS**（2026-09）。
> 已实现：`storage/ObjectStorage` 接口 + `LocalObjectStorage` + `CosObjectStorage` +
> `stellar.ink.storage.type` 开关 + `AvatarValidator` 公共校验；已用真实桶跑通 16 项端到端验证
> （上传 / 匿名公开读 / 字节一致 / 缓存头 / 换头像清理旧对象 / 魔数拒绝 / 超限拒绝 / 未登录拒绝 / 删除清理）。
> MinIO 部分（§3 形态 B、§4 的 MinIO 行）保留为**同接口下的另一实现**，需要时按本文新增
> `MinioObjectStorage` 即可，无需改动业务层与前端。
> 尚未做：P3 存量头像迁移脚本、P4 生产 Compose 里的 COS 变量核对与 CDN 域名接入。

## 1. 为什么要动

现状：`user.avatar_url` 存相对路径（`/uploads/avatars/u1_xxx.jpg`），文件落在**运行 user-service 那台机器**的
`UPLOAD_DIR` 目录里。这带来两个硬伤：

| 问题 | 现象 |
|---|---|
| 文件与数据库不在一起 | 本地开发用 `start-all.bat` 连的是远程库（`MYSQL_HOST=124.221.158.32`），头像路径写进远程库，文件却留在本机 → 任何别的实例/机器读该路径都 404，前端按设计降级成底字，表现为「头像没更新成功」 |
| 无法水平扩展 | 多实例部署时，A 实例上传的图 B 实例读不到（与「登录失败计数放进程内」是同一类限制） |

MinIO 解决的就是这两点：图片存成对象，各实例只认 `endpoint + bucket + key`，与运行位置无关。

## 2. 方案总览

```
浏览器 ──POST /user/avatar──▶ 网关 ──▶ user-service ──PutObject──▶ MinIO
   │                                        │
   └────GET 头像图片──────────────────────────┴──▶ MinIO（公开读 bucket，直接取图）
```

- 上传仍走既有 `POST /user/avatar`（multipart）：**服务端收流后转存 MinIO**，前端零改动。
- 读取直接由浏览器访问 MinIO 的公开读 URL（`http://<minio-host>:9000/stellar-ink-avatars/avatars/u1_xxx.jpg`），
  不经过 user-service，省一层转发带宽。
- `user.avatar_url` 语义从「站内相对路径」升级为「**绝对 URL**」，库里同时能容纳历史相对路径（见 §6 兼容）。

### 为什么不让前端直传

| 维度 | 服务端转存（推荐） | 前端直传（预签名 URL） |
|---|---|---|
| 密钥暴露 | 密钥只在服务端 | 仍需服务端签发预签名 URL，只是不经过文件流 |
| 校验 | ImageIO 魔数校验、改名、限额都能做 | 服务端无法在落盘前校验内容（只能在签发时限制大小/类型） |
| 前端改动 | 0 | 需改成「先要签名 → 再 PUT → 再回调确认」三步 |
| 适用规模 | 单站点头像，1MB 以内，完全够 | 大文件、高并发 |

结论：**先做服务端转存**。头像最大 1MB，转存成本可忽略；直传留到将来做文章配图/附件时再评估。

## 3. 部署形态（先定这个，它决定 URL 怎么写）

| 形态 | MinIO 位置 | endpoint | 谁访问得到 | 适用 |
|---|---|---|---|---|
| A. 服务器 Docker | 与应用同机（`deploy/docker` 内新增 minio 服务） | `https://cdn.你的域名`（反代）或 `http://服务器IP:9000` | 服务器与公网浏览器 | **线上推荐** |
| B. 本机 Docker | 开发机（`localhost:9000`） | `http://localhost:9000` | 只有你自己这台机器 | **本地开发** |
| C. 第三方云 | 阿里云 OSS / 腾讯云 COS | 官方 endpoint | 公网 | 不想自己运维时 |

⚠️ **最容易踩的坑（也就是你现在这个问题的翻版）**：MinIO 装在开发机（形态 B）而数据库在远程，
那么库里的图片 URL 写成 `http://localhost:9000/...`，**别人打开你的站点就指向他自己的 localhost，图片必然打不开**。
规则：**endpoint 必须写「浏览器能访问到的地址」，且服务端与浏览器都能访问**。
所以本地开发有两种正确姿势：

1. 本机跑全套（MinIO + user-service + 本机 MySQL），endpoint 用 `http://localhost:9000`；
2. 服务端 MinIO（形态 A），本地服务也连它，endpoint 用服务器地址或 CDN 域名 —— 这样本地开发与线上看到的是同一份图片。

## 4. 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| SDK | `io.minio:minio:8.5.x` | 官方 Java SDK，轻量（核心 + okhttp），只被 user-service 依赖 |
| 版本登记 | 父 pom `dependencyManagement` 加 `<minio.version>` | 符合本仓库「新增依赖先在父 pom 登记」的约定 |
| bucket | `stellar-ink-avatars`，启动时 `bucketExists` 不存在则 `makeBucket` | 幂等，避免手工初始化遗漏 |
| 访问策略 | bucket 设 **public read**（`SetBucketPolicy` 允许 `s3:GetObject`） | 头像本就是公开展示的资源，公开读才能让浏览器直接取图并走缓存 |
| 写权限 | 只有 user-service 持有 AK/SK | 密钥不进前端、不进 Nacos 明文（见 §5） |
| 对象 key | `avatars/u{userId}_{uuid8}.{ext}` | 与现有文件命名一致，便于迁移对账；单层前缀，将来可扩 `posts/`、`notes/` |
| 缓存 | 对象名随机 + 响应用 `Cache-Control: public, max-age=31536000` | 换头像即换 key，天然无缓存残留 |
| 缩略图 | 一期不做 | 现有前端已把图压到 512px（约 40–70KB），列表页够用 |

## 5. 配置与密钥

沿用本仓库既有分层：**非敏感、可调项进 Nacos；凭据只走环境变量**。

```yaml
# nacos 模板（user-service-dev.yaml / user-service-prod.yaml 均可下发）
stellar:
  ink:
    storage:
      type: minio                 # local | minio —— 开关，回滚只需改这一个值
      minio:
        endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
        bucket: stellar-ink-avatars
        public-base-url: ${MINIO_PUBLIC_BASE:}   # 留空则用 endpoint；CDN/反代场景填 https://cdn.xxx
        region: us-east-1
```

| 变量 | 说明 | 放哪 |
|---|---|---|
| `MINIO_ENDPOINT` | 服务端访问 MinIO 的地址 | 环境变量（dev 也可写死 localhost） |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | 凭据 | **只走环境变量**，与 `SA_TOKEN_JWT_SECRET`、`MYSQL_PASSWORD` 同口径；绝不写进 Nacos 或仓库 |
| `MINIO_PUBLIC_BASE` | 浏览器访问用的基址（CDN/反代时与 endpoint 不同） | Nacos 可调 |

生产 `deploy/docker/.env` 增加：`MINIO_ROOT_USER`、`MINIO_ROOT_PASSWORD`、`MINIO_PUBLIC_BASE`，
并在 compose 里给 user-service 注入 `MINIO_ACCESS_KEY/SECRET_KEY`。

## 6. 代码改造清单

### 6.1 抽存储接口（关键一步，决定可回滚性）

新增 `storage/` 包，把「存/删」从 `AvatarStorage` 里抽成接口，两个实现由 `stellar.ink.storage.type` 选择：

```
user-service/src/main/java/com/stellarink/user/
├── storage/
│   ├── ObjectStorage.java            # store(userId, file) -> url；delete(url)
│   ├── LocalObjectStorage.java       # = 现有 AvatarStorage 逻辑（保留，作为回滚路径）
│   └── MinioObjectStorage.java       # 新增
└── config/
    ├── UploadProperties.java         # 既有，加 minio 子配置或新增 StorageProperties
    └── MinioConfig.java              # MinioClient Bean + 启动建桶/设策略
```

- `UserServiceImpl` 的依赖从 `AvatarStorage` 改成 `ObjectStorage`（**注入的是接口**），
  `uploadAvatar` / `deleteAvatar` 的业务流程（先写新、写库成功再删旧、删库失败回收新件）**一行不改**。
- 魔数校验、服务端改名、1MB 双拦这三条安全校验放在接口之外的公共入口，**两个实现共用**，避免 MinIO 版漏掉校验。

### 6.2 MinIO 实现要点

```java
// 伪代码，示意关键点
public String store(Long userId, MultipartFile file) {
    String ext = imageFormat(file);                       // 共用校验：ImageIO 魔数
    String key = "avatars/u" + userId + "_" + uuid8 + "." + ext;
    try (InputStream in = file.getInputStream()) {
        client.putObject(PutObjectArgs.builder()
            .bucket(bucket).object(key).stream(in, file.getSize(), -1)
            .contentType("image/" + ext)
            .cacheControl("public, max-age=31536000")
            .build());
    }
    return publicBase + "/" + bucket + "/" + key;         // 绝对 URL
}

public void delete(String url) {                          // 仍是「尽力而为」，失败只记日志
    String key = keyOf(url);                              // 只解析自己 bucket 的 URL，其余忽略
    client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
}
```

- `delete` 必须容忍两种历史值：MinIO 绝对 URL、旧的 `/uploads/...` 相对路径（后者直接忽略，交给本地清理）。
- 启动建桶：`@PostConstruct` 里 `bucketExists` → `makeBucket` → `setBucketPolicy(public read)`，失败只 WARN 不阻塞启动（
  否则 MinIO 抖动会导致整个 user-service 起不来）。

### 6.3 需要一起改的地方

| 文件 | 改动 |
|---|---|
| `user-service/pom.xml` | 加 `io.minio:minio` |
| `stellar-ink-server/pom.xml` | `dependencyManagement` 登记版本 |
| `user-service/src/main/resources/application-{dev,prod}.yml` | `stellar.ink.storage.*` |
| `user-service/.../nacos-application-dev.yml` | 可调项模板 |
| `UploadWebConfig.java` | `type=minio` 时不再注册 `/uploads/**` 静态映射（保留无害，但会给人「文件还在本地」的错觉，建议加条件装配） |
| 网关路由 `/uploads/**` | **保留**：历史相对路径的头像还要能读；新头像走 MinIO 绝对 URL，不经网关 |
| `deploy/docker/docker-compose.yml` | 新增 `minio` 服务 + 卷 `./data/minio`；user-service 注入凭据与 endpoint |
| `deploy/docker/.env.example` | `MINIO_ROOT_USER/PASSWORD`、`MINIO_PUBLIC_BASE` |
| `deploy/docker/nginx/default.conf` | 可选：反代 `cdn.域名` → `minio:9000`（拿到 https 与同域缓存） |
| `docs/api/README.md`、`AGENTS.md`、`docs/architecture/README.md` | 口径更新 |

### 6.4 前端

**零改动**。`UserAvatar` 组件拿到的 `url` 由 `/uploads/...` 变成 `https://cdn.../...`，
`<img :src>` 与降级逻辑都不关心是相对还是绝对路径。

需要顺手确认的一点：若将来 MinIO 走跨域 CDN，`<img>` 标签本身不受 CORS 限制（只有 canvas 读像素才受限），
现有代码没有对头像做 canvas 二次处理，因此**不需要**给 MinIO 配 CORS。

## 7. 数据库

**不加列**（`avatar_url` 升级为「绝对 URL 或历史相对路径」两种形态并存，VARCHAR(255) 够用）。

如果要能用 SQL 一眼区分新旧，可选加一列（非必需）：

```sql
-- 可选：仅用于迁移对账与灰度统计
ALTER TABLE `user` ADD COLUMN `avatar_storage` VARCHAR(20) NULL COMMENT 'LOCAL / MINIO，用于灰度与回滚对账';
```

## 8. 迁移现有头像

一次性脚本（建议放在 `deploy/scripts/`，本地私有、不入库）：

```bash
# 1) 用 mc 建桶并设公开读
mc alias set ink http://<minio>:9000 <AK> <SK>
mc mb -p ink/stellar-ink-avatars
mc anonymous set download ink/stellar-ink-avatars

# 2) 把本地磁盘上的头像整体推上去（保留文件名 = 保留 key）
mc mirror --overwrite ./data/uploads/avatars ink/stellar-ink-avatars/avatars

# 3) 按文件名回填数据库 URL（文件名 u{userId}_xxx 可反查归属）
#    用 SQL 生成 UPDATE，人工核对后再执行；不要直接跑 UPDATE ... REPLACE 全表
```

注意事项：

- 旧文件与 `user.avatar_url` 是 **1:N 的历史孤儿关系**（换头像会删旧文件，所以正常情况下库里指向的就是磁盘上那一张）；
  若存在对不上的（库里指向已删文件），迁移时按「文件存在才回填，否则置 NULL 回落底字」处理。
- 迁移期间**不删本地文件**，先只读回滚：`storage.type` 改回 `local` 即可退回原状。
- 回滚步骤写进 `deploy/docker/README.md`：改配置 → 重启 user-service → 数据库 `avatar_url` 里遗留的绝对 URL
  在 `local` 模式下会被 `delete` 忽略（不会误删本地文件），但图片会 404 → 需要一并把 URL 换回相对路径。

## 9. 安全核对清单（照现有上传清单扩展）

- [ ] 凭据只走环境变量；仓库、Nacos、镜像层里都不出现 SK
- [ ] bucket 只开 `s3:GetObject` 公开读，**绝不给 `s3:PutObject` 匿名权限**（否则任何人都能往你的桶里塞文件）
- [ ] MinIO 控制台端口（9001）不暴露公网；只暴露 9000，或干脆只经 nginx 反代
- [ ] 上传仍保留：服务端改名（不用客户端文件名）、ImageIO 魔数校验、1MB 双拦
- [ ] `delete` 只允许操作 `avatars/` 前缀下的 key，拒绝解析任意 URL（防越权删他人对象）
- [ ] 生产 `MINIO_ROOT_PASSWORD` 强度与 `SA_TOKEN_JWT_SECRET` 同规格（≥32 位随机）
- [ ] 备份：MinIO 卷纳入备份范围（`/data`），与 MySQL 一起

## 10. 分阶段实施（每步可独立验证/回滚）

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0 | 父 pom 登记版本、user-service 加依赖、`StorageProperties` + `MinioConfig`（建桶+策略） | 启动日志显示 bucket 就绪；`type=local` 时行为完全不变 |
| P1 | `ObjectStorage` 接口 + `LocalObjectStorage`（现有逻辑平移）+ `MinioObjectStorage`，`UserServiceImpl` 改注入接口 | `type=local` 下既有端到端脚本（`verify-avatar.mjs`）全绿 = 重构无回归 |
| P2 | `type=minio` 打通上传/删除/公开读 | 同脚本改 `type=minio` 后全绿；`curl` 直取 MinIO URL 返回 `image/jpeg` |
| P3 | 迁移脚本 + 存量头像回填 + 数据库对账 | 随机抽 10 个用户，头像能显示；库里无指向不存在对象的 URL |
| P4 | Docker Compose 加 minio 服务、nginx 可选反代、文档同步 | `docker compose up -d --build` 后生产环境头像可上传可显示 |

## 11. 工作量与风险

| 项 | 估计 |
|---|---|
| 代码（P0–P2） | 新增 3 个类 + 改 2 个类，约 300 行；接口抽象是主要设计成本 |
| 部署（P4） | compose 加一个服务 + 卷 + 3 个环境变量 |
| 迁移（P3） | 取决于存量文件数；个人站规模通常十几分钟内完成 |
| 主要风险 | ① endpoint 写成浏览器不可达的地址（§3 的坑，本地开发最容易犯）② 忘了给 bucket 设公开读导致全部 404 ③ 生产 MinIO 未做卷持久化 → 容器重建丢图（与现在本地磁盘方案的坑同源） |

## 12. 我的建议

1. **先按 P0–P2 做**，把 `type` 开关和接口抽象留下 —— 这一步的副产品是「本地/对象存储可切换」，
   本身就让项目从「只能单机跑」变成「能多实例部署」。
2. **本地开发本机跑全套**（MinIO + 本机 MySQL + 本机服务），endpoint 用 `localhost:9000`；
   这样开发与线上同构，不会再出现「库里 URL 指向另一台机器」的问题。
3. 线上 endpoint 走 `https://cdn.你的域名` 反代到 `minio:9000`：既拿到 https，又能让 Nginx 做缓存与限速。
4. 若你近期不打算多实例部署，只是想解决本地与远程库错位，那**改成本机全套环境（或把图片存进数据库 BLOB）**
   的收益/成本比更好 —— MinIO 的价值在多实例、大文件与将来的文章配图，现在上的话属于提前投入。
