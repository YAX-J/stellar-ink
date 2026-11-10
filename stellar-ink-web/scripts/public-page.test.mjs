/** 真实 HTTP 验证匿名渲染、公开状态变化、错误状态及源码安全。无需数据库或额外依赖。 */
import assert from 'node:assert/strict'
import { test, before, after } from 'node:test'
import { createServer } from 'node:http'
import { once } from 'node:events'
import { readFile } from 'node:fs/promises'
import { createPublicPage } from '../server/public-page.mjs'
import { publicPageHandler } from '../server/http.mjs'
import { takePublicPage, finishPublicPage, showPublicPageError } from '../src/utils/public-page.js'

const template = await readFile(new URL('../index.html', import.meta.url), 'utf8')
const post = { id: 7, status: 1, title: '公开文章 $& </script><img src=x onerror=alert(1)>',
  content: '## 内容标题\n\n正文 **证据**。\n\n[坏链接](javascript:alert(1))\n\n<script>alert(2)</script>\n\n```js\nconst x = "<img>"\n```',
  date: '2026-11-10', wordCount: 33, summary: '这是公开摘要', password: '不能写入HTML' }
const note = { ...post, id: 8, title: '公开技术笔记', visibility: 'PUBLIC' }
const requests = []
let respond
let upstream, frontend, origin

before(async () => {
  upstream = createServer((req, res) => {
    requests.push({ path: req.url, headers: req.headers })
    respond(req, res)
  }).listen(0, '127.0.0.1')
  await once(upstream, 'listening')
  const render = createPublicPage({ gatewayUrl: `http://127.0.0.1:${upstream.address().port}`,
    siteUrl: 'https://example.test', timeoutMs: 1500 })
  frontend = createServer(publicPageHandler({ render, loadTemplate: async () => template })).listen(0, '127.0.0.1')
  await once(frontend, 'listening')
  origin = `http://127.0.0.1:${frontend.address().port}`
})

after(async () => {
  for (const server of [frontend, upstream]) {
    server.closeAllConnections()
    await new Promise((resolve) => server.close(resolve))
  }
})

function reply(data, code = 0, status = 200) {
  requests.length = 0
  respond = (req, res) => {
    res.writeHead(status, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ code, data }))
  }
}
async function page(path, headers = {}, method = 'GET') {
  const response = await fetch(`${origin}${path}`, { method, headers: { Accept: 'text/html', ...headers }, redirect: 'manual' })
  return { response, html: await response.text() }
}
function state(html) {
  const match = html.match(/<script id="stellar-public-state" type="application\/json">([\s\S]*?)<\/script>/)
  return match ? JSON.parse(match[1]) : null
}

test('无需 JavaScript 的响应包含正文、分享卡片、canonical 和可解析 JSON-LD，保留字面量 $&', async () => {
  reply(post)
  const { response, html } = await page('/read/7?tracking=x')
  assert.equal(response.status, 200)
  assert.match(html, /<h1>公开文章 \$&amp;/)
  assert.match(html, /正文 <strong>证据<\/strong>/)
  assert.match(html, /<link rel="canonical" href="https:\/\/example.test\/read\/7">/)
  assert.match(html, /<meta property="og:type" content="article">/)
  assert.equal((html.match(/<title>/g) || []).length, 1)
  assert.equal((html.match(/<meta name="description"/g) || []).length, 1)
  const schema = JSON.parse(html.match(/data-stellar-schema>([\s\S]*?)<\/script>/)[1])
  assert.equal(schema.headline, post.title)
  assert.equal(schema.url, 'https://example.test/read/7')
  assert.equal(state(html).item.content, post.content)
  assert.ok(!html.includes(post.password))
  assert.ok(!html.includes('<img src=x'))
  assert.ok(!html.includes('<script>alert'))
  assert.ok(!html.includes('href="javascript:'))
})

test('HTML 明确禁止浏览器及 CDN 缓存，且不使用请求 Host 构造 canonical', async () => {
  reply(post)
  const { response, html } = await page('/read/7', { Host: 'forged.test', 'X-Forwarded-Host': 'evil.test', 'If-None-Match': 'old-version' })
  assert.equal(response.headers.get('cache-control'), 'no-store')
  assert.equal(response.headers.get('cdn-cache-control'), 'no-store')
  assert.equal(response.headers.get('vary'), 'Accept')
  assert.match(html, /https:\/\/example.test\/read\/7/)
  assert.ok(!html.includes('forged.test') && !html.includes('evil.test'))
})

test('携带管理员 Authorization/Cookie 的页面请求仍只做匿名 GET，不转发查询参数', async () => {
  reply(post)
  await page('/read/7?status=0&url=http://evil.test', { Authorization: 'admin-test-token', Cookie: 'token=test-cookie' })
  assert.equal(requests.length, 1)
  assert.equal(requests[0].path, '/posts/7')
  assert.equal(requests[0].headers.authorization, undefined)
  assert.equal(requests[0].headers.cookie, undefined)
})

test('公开笔记转为私有后下一次请求立即404，正文、状态数据、JSON-LD均不残留', async () => {
  reply(note)
  assert.equal((await page('/note/8')).response.status, 200)
  reply({ ...note, visibility: 'PRIVATE' })
  const { response, html } = await page('/note/8')
  assert.equal(response.status, 404)
  assert.equal(response.headers.get('x-robots-tag'), 'noindex, nofollow')
  assert.ok(!html.includes(note.title) && !html.includes('正文'))
  assert.equal(state(html), null)
  assert.ok(!html.includes('data-stellar-schema'))
})

