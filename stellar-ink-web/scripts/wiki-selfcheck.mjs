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

/** 实体接口的响应（**独立于**上面那份：两条路径可以各自成功/失败） */
let nextEntities = {
  status: 200,
  payload: {
    code: 0,
    data: [
      { id: 11, name: '每天写五百字', normalized: '每天写五百字', kind: 'concept',
        mentionCount: 3, postCount: 2,
        mentions: [{ postId: 7, chunkIndex: 0, claimText: '每天写五百字可以累积成十八万字' }],
        relations: [{ entityId: 12, name: '十八万字', weight: 2, evidence: [] }] },
      { id: 12, name: '十八万字', normalized: '十八万字', kind: 'concept',
        mentionCount: 1, postCount: 1,
        mentions: [{ postId: 7, chunkIndex: 0, claimText: '每天写五百字可以累积成十八万字' }],
        relations: [{ entityId: 11, name: '每天写五百字', weight: 2, evidence: [] }] },
    ],
  },
}

/** 主题接口的响应（同样独立） */
let nextTopics = {
  status: 200,
  payload: {
    code: 0,
    data: [
      { id: 21, name: '每天写五百字 · 十八万字', keywords: ['每天写五百字', '十八万字'],
        size: 2, weight: 3, postIds: [7],
        entities: [{ id: 11, name: '每天写五百字', kind: 'concept', mentionCount: 3 },
          { id: 12, name: '十八万字', kind: 'concept', mentionCount: 1 }],
        evidence: [{ postId: 7, chunkIndex: 0, claimText: '每天写五百字可以累积成十八万字' }] },
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
  if (path.endsWith('/entities')) {
    return nextEntities.status === 200
      ? reply(200, nextEntities.payload)
      : reply(nextEntities.status, nextEntities.payload)
  }
  if (path.endsWith('/topics')) {
    return nextTopics.status === 200
      ? reply(200, nextTopics.payload)
      : reply(nextTopics.status, nextTopics.payload)
  }
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

// —— 实体（E4-7）：与条目分开取、分开失败 ——
// ⚠️ 显式恢复条目接口的响应：上面几个用例把它改成了失败/空（自检里的桩是共享状态，
// 不显式恢复的话，「断言」测的就是上一个用例留下的残局）
next = { status: 200, payload: { code: 0, data: [
  { id: 1, postId: 7, chunkIndex: 0, text: '每天写五百字可以累积成十八万字',
    quote: '每天写五百字，一年就是十八万字', headingPath: '写作方法', confidence: 0.9 },
  { id: 2, postId: 7, chunkIndex: 1, text: '深夜写作要先清掉干扰',
    quote: '先把手机放到另一个房间', headingPath: '写作方法', confidence: 0.6 },
] } }
await wiki.load(7)
await wiki.loadEntities(7)
report(wiki.entities.length === 2, '拿到两个实体')
report(wiki.hasEntities === true, 'hasEntities 判据成立')
report(calls.at(-1) === '/ai/wiki/posts/7/entities', '实体请求打到按文章的实体接口')
report(wiki.entities[0].relations[0].name === '十八万字', '共现关系的另一端带名字（读者不看 id）')
report(wiki.entitiesFailed === false, '成功时不标记失败')

// 实体取不到 → 静默降级，且**不影响已经拿到的条目**
nextEntities = { status: 503, payload: { code: 503, msg: 'Python 在跑批' } }
await wiki.loadEntities(7)
report(wiki.entities.length === 0, '实体失败时列表为空')
report(wiki.entitiesFailed === true, '实体失败被单独记下来')
report(wiki.claims.length === 2, '实体失败不影响知识条目（两条路径互不牵连）')
report(wiki.failed === false, '实体失败不会把条目也标成失败')

// 实体为空 ≠ 失败
nextEntities = { status: 200, payload: { code: 0, data: [] } }
await wiki.loadEntities(7)
report(wiki.entities.length === 0 && wiki.entitiesFailed === false, '「没有实体」不是「失败」')

// 切文章先清空：否则会拿上一篇的实体配这一篇
await wiki.loadEntities(8)
report(wiki.entities.length === 0, '换文章时实体先清空')

// 空 id 不发请求
const beforeEntities = calls.length
report((await wiki.loadEntities(null)).length === 0, '空 id 返回空数组（实体）')
report(calls.length === beforeEntities, '空 id 不发实体请求')

// —— 主题（E4-10）：第三套独立状态 ——
// ⚠️ 与实体那段同样的教训：自检里的桩是**共享状态**，断言前必须把要用到的状态显式设好，
// 否则测的是上一个用例留下的残局（这次是 entities 被前面的「空列表」用例清成 0）
nextEntities = { status: 200, payload: { code: 0, data: [
  { id: 11, name: '每天写五百字', normalized: '每天写五百字', kind: 'concept',
    mentionCount: 3, postCount: 2,
    mentions: [{ postId: 7, chunkIndex: 0, claimText: '每天写五百字可以累积成十八万字' }],
    relations: [] },
] } }
await wiki.load(7)
await wiki.loadEntities(7)
report((await wiki.loadTopics(7)).length === 1, '拿到一条主题')
report(wiki.hasTopics === true, 'hasTopics 判据成立')
report(calls.at(-1) === '/ai/wiki/posts/7/topics', '主题请求打到按文章的主题接口')
report(wiki.topics[0].entities.length === 2, '成员一起回（展开成员就有东西看）')
report(wiki.topics[0].evidence.length === 1, '原文一起回（主题页也要能核对）')

nextTopics = { status: 503, payload: { code: 503, msg: 'Python 在跑批' } }
await wiki.loadTopics(7)
report(wiki.topics.length === 0 && wiki.topicsFailed === true, '主题失败单独记下来')
report(wiki.claims.length === 2 && wiki.entities.length === 1, '主题失败不影响条目与实体')

nextTopics = { status: 200, payload: { code: 0, data: [] } }
await wiki.loadTopics(7)
report(wiki.topics.length === 0 && wiki.topicsFailed === false, '「没有主题」不是「失败」')
await wiki.loadTopics(8)
report(wiki.topics.length === 0, '换文章时主题先清空')

const beforeTopics = calls.length
report((await wiki.loadTopics(null)).length === 0, '空 id 返回空数组（主题）')
report(calls.length === beforeTopics, '空 id 不发主题请求')

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
