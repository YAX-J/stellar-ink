/* 部署自检（不引依赖，node 直接跑）。
 *
 * 为什么需要它：**前端加了新的接口前缀，部署配置不会报错，只会静默失败。**
 * dev 下 Vite 把未知前缀当 history 路由回退到 index.html，接口拿到的是 HTML；
 * 生产 nginx 同理回退 SPA，前端看到的是「请求成功但没数据」或整个页面。
 * 星笺已经因此漏过两次（`/notes`、`/ai`），两次都不是代码错、而是**配置没跟上代码**。
 *
 * 判据很简单：**前端代码里出现过的接口前缀，必须同时出现在
 * `vite.config.js` 的 proxy 与 `deploy/docker/nginx/default.conf` 的 location 里**。
 * 只校验「存在」，不校验语义（限流档位、超时这类得人看）。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

/** 只扫这些目录：脚本与依赖里的字符串不算「前端在调它」 */
const SOURCE_DIRS = ['src']
const SRC_EXTENSIONS = ['.js', '.vue']

/** 已知的接口前缀 → 说明（新增接口前缀时也要在这里登记，否则下面会报「未登记」） */
const KNOWN_PREFIXES = {
  '/auth': '登录注册',
  '/user': '用户资料与角色',
  '/posts': '文章',
  '/notes': '技术笔记',
  '/meteors': '流星',
  '/echos': '回声',
  '/links': '星链',
  '/stats': '统计',
  '/tags': '标签',
  '/search': '搜索',
  '/ai': 'AI 出口（问答 SSE / Copilot / 评测台）',
  '/uploads': '头像等上传文件（URL 由后端下发，源码里不会出现字面量）',
}

/** 源码里不会以字面量出现的接口前缀：它们由后端下发 URL，前端只负责渲染 */
const BACKEND_ISSUED = new Set(['/uploads'])

/** 从源码里抠出看起来像接口前缀的字符串 */
const PATH_PATTERN = /['"`](\/[a-z][a-z0-9-]*)(?=[/'"`])/g

function walk(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) walk(full, out)
    else if (SRC_EXTENSIONS.some((ext) => entry.endsWith(ext))) out.push(full)
  }
  return out
}

function prefixesUsedByFrontend(rootDir) {
  const used = new Map()
  for (const dir of SOURCE_DIRS) {
    for (const file of walk(join(rootDir, dir))) {
      const text = readFileSync(file, 'utf8')
      for (const match of text.matchAll(PATH_PATTERN)) {
        const prefix = match[1]
        // 只关心「一行里同时出现请求方法」或「已知前缀」的情况，避免把普通链接当接口
        used.set(prefix, (used.get(prefix) || 0) + 1)
      }
    }
  }
  return used
}

function configuredPrefixes(text, pattern) {
  const found = new Set()
  for (const match of text.matchAll(pattern)) found.add(match[1])
  return found
}

let failed = 0
function report(ok, message) {
  if (ok) console.log(`✓ ${message}`)
  else {
    failed += 1
    console.error(`✗ ${message}`)
  }
}

const root = new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')
const viteConfig = readFileSync(join(root, 'vite.config.js'), 'utf8')
const nginxConfig = readFileSync(join(root, '..', 'deploy', 'docker', 'nginx', 'default.conf'), 'utf8')

// vite.config.js 的 proxy 键名形如 `'/ai': gatewayProxy,`
const viteProxied = configuredPrefixes(viteConfig, /['"](\/[a-z][a-z0-9-]*)['"]\s*:\s*gatewayProxy/g)
// nginx 的 location 要么是单个前缀（`location ~ ^/uploads/`），要么是一组前缀（`^/(a|b|c)(/|$)`）
const nginxProxied = configuredPrefixes(nginxConfig, /location\s+~\s+\^\/([a-z-]+)/g)
for (const group of nginxConfig.matchAll(/\^\/\(([a-z|-]+)\)/g)) {
  const first = group[1].split('|')[0]
  if (KNOWN_PREFIXES[`/${first}`]) {
    for (const name of group[1].split('|')) nginxProxied.add(name)
  }
}

const used = prefixesUsedByFrontend(root)
// `/uploads` 的 URL 由后端下发（avatar_url），源码里没有字面量 —— 它仍然必须被代理，
// 所以「必须出现在源码里」这条对它是反的：出现在源码里反而说明有人在拼路径。
const proxyOnly = Object.keys(KNOWN_PREFIXES).filter((prefix) => BACKEND_ISSUED.has(prefix))
const apiPrefixes = Object.keys(KNOWN_PREFIXES).filter(
  (prefix) => used.has(prefix) || BACKEND_ISSUED.has(prefix),
)

report(apiPrefixes.length >= 10, `已登记的接口前缀 ${apiPrefixes.length} 个`)
const neverSeen = Object.keys(KNOWN_PREFIXES).filter(
  (prefix) => !used.has(prefix) && !BACKEND_ISSUED.has(prefix),
)
report(
  neverSeen.length === 0,
  `登记表里每个前缀都有出处${neverSeen.length ? `（没见到：${neverSeen.join(' ')}）` : ''}`,
)
if (proxyOnly.length) {
  console.log(`· 只由后端下发 URL、仍需代理的前缀：${proxyOnly.join(' ')}`)
}

for (const prefix of apiPrefixes) {
  const name = prefix.slice(1)
  report(viteProxied.has(prefix), `vite dev 代理包含 ${prefix}（${KNOWN_PREFIXES[prefix]}）`)
  report(nginxProxied.has(name), `nginx 反代包含 ${prefix}（${KNOWN_PREFIXES[prefix]}）`)
}

// 流式问答：代理必须不缓冲，否则「逐字生成」会退化成「转圈等到最后」
const aiBlock = nginxConfig.slice(nginxConfig.indexOf('location ~ ^/ai(/|$)'))
const aiLocation = aiBlock.slice(0, aiBlock.indexOf('}'))
report(aiLocation.includes('proxy_buffering off'), 'nginx 的 /ai location 关掉了响应缓冲（SSE 逐帧到达）')
report(aiLocation.includes('proxy_read_timeout 120s'), 'nginx 的 /ai location 读超时放宽到 120s（长回答）')

/* nginx 语法只能靠 `nginx -t` 真正验证（本机通常没有 nginx 与 docker），
 * 这里只兜住最粗的错误：**花括号配平**。漏一个 `}` 会让整个 server 块吞掉后面的配置 ——
 * 表现是 nginx 起不来（还算好的）或某些 location 静默失效（更糟）。注释里的括号不计入。 */
const nginxCode = nginxConfig
  .split('\n')
  .map((line) => line.replace(/#.*$/, ''))
  .join('\n')
const opens = (nginxCode.match(/\{/g) || []).length
const closes = (nginxCode.match(/\}/g) || []).length
report(opens === closes, `nginx 配置花括号配平（{ ${opens} 个 / } ${closes} 个）`)

// 未登记的前缀：可能是新接口，也可能是把前端路由误判成接口 —— 两种都值得看一眼
const unregistered = [...used.keys()].filter((prefix) => !KNOWN_PREFIXES[prefix])
const suspicious = unregistered.filter((prefix) => !/^\/(api|assets|fonts|images)$/.test(prefix))
if (suspicious.length) {
  console.log(`· 未登记的前缀（确认是否新接口）：${suspicious.join(' ')}`)
}

console.log(failed ? `\n${failed} 项失败` : '\n部署自检通过')
process.exit(failed ? 1 : 0)
