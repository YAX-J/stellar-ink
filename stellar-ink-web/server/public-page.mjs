/** 公开 HTML 按请求生成；只访问固定匿名接口，不缓存正文、不转发访客身份。 */
import { escapeHtml as escape, parseMarkdown } from '../src/utils/markdown.js'
import { articleData, clip, SITE_NAME, HOME_TITLE, DEFAULT_DESCRIPTION } from '../src/utils/seo.js'

const NAV = [['/', '此刻'], ['/archive', '星图'], ['/notes', '笔记'], ['/meteor', '流星'], ['/echo', '回声'], ['/links', '星链']]
const SHELL_PAGES = new Map([
  ...NAV, ['/series', '系列'], ['/search', '寻星'], ['/login', '登录'], ['/register', '注册'],
  ['/write', '执笔'], ['/account', '账号'], ['/ai-lab', 'AI 实验室'],
  ['/notes/mine', '我的笔记'], ['/notes/review', '我的笔记'], ['/note/edit', '写笔记'],
])
const PRIVATE_PAGES = new Set(['/search', '/login', '/register', '/write', '/account', '/ai-lab', '/notes/mine', '/notes/review', '/note/edit'])
const json = (value) => JSON.stringify(value).replace(/</g, '\\u003c').replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029')
const isPublic = (item, kind) => item && Number(item.status) === 1
  && (kind !== 'note' || item.visibility === 'PUBLIC')

class PageError extends Error {
  constructor(status) { super(`公开页面读取失败（${status}）`); this.status = status }
}

/** 只把展示字段写入 HTML，避免上游扩展返回值时顺带公开其他字段。 */
function publicItem(item, kind, detail = false) {
  if (!isPublic(item, kind) || !/^[1-9]\d*$/.test(String(item.id))) return null
  const result = { id: item.id, status: 1, title: String(item.title || ''),
    summary: String(item.summary || ''), date: item.date, updatedAt: item.updatedAt,
    wordCount: item.wordCount, userId: item.userId,
    tags: Array.isArray(item.tags) ? item.tags.map(String) : [] }
  if (kind === 'note') Object.assign(result, { visibility: 'PUBLIC', noteType: item.noteType })
  if (detail) result.content = String(item.content || '')
  return result
}

function markdown(source) {
  return parseMarkdown(source).blocks.map((block) => {
    switch (block.type) {
      case 'paragraph': return `<p>${block.html}</p>`
      case 'heading': return `<h2 id="${escape(block.id)}">${block.html}</h2>`
      case 'quote': return `<blockquote>${block.html}</blockquote>`
      case 'note': return `<aside>${block.html}</aside>`
      case 'code': return `<pre><code>${escape(block.text)}</code></pre>`
      case 'list': {
        const tag = block.ordered ? 'ol' : 'ul'
        return `<${tag}>${block.items.map((item) => `<li>${item}</li>`).join('')}</${tag}>`
      }
      case 'hr': return '<hr>'
      default: return ''
    }
  }).join('\n')
}

function itemList(items, kind) {
  return `<ul class="public-list">${items.map((item) => `<li><h2><a href="/${kind === 'note' ? 'note' : 'read'}/${item.id}">${escape(item.title)}</a></h2><p>${escape(clip(item.summary))}</p><time>${escape(item.date || '')}</time></li>`).join('')}</ul>`
}

