/* ================= 每页的 SEO 元信息 =================
 * 为什么自己写：项目约定「前端不加依赖」，而这件事本质就是
 * 「找到（或创建）head 里的 meta 标签，然后写一个值」。
 *
 * 分工：
 *   · index.html 提供**站点级缺省值**（description / og:site_name / og:type）；
 *   · router.afterEach 每次导航先调 resetPageMeta() 抹掉上一页的标题与摘要
 *     —— 否则从文章页跳回首页，分享卡片里还挂着上一篇的标题；
 *   · 需要独立摘要的页面（文章、笔记、笔记列表）在自己的 setup 里调 usePageMeta(fn)，
 *     传函数而不是对象：正文是异步取回来的，传对象会永远定在「加载中」那一版。
 *
 * 注意标题仍然由 router.afterEach 兜底写入（`星笺 · 深读` 这种），
 * usePageMeta 拿到具体标题后会覆盖成 `标题 · 星笺`；两者顺序是 afterEach → 组件 setup。
 */
import { watch } from 'vue'

const SITE_NAME = '星笺 · STELLAR INK'
const HOME_TITLE = `${SITE_NAME} — 把思绪挂成星图`
const DEFAULT_DESCRIPTION = '把文章比作星辰的夜间写作与技术笔记博客：随笔、技术笔记与星图。'
const MAX_DESCRIPTION = 120

function ensureMeta(attr, key) {
  let el = document.head.querySelector(`meta[${attr}="${key}"]`)
  if (!el) {
    el = document.createElement('meta')
    el.setAttribute(attr, key)
    document.head.appendChild(el)
  }
  return el
}

function ensureCanonical() {
  let el = document.head.querySelector('link[rel="canonical"]')
  if (!el) {
    el = document.createElement('link')
    el.setAttribute('rel', 'canonical')
    document.head.appendChild(el)
  }
  return el
}

/** 压成一行、去掉 Markdown 标记，再按字数截断：摘要里出现 `##` 与换行会很难看 */
export function clip(text, max = MAX_DESCRIPTION) {
  const plain = String(text || '')
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`([^`]*)`/g, '$1')
    .replace(/!\[[^\]]*]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]+)]\([^)]*\)/g, '$1')
    .replace(/[#>*_~|]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return plain.length > max ? `${plain.slice(0, max)}…` : plain
}

function apply(meta = {}, { setTitle = true } = {}) {
  const title = meta.title ? `${meta.title} · ${SITE_NAME}` : HOME_TITLE
  const description = clip(meta.description || DEFAULT_DESCRIPTION)
  const url = meta.url || `${location.origin}${location.pathname}`

  /* 标题只有页面自己知道时才算「元信息」：路由切换的缺省标题由 router.afterEach 写，
   * 这里不能覆盖 —— 否则 reset 会把 `星笺 · 深读` 抹回首页标题。 */
  if (setTitle) document.title = title
  ensureMeta('name', 'description').setAttribute('content', description)
  ensureMeta('property', 'og:title').setAttribute('content', title)
  ensureMeta('property', 'og:description').setAttribute('content', description)
  ensureMeta('property', 'og:type').setAttribute('content', meta.type || 'website')
  ensureMeta('property', 'og:url').setAttribute('content', url)
  ensureMeta('property', 'og:site_name').setAttribute('content', SITE_NAME)
  ensureMeta('name', 'twitter:card').setAttribute('content', 'summary')
  ensureCanonical().setAttribute('href', url)
}

/** 路由切换时回到站点缺省值；页面随后可以覆盖（标题留给 router.afterEach） */
export function resetPageMeta() {
  if (typeof document === 'undefined') return
  apply({}, { setTitle: false })
}

/**
 * 在页面里声明本页的元信息。
 *
 * @param {() => { title?: string, description?: string, type?: string, url?: string }} source
 *        传函数（可依赖 computed / ref），这样正文异步到达后标题与摘要会自动更新。
 */
export function usePageMeta(source) {
  if (typeof document === 'undefined') return
  const read = typeof source === 'function' ? source : () => source
  watch(read, (meta) => apply(meta || {}), { immediate: true, deep: true })
}
