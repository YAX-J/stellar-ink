# Cloudflare 侧配置：图片域名走 Worker 代理

生产环境的头像存储是 **腾讯云 COS 香港桶 + Cloudflare Worker 代理**：应用把头像 PUT 到 COS，
浏览器从 `https://img.geminix.work/avatars/...` 读图，CF 边缘负责缓存。

## 为什么需要 Worker（别直接 CNAME 到 COS）

CF 会把请求的 `Host` 原样转给源站，而 **COS 只认自己的端点域名**（`bucket.cos.<region>.myqcloud.com`）
或你在 COS 里另绑的自定义域名 —— 于是在 CF 里直接 CNAME 到 COS 端点会得到 **403/404**。

标准解法是 Origin Rules 的「Host 重写」，但它在部分套餐（含**免费**）不可用；而 Transform Rules
**根本不允许修改 Host 头**（这是 CF 有意的限制，也是 Origin Rules 存在的原因）。
Worker 自己发起 `fetch`、天然带正确的 Host，顺带吃到边缘缓存，免费套餐每天 10 万请求足够。

## 一、腾讯云侧

1. COS 控制台 → 存储桶列表 → **创建存储桶**：
   - 名称 `stellar-ink-<你的APPID>`（必须带 APPID 后缀，全局唯一）
   - 地域 **中国香港 `ap-hongkong`**
   - 访问权限 **公有读私有写**（图片要被匿名 GET；**不要**选公有读写）
   - 建议开启**版本控制**（误删可恢复，也是备份手段之一）
2. 访问管理 CAM → 新建**子用户**（编程访问）→ 只授权这一个桶的读写 → 记下 SecretId / SecretKey。
   **不要用主账号密钥。**
3. CORS 不用配：`<img src>` 加载图片不需要 CORS。

## 二、Cloudflare 侧

1. **DNS**：加一条 `img` CNAME → `<bucket>.cos.ap-hongkong.myqcloud.com`，代理状态**橙云**
2. **Worker**：Workers & Pages → 创建 Worker → 粘贴 `image-proxy-worker.js`
   （把 `ORIGIN` 换成你的桶端点）→ Deploy
3. **路由**：该 Worker → Settings → Triggers → Routes → 添加 `img.geminix.work/*`（Zone 选 `geminix.work`）
4. **Cache Rule**（建议）：Rules → Cache Rules → `Hostname equals img.geminix.work` →
   Cache Eligibility **Eligible for cache**、Edge TTL **1 year**、Browser TTL **1 year**、
   Cache Deception Armor **开**

## 三、应用侧（服务器上）

`.env`（字段说明见 `.env.example`）：

```ini
STORAGE_TYPE=cos
COS_BUCKET=stellar-ink-<APPID>
COS_REGION=ap-hongkong
COS_PUBLIC_BASE=https://img.geminix.work
COS_SECRET_ID=AKIDxxxxxxxx
COS_SECRET_KEY=xxxxxxxx
```

```bash
cd /app/stellar-ink/deploy/docker
docker compose up -d user-service
docker compose logs --tail=40 user-service | grep -i cos   # 期望：COS 存储桶可用
```

## 四、验证

```bash
# 链路是否通（404 但带 cf-cache-status 就说明 CF → Worker → COS 通了，只是文件不存在）
curl -sI https://img.geminix.work/avatars/nonexistent.png | grep -iE 'HTTP/|cf-cache-status'

# 页面上传一次头像 → 返回的 avatar_url 应是 https://img.geminix.work/avatars/...
# 再 curl 那条真实 URL，第二次应看到 cf-cache-status: HIT
```