test('文章撤回或删除后不保留公开内容，也不返回软404', async () => {
  for (const [data, code] of [[{ ...post, status: 0 }, 0], [null, 404], [post, 401], [post, 403]]) {
    reply(data, code)
    const { response, html } = await page('/read/7')
    assert.equal(response.status, 404)
    assert.ok(!html.includes(post.summary))
    assert.equal(state(html), null)
  }
})

test('首页和列表只给公开已发布记录生成可抓取链接，拒绝上游混入草稿/私有/异常ID', async () => {
  for (const [path, item, detailPath] of [['/', post, '/read/7'], ['/archive', post, '/read/7'], ['/notes', note, '/note/8']]) {
    reply({ records: [item, { ...item, id: 9, status: 0, title: '草稿不能泄漏' },
      { ...item, id: 10, visibility: 'PRIVATE', title: '私有不能泄漏', ...(path === '/notes' ? {} : { status: 0 }) },
      { ...item, id: '"onclick=alert(1)', title: '异常ID不能泄漏' }] })
    const { response, html } = await page(path)
    assert.equal(response.status, 200)
    assert.match(html, new RegExp(`href="${detailPath}"`))
    assert.ok(!html.includes('不能泄漏'))
    assert.equal(state(html).records.length, 1)
  }
})

test('私有管理、编辑和搜索页面直接返回noindex，不调用业务接口', async () => {
  reply(post)
  for (const path of ['/account', '/ai-lab', '/write', '/note/edit', '/notes/mine', '/search', '/login']) {
    const { response, html } = await page(path)
    assert.equal(response.status, 200)
    assert.equal(response.headers.get('x-robots-tag'), 'noindex, nofollow')
    assert.equal(state(html), null)
  }
  assert.equal(requests.length, 0)
})

test('不存在或非法路由直接404，不向任意目标发请求；旧路径保持重定向', async () => {
  reply(post)
  for (const path of ['/read/0', '/note/abc', '/read/1/extra', '/unknown', '/read/9999999999999999999']) {
    assert.equal((await page(path)).response.status, 404)
  }
  const { response } = await page('/spectrum')
  assert.equal(response.status, 308)
  assert.equal(response.headers.get('location'), '/archive')
  const series = await page('/series/1')
  assert.equal(series.response.status, 200)
  assert.match(series.html, /<title>系列 · 星笺/)
  assert.equal(requests.length, 0)
})

test('上游5xx、非JSON、畸形契约、超时都返回503/noindex，不伪装成空列表', async () => {
  for (const body of ['invalid json', JSON.stringify({ code: 0, data: { records: null } }), JSON.stringify({ data: [] })]) {
    respond = (req, res) => res.end(body)
    assert.equal((await page('/')).response.status, 503)
  }
  reply(null, 500, 500)
  assert.equal((await page('/read/7')).response.status, 503)
  respond = () => {} // 真实 HTTP 静默上游，必须被总超时中止。
  const { response, html } = await page('/read/7')
  assert.equal(response.status, 503)
  assert.equal(response.headers.get('retry-after'), '30')
  assert.equal(response.headers.get('x-robots-tag'), 'noindex, nofollow')
  assert.ok(!html.includes('data-stellar-schema'))
})

test('HEAD保持GET状态与响应头但不返回正文，拒绝写请求', async () => {
  reply(post)
  const { response, html } = await page('/read/7', {}, 'HEAD')
  assert.equal(response.status, 200)
  assert.equal(response.headers.get('content-type'), 'text/html; charset=utf-8')
  assert.equal(html, '')
  assert.equal((await page('/read/7', {}, 'POST')).response.status, 405)
})

test('禁止带凭据、路径、非HTTP协议的服务配置', () => {
  for (const siteUrl of ['file:///etc/passwd', 'https://user:pass@example.test', 'https://example.test/path']) {
    assert.throws(() => createPublicPage({ gatewayUrl: 'http://127.0.0.1:8080', siteUrl }))
  }
})

test('浏览器只消费匹配路由的初始公开数据，挂载后清理静态阅读层', () => {
  const removed = []
  const hint = { hidden: true }
  const makeScript = () => ({ textContent: JSON.stringify({ path: '/read/7', kind: 'post', item: post }), remove: () => { removed.push('state'); script = null } })
  let script = makeScript()
  const saved = globalThis.document
  globalThis.document = {
    getElementById: (id) => id === 'stellar-public-state' ? script : id === 'stellar-public-error' ? hint : { remove: () => removed.push('page') },
    documentElement: { removeAttribute: (name) => removed.push(name) },
  }
  try {
    assert.equal(takePublicPage('/read/8'), null)
    assert.equal(takePublicPage('/read/7'), null)
    script = makeScript()
    assert.equal(takePublicPage('/read/7').item.title, post.title)
    assert.equal(takePublicPage('/read/7'), null)
    showPublicPageError()
    assert.equal(hint.hidden, false)
    finishPublicPage()
    assert.ok(removed.includes('page') && removed.includes('data-public-pending'))
  } finally { globalThis.document = saved }
})
