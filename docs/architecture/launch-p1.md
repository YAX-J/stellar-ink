# 首发 P1 第一批优化与验收

当前无生产环境，本轮针对本机可验证的前端性能、错误恢复、SEO 基础和持续集成。
P0 Windows 备份支持已提交为 `97bef4f`，服务器侧备份部署继续暂停。

## 已完成

| 维度 | 原问题 | 当前行为 |
|---|---|---|
| 首屏代码 | 助手常驻组件及依赖进入入口 | `AssistantEntry` 仅渲染轻量按钮，首次点击才加载面板；后续收起保留会话 |
| 加载失败 | 路由模块失败只记控制台，可能白屏 | 页面提示重试与刷新；助手模块失败也有可见提示及刷新入口，不自动刷新丢失用户输入 |
| 搜索 | 任一接口失败使整次搜索失败 | 文章、笔记分别保存结果和错误；部分失败标为“部分结果”，保留 traceId，不显示失败类为0条 |
| 并发请求 | 旧请求只丢结果，仍继续执行 | 新搜索／清空会中止旧请求，sequence 防止晚到结果覆盖；翻页失败保留列表 |
| SEO | canonical、分享标签和结构化数据缺少统一清理 | canonical 去参数／锚点、拒绝跨站 URL；公开已发布内容增加 BlogPosting，切页删除旧 JSON-LD |
| 私有页 | 管理页和私有内容没有 noindex | 管理、登录、搜索、404、私有／草稿详情标记 noindex，未加载详情先 noindex；不为私有或草稿生成 JSON-LD |
| 工程化 | 无自动检查入口 | GitHub Actions 分别运行前端 check、Java package、Python lint／类型／pytest，以及部署回归 |

共享入口样式位于 `styles/components.css`，沿用三主题变量；没有新增运行时依赖，没有调整内容写入接口。
代码编辑器仍只由笔记编辑页懒加载，不进入主入口。

## 构建测量

同机、同一依赖锁文件，`npm run check` 产物中 Vite 的十进制 KB：

| 主入口产物 | 优化前 | 优化后 |
|---|---:|---:|
| JavaScript | 164.80 KB | 137.85 KB |
| JavaScript gzip | 63.23 KB | 53.61 KB |
| CSS | 36.61 KB | 19.41 KB |
| CSS gzip | 6.69 KB | 4.37 KB |

入口 JS 减少约16.4%。这只是入口文件体积，不能等同于全部首屏流量、LCP 或 Lighthouse 分数。
`scripts/bundle-budget.mjs` 对实际入口设置150 KiB／gzip60 KiB预算，纳入 `npm run check`；超限应排查依赖归属，不能只调大阈值。
编辑器独立包仍为519.81 KB，有现有500KB构建提示；它不属于本轮入口预算。

## 验证方式与结果

- `npm run check`：原有自检、P1真实 store／head 回归、生产构建和入口预算通过。
- `scripts/p1-selfcheck.mjs`：验证双向部分失败、畸形响应、旧请求中止与晚到隔离、清空防写回、分页失败重试／去重，以及 JSON-LD 安全序列化与切页清理。
- agent-browser 真实本地浏览器，接口使用模拟数据：文章仍可见且笔记失败 traceId 可见；未点击助手前不加载面板模块，点击后正常展开；私有笔记和登录页无旧 JSON-LD；同篇文章只改 query／hash 时标题与 schema 保持正确。
- 同一浏览器验证390px无横向溢出、破晓主题、首次直接打开路由模块失败的提示，以及网络恢复后“刷新打开”成功；助手模块加载失败显示重试和刷新入口。
- Python：ruff、mypy（103模块）通过；pytest 1125通过、1跳过。
- Java：516项测试与完整 Maven reactor `package` 通过；本轮未改 Java 业务接口，不需要为前端模拟测试启动整套中间件。
- 工作流通过 actionlint 检查；尚未推送触发 GitHub 远端执行，不宣称已经得到远端绿灯。

## 使用自动检查

工作流为 `.github/workflows/quality.yml`：PR、main／master／test／feature分支推送和手工触发。
只有仓库读取权限，不部署、不读取生产密钥。Node22使用 `npm ci`；Java17运行整个 Maven reactor；
Python使用 uv frozen锁文件和3.11。是否设置为合并必过项，需要仓库维护者在分支保护规则中配置。

本地前端：`cd stellar-ink-web` 后运行 `npm run check`；后端：`mvn -B -ntp -f stellar-ink-server/pom.xml package`；
AI：在 `stellar-ink-ai` 运行 `uv run ruff check .`、`uv run mypy`、`uv run pytest`。

## 尚未完成的范围

SEO 仍由 SPA 客户端更新，未实现 SSR／预渲染，不能保证不执行 JavaScript 的社交分享爬虫读取正文元信息。
robots 与 noindex 不构成权限保护，私有数据继续由服务端鉴权隔离。
尚未做生产 CDN／真实网络性能与负载测试，也没有真实 Lighthouse 分数；需要部署环境后另行测量。
动态 sitemap／RSS 仍由现有 content-service 提供，本轮没有修改其接口。
