/* 首发 P1 回归：真实搜索 store 的部分失败、并发隔离和分页，以及 head 元信息清理。 */
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { createPinia, setActivePinia } from 'pinia'
import { canonicalUrl, articleData } from '../src/utils/seo.js'

globalThis.location = { origin: 'http://localhost:5173', pathname: '/read/1' }
globalThis.window = { location }
globalThis.localStorage = { getItem: () => null }
let respond
globalThis.fetch = (url, init) => respond(new URL(String(url)), init)
const reply = (data, status = 200, traceId = '') => ({
  ok: status === 200, status, headers: { get: () => traceId },
  text: async () => JSON.stringify({ code: status === 200 ? 0 : status, data, traceId }),
})
const page = (id = 1, total = 1, current = 1) => ({ records: [{ id, title: '星笺', status: 1, userId: 0 }], total, current })
const server = await createServer({ server: { middlewareMode: true }, appType: 'custom', logLevel: 'error' })
try {
  const { useSearchStore } = await server.ssrLoadModule('/src/stores/search.js')
  function store() { setActivePinia(createPinia()); return useSearchStore() }
  let search = store()
  respond = async (url) => url.pathname === '/search/notes' ? reply(null, 503, 'note-trace') : reply(page())
  await search.search('星笺')
  assert.equal(search.posts.length, 1)
  assert.equal(search.notes.length, 0)
  assert.ok(search.noteError)
  assert.equal(search.noteTraceId, 'note-trace')
  assert.equal(search.postError, '')
  console.log('通过：笔记接口失败仍保留文章，失败原因与追踪编号单独显示')

  search = store()
  respond = async (url) => url.pathname === '/search' ? reply(null, 503) : reply(page(2))
  await search.search('公开笔记')
  assert.equal(search.notes.length, 1)
  assert.ok(search.postError)
  console.log('通过：文章接口失败仍保留笔记')

  search = store()
  respond = async (url) => url.pathname === '/search/notes' ? reply({ records: 'invalid' }) : reply(page(3))
  await search.search('格式异常')
  assert.equal(search.posts.length, 1)
  assert.ok(search.noteError.includes('格式'))
  assert.equal(search.loading, false)
  console.log('通过：单类结果格式异常不丢弃另一类，也不伪装成零结果')

  search = store()
  const pending = []
  respond = (url, init) => new Promise((resolve) => pending.push({ url, init, resolve }))
  const previous = search.search('旧关键词')
  const current = search.search('新关键词')
  assert.ok(pending.slice(0, 2).every(({ init }) => init.signal.aborted))
  pending.slice(2).forEach(({ resolve }) => resolve(reply(page(20))))
  await current
  pending.slice(0, 2).forEach(({ resolve }) => resolve(reply(page(10))))
  await previous
  assert.equal(search.posts[0].id, 20)
  assert.equal(search.keyword, '新关键词')
  console.log('通过：新搜索中止旧请求，晚到的旧结果不覆盖当前列表')

  pending.length = 0
  const resetting = search.search('即将清空')
  search.reset()
  assert.ok(pending.every(({ init }) => init.signal.aborted))
  pending.forEach(({ resolve }) => resolve(reply(page(30))))
  await resetting
  assert.equal(search.posts.length, 0)
  assert.equal(search.searched, false)
  console.log('通过：清空后未完成请求不能写回结果')

  search = store()
  respond = async (url) => url.pathname === '/search' ? reply(page(1, 24)) : reply({ records: [], total: 0 })
  await search.search('分页')
  respond = async () => reply(null, 503, 'page-trace')
  await search.loadMore('post')
  assert.equal(search.posts[0].id, 1)
  assert.equal(search.postPage, 1)
  assert.equal(search.postTraceId, 'page-trace')
  respond = async () => reply({ records: [{ id: 1 }, { id: 2 }], total: 24, current: 2 })
  await search.loadMore('post')
  assert.deepEqual(search.posts.map((item) => item.id), [1, 2])
  assert.equal(search.postError, '')
  console.log('通过：翻页失败保留已有结果，重试后去重并清除错误')

  assert.equal(canonicalUrl('/read/7?utm_source=x#heading', location.origin, '/'), location.origin + '/read/7')
  assert.equal(canonicalUrl('https://other.example/read/7', location.origin, '/read/1'), location.origin + '/read/1')
  assert.equal(articleData({ title: '草稿', status: 0 }, 'post'), null)
  assert.equal(articleData({ title: '私有', status: 1, visibility: 'PRIVATE' }, 'note'), null)
  const data = articleData({ title: '</script>真实标题', status: 1, date: '2026-11-10', updatedAt: '无效', words: 100 }, 'post', '作者')
  assert.equal(data.headline, '</script>真实标题')
  assert.equal(data.author.name, '作者')
  assert.ok(!data.dateModified)

  const children = []
  function element(tag) {
    return { tag, attrs: {}, textContent: '',
      setAttribute(key, value) { this.attrs[key] = value },
      getAttribute(key) { return this.attrs[key] },
      remove() { children.splice(children.indexOf(this), 1) },
    }
  }
  globalThis.document = {
    title: '星笺', createElement: element,
    head: {
      appendChild(node) { children.push(node) },
      querySelector(selector) {
        const [, tag, attribute, value] = selector.match(/^(\w+)\[([^=\]]+)(?:="([^"]*)")?\]$/) || []
        return children.find((node) => node.tag === tag && Object.hasOwn(node.attrs, attribute)
          && (value === undefined || node.attrs[attribute] === value)) || null
      },
    },
  }
  const { resetPageMeta } = await server.ssrLoadModule('/src/composables/usePageMeta.js')
  resetPageMeta({ title: '文章', structuredData: data })
  const schema = document.head.querySelector('script[data-stellar-schema]')
  assert.equal(JSON.parse(schema.textContent).headline, data.headline)
  assert.ok(!schema.textContent.includes('</script>'))
  resetPageMeta({ noindex: true })
  assert.equal(document.head.querySelector('script[data-stellar-schema]'), null)
  assert.equal(document.head.querySelector('meta[name="robots"]').attrs.content, 'noindex, nofollow')
  resetPageMeta({ title: '此刻' })
  assert.equal(document.head.querySelector('meta[name="robots"]').attrs.content, 'index, follow')
  assert.ok(document.head.querySelector('meta[property="og:title"]').attrs.content.startsWith('此刻'))
  const siteOrigin = element('meta')
  siteOrigin.setAttribute('name', 'stellar-site-origin')
  siteOrigin.setAttribute('content', 'https://canonical.example')
  document.head.appendChild(siteOrigin)
  resetPageMeta({ url: location.origin + '/read/7?tracking=x' })
  assert.equal(document.head.querySelector('link[rel="canonical"]').attrs.href, 'https://canonical.example/read/1')
  assert.equal(document.head.querySelector('meta[property="og:url"]').attrs.content, 'https://canonical.example/read/1')
  console.log('通过：公开文章结构化数据与安全序列化，管理页 noindex，切页清理元信息')
} finally {
  await server.close()
}