/** origin 只能来自部署配置，Host、转发头和查询参数均不能决定 canonical 或 API 目标。 */
export function createPublicPage({ gatewayUrl, siteUrl, fetchImpl = fetch, timeoutMs = 5000 }) {
  const gateway = new URL(gatewayUrl)
  const site = new URL(siteUrl)
  for (const url of [gateway, site]) {
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
      throw new Error('页面服务地址必须是无凭据、无路径的 http/https 源地址')
    }
  }

  async function readApi(path, signal) {
    const response = await fetchImpl(new URL(path, gateway), {
      headers: { Accept: 'application/json' }, redirect: 'error', signal,
    })
    if (!response.ok) throw new PageError([401, 403, 404].includes(response.status) ? 404 : 503)
    const body = await response.json()
    if (body?.code !== 0 || !Object.hasOwn(body, 'data')) {
      throw new PageError([401, 403, 404].includes(body?.code) ? 404 : 503)
    }
    return body.data
  }

  return async function publicPage(rawPath, template, { signal: callerSignal } = {}) {
    const url = new URL(rawPath, site)
    const path = url.pathname
    const canonical = `${site.origin}${path}`
    const signal = callerSignal ? AbortSignal.any([callerSignal, AbortSignal.timeout(timeoutMs)]) : AbortSignal.timeout(timeoutMs)
    let status = 200
    let title = path === '/' ? HOME_TITLE : `${SHELL_PAGES.get(path) || (path.startsWith('/series/') ? '系列' : '星轨走失')} · ${SITE_NAME}`
    let description = DEFAULT_DESCRIPTION
    let type = 'website'
    let noindex = PRIVATE_PAGES.has(path)
    let content = ''
    let state = null
    let structuredData = null
    let redirect = null

    try {
      const detail = path.match(/^\/(read|note)\/([1-9]\d{0,17})$/)
      if (detail) {
        const kind = detail[1] === 'note' ? 'note' : 'post'
        const item = publicItem(await readApi(`/${kind === 'post' ? 'posts' : 'notes'}/${detail[2]}`, signal), kind, true)
        if (!item || String(item.id) !== detail[2]) throw new PageError(404)
        title = `${item.title} · ${SITE_NAME}`
        description = clip(item.summary || item.content) || DEFAULT_DESCRIPTION
        type = 'article'
        content = `<article><h1>${escape(item.title)}</h1><time>${escape(item.date || '')}</time><div class="public-prose">${markdown(item.content)}</div></article>`
        state = { path, kind, item }
        structuredData = articleData({ ...item, words: item.wordCount }, kind)
      } else if (['/', '/archive', '/notes'].includes(path)) {
        const kind = path === '/notes' ? 'note' : 'post'
        const page = await readApi(`/${kind === 'post' ? 'posts' : 'notes'}?page=1&size=24`, signal)
        if (!Array.isArray(page?.records)) throw new PageError(503)
        const items = page.records.map((item) => publicItem(item, kind)).filter(Boolean)
        const heading = path === '/' ? '把思绪挂成星图' : SHELL_PAGES.get(path)
        content = `<h1>${heading}</h1><p>${escape(DEFAULT_DESCRIPTION)}</p>${itemList(items, kind)}`
        state = { path, kind: 'list', records: items }
      } else if (path === '/spectrum' || path === '/passport') {
        redirect = path === '/spectrum' ? '/archive' : '/account'
        status = 308
        noindex = true
      } else if (!SHELL_PAGES.has(path) && !/^\/series\/[1-9]\d{0,17}$/.test(path)) {
        throw new PageError(404)
      }
    } catch (error) {
      status = error instanceof PageError ? error.status : 503
      noindex = true
      title = `${status === 404 ? '星轨走失' : '星图暂时无法读取'} · ${SITE_NAME}`
      description = status === 404 ? '这篇内容暂不可公开访问。' : '读取暂时失败，请稍后重试。'
      content = `<h1>${escape(title)}</h1><p>${escape(description)}</p><a href="/">返回此刻</a>`
      state = structuredData = null
    }

    const robots = noindex ? 'noindex, nofollow' : 'index, follow'
    const meta = [
      `<title>${escape(title)}</title>`,
      `<meta name="stellar-site-origin" content="${escape(site.origin)}">`,
      `<meta name="description" content="${escape(description)}">`,
      `<meta name="robots" content="${robots}">`,
      ...Object.entries({ title, description, type, url: canonical, site_name: SITE_NAME })
        .map(([key, value]) => `<meta property="og:${key}" content="${escape(value)}">`),
      '<meta name="twitter:card" content="summary">',
      `<link rel="canonical" href="${escape(canonical)}">`,
      structuredData ? `<script type="application/ld+json" data-stellar-schema>${json({ ...structuredData, url: canonical, mainEntityOfPage: { '@type': 'WebPage', '@id': canonical } })}</script>` : '',
    ].join('\n')
    const nav = `<nav class="public-nav" aria-label="主导航"><a href="/">✦ 星笺</a>${NAV.map(([href, label]) => `<a href="${href}">${label}</a>`).join('')}</nav>`
    const publicHtml = content ? `<div id="stellar-public-page">${nav}<main class="public-page"><p id="stellar-public-error" hidden>交互暂未加载，当前内容仍可阅读。<a href="${escape(path)}">刷新打开</a></p>${content}</main></div>` : ''
    const html = template
      .replace(/<title>[\s\S]*?<\/title>/i, '')
      .replace(/<meta\s+(?:name="(?:description|robots|twitter:card)"|property="og:[^"]+")[^>]*>/gi, '')
      .replace(/<\/head>/i, () => `${meta}\n</head>`)
      .replace('<div id="app"></div>', () => `${publicHtml}<div id="app"></div>${state ? `<script id="stellar-public-state" type="application/json">${json(state)}</script>` : ''}`)
      .replace('<html lang="zh-CN">', `<html lang="zh-CN"${publicHtml ? ' data-public-pending' : ''}>`)
    return { status, html, headers: {
      'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store',
      'CDN-Cache-Control': 'no-store', Vary: 'Accept',
      ...(noindex ? { 'X-Robots-Tag': 'noindex, nofollow' } : {}),
      ...(status === 503 ? { 'Retry-After': '30' } : {}),
      ...(redirect ? { Location: redirect } : {}),
    } }
  }
}
