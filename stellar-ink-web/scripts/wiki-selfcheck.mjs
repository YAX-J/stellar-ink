/* 知识条目（LLM Wiki，E4-3）前端自检：node 直接跑，用 vite 的 SSR 加载器加载真实 store。
 *
 * 这一层要守的是**「辅助信息失败不得损伤主流程」**：
 * 知识条目是阅读页上的增强，网关抖一下、条目还没建、Python 在跑批 ——
 * 任何一种都不该让读者看到红字提示，更不该挡住正文。
 * 与之相对的另一面同样要守住：**「取不到」不等于「没有条目」**（`failed` 与 `claims` 分开），
 * 混起来会让「服务坏了」看起来像「这篇文章没有知识条目」。
 *
 * 另外验定位逻辑（`utils/wiki.js`）的三个结果：找到 / 找不到 / 没有正文容器。
 * 「找不到」必须是一个**独立结果**而不是「定位失败」：文章在抽取之后被改过时，
 * 界面要说的是「正文里找不到这段文字」，而不是笼统的报错。
 */
import { createServer } from 'vite'
import { createPinia, setActivePinia } from 'pinia'

globalThis.window = { location: { origin: 'http://localhost:5173' } }
const memory = new Map()
globalThis.localStorage = {
  getItem: (key) => (memory.has(key) ? memory.get(key) : null),
  setItem: (key, value) => memory.set(key, String(value)),
  removeItem: (key) => memory.delete(key),
}

const calls = []
/** 下一次响应：`{status, payload}`；默认成功且带两条条目 */
let next = {
  status: 200,
  payload: {
    code: 0,
    data: [
      { id: 1, postId: 7, chunkIndex: 0, text: '每天写五百字可以累积成十八万字',
        quote: '每天写五百字，一年就是十八万字', headingPath: '写作方法', confidence: 0.9 },
      { id: 2, postId: 7, chunkIndex: 1, text: '深夜写作要先清掉干扰',
        quote: '先把手机放到另一个房间', headingPath: '写作方法', confidence: 0.6 },
    ],
  },
}

globalThis.fetch = async (url, init = {}) => {
  const path = new URL(String(url)).pathname
  calls.push(path)
  const reply = (status, payload) => ({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    text: async () => JSON.stringify(payload),
  })
  if (!path.startsWith('/ai/wiki/posts/')) {
    return reply(404, { code: 404, msg: `自检未覆盖的接口：${path}` })
  }
  if (next.status !== 200) {
    return reply(next.status, next.payload)
  }
  return reply(200, next.payload)
}

let failed = 0
function report(ok, label) {
  console.log(`${ok ? '✓' : '✗'} ${label}`)
  if (!ok) failed += 1
}

const server = await createServer({ server: { middlewareMode: true }, appType: 'custom', logLevel: 'error' })
const { useWikiStore } = await server.ssrLoadModule('/src/stores/wiki.js')
const { findEvidenceElement, locateEvidence, normalizeText } = await server.ssrLoadModule('/src/utils/wiki.js')
setActivePinia(createPinia())
const wiki = useWikiStore()

// —— 正常路径：拿到条目 ——
await wiki.load(7)
report(wiki.claims.length === 2, '拿到两条件目')
report(wiki.hasClaims === true, 'hasClaims 判据成立')
report(wiki.failed === false, '成功时不标记失败')
report(calls.at(-1) === '/ai/wiki/posts/7/claims', '请求打到按文章的读取接口')

// —— 切换文章：先清空，避免拿上一篇的条目配这一篇 ——
next = { status: 200, payload: { code: 0, data: [] } }
await wiki.load(8)
report(wiki.claims.length === 0, '新文章没有条目时列表为空')
report(wiki.hasClaims === false, '没有条目 → 界面整块不出现（不留空壳）')
report(wiki.failed === false, '「没有条目」不是「失败」')

// —— 取数失败：静默降级，但 `failed` 要能区分出来 ——
next = { status: 503, payload: { code: 503, msg: '网关连不上 Redis 撤销列表' } }
await wiki.load(9)
report(wiki.claims.length === 0, '失败时列表为空')
report(wiki.failed === true, '失败被记下来（与「没有条目」分开）')
report(wiki.loading === false, '失败后不卡在加载中')

// —— 空 id：不打接口 ——
const before = calls.length
report((await wiki.load(null)).length === 0, '空 id 返回空数组')
report(calls.length === before, '空 id 不发请求')

// —— 定位逻辑 ——
function node(text) {
  return { textContent: text, scrollIntoView() { this.scrolled = true }, classList: { add() {}, remove() {} } }
}
function container(nodes) {
  return { querySelectorAll: () => nodes }
}

const first = node('每天写五百字，一年就是十八万字。')
const second = node('深夜写作时，先把手机放到另一个房间。')
report(
  findEvidenceElement(container([first, second]), '先把手机放到另一个房间') === second,
  '按原文片段找到对应的块',
)
report(
  findEvidenceElement(container([first, second]), '这段文字不在正文里') === null,
  '找不到时返回 null（而不是随便给一个块）',
)
report(
  findEvidenceElement(container([first]), '每天写五百字，\n一年就是十八万字') === first,
  '空白差异不影响定位（模型与原文的换行常不一致）',
)
report(findEvidenceElement(container([first]), '短') === null, '过短的片段不定位（宁可不跳，也不跳错）')
report(normalizeText(' 甲\n乙 ') === '甲乙', '规范化会去掉所有空白')

report(locateEvidence(null, '随便') === 'unavailable', '没有正文容器时如实返回 unavailable')
report(locateEvidence(container([first]), '不存在的一段') === 'missing', '找不到 → missing（不是 located）')
report(locateEvidence(container([first]), '每天写五百字，一年就是十八万字') === 'located', '找到 → located')

await server.close()
console.log(failed ? `\n${failed} 项失败` : '\n知识条目前端自检通过')
process.exit(failed ? 1 : 0)
