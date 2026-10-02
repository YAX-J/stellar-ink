/* 作者记忆（M9-4）前端自检：node 直接跑，用 vite 的 SSR 加载器加载真实 store。
 *
 * 这一层与 Wiki 自检**刻意相反**：Wiki 的失败要静默（那是阅读页的增强），
 * 而记忆的失败**必须说出来** —— 用户主动来看「模型记住了我什么」，
 * 一无所获时他必须能分清「还没有记忆」与「服务坏了」。
 * 混起来的后果是：他以为记忆丢了，或者以为自己从没记过东西。
 *
 * 另外三条口径：
 * 1. **待确认与已生效分开**：一个问「要不要记住」，一个问「要不要关掉/删掉」；
 * 2. **冲突要单列**：确认时冲突没被写进去，界面若只显示「已保存 N 条」，
 *    用户会以为候选全被接受了；
 * 3. **写成功后就地更新、刷新失败不算写失败**（AGENTS §4 那条口径）；
 *    写**失败**则必须回滚本地，否则界面在说谎。
 */
import { createServer } from 'vite'
import { createPinia, setActivePinia } from 'pinia'

globalThis.window = { location: { origin: 'http://localhost:5173' } }
const storage = new Map()
globalThis.localStorage = {
  getItem: (key) => (storage.has(key) ? storage.get(key) : null),
  setItem: (key, value) => storage.set(key, String(value)),
  removeItem: (key) => storage.delete(key),
}

const calls = []
/** 下一次 `/ai/memory/list` 的响应（独立变量，避免上一个用例的残留影响下一个 —— 踩过） */
let nextList = { status: 200, payload: { code: 0, data: [] } }
let nextPending = { status: 200, payload: { code: 0, data: [] } }
let nextWrite = { status: 200, payload: { code: 0, data: {} } }
let nextProfile = { status: 200, payload: { code: 0, data: null } }

const reply = (status, payload) => ({
  ok: status >= 200 && status < 300,
  status,
  headers: { get: () => null },
  text: async () => JSON.stringify(payload),
})

globalThis.fetch = async (url, init = {}) => {
  const raw = String(url)
  const path = raw.replace(/^https?:\/\/[^/]+/, '')
  const method = (init.method || 'GET').toUpperCase()
  calls.push(`${method} ${path}`)

  if (path.startsWith('/ai/memory/list')) {
    // 待确认那份走 `?status=pending`：两条列表必须真的分开取
    if (path.includes('status=pending')) {
      return nextPending.status === 200
        ? reply(200, nextPending.payload)
        : reply(nextPending.status, nextPending.payload)
    }
    return nextList.status === 200 ? reply(200, nextList.payload) : reply(nextList.status, nextList.payload)
  }
  if (path.startsWith('/ai/memory/style-profile')) {
    return nextProfile.status === 200
      ? reply(200, nextProfile.payload)
      : reply(nextProfile.status, nextProfile.payload)
  }
  if (path.startsWith('/ai/memory/')) {
    return nextWrite.status === 200 ? reply(200, nextWrite.payload) : reply(nextWrite.status, nextWrite.payload)
  }
  return reply(404, { code: 404, msg: `自检未覆盖的接口：${path}` })
}

let failed = 0
function report(ok, label) {
  console.log(`${ok ? '✓' : '✗'} ${label}`)
  if (!ok) failed += 1
}

const server = await createServer({ server: { middlewareMode: true }, appType: 'custom', logLevel: 'error' })
const { useMemoryStore, describeExtract, kindLabel, statusLabel, evidenceLabel } = await server.ssrLoadModule(
  '/src/stores/memory.js',
)
setActivePinia(createPinia())
const memory = useMemoryStore()

const active = { id: 1, memoryType: 'preference', content: '作者偏好短句', confidence: 0.9,
  source: 'user_confirmed', status: 'active',
  evidence: [{ kind: 'quote', ref: '句子短一点读起来才顺' }] }
const disabled = { id: 2, memoryType: 'fact', content: '写过 29 篇', confidence: 0.8,
  source: 'model_suggested', status: 'disabled', evidence: [] }
const pendingItem = { id: 3, memoryType: 'decision', content: '不再写第二季', confidence: 0.6,
  source: 'model_suggested', status: 'pending', evidence: [{ kind: 'quote', ref: '我决定不再写第二季了' }] }

// —— 正常路径：两份列表分开取、分开存 ——
nextList = { status: 200, payload: { code: 0, data: [active, disabled] } }
nextPending = { status: 200, payload: { code: 0, data: [pendingItem] } }
await memory.load()
report(memory.memories.length === 2, '拿到两条已生效/已禁用记忆')
report(memory.pending.length === 1, '待确认是单独一份（不是混在同一个列表里）')
report(memory.hasMemories && memory.hasPending, '两个判据都成立')
report(memory.failed === false, '成功时不标记失败')
report(
  calls.some((item) => item.includes('status=pending')),
  '待确认走 status=pending 查询，而不是在前端过滤（前端过滤会漏掉分页/上限）',
)

// —— 取数失败：**必须说出来**（与 Wiki 的静默降级相反）——
nextList = { status: 503, payload: { code: 503, msg: '网关连不上' } }
nextPending = { status: 503, payload: { code: 503, msg: '网关连不上' } }
await memory.load()
report(memory.failed === true, '取数失败被记下来（界面据此说「服务坏了」，而不是「你没有记忆」）')
report(memory.memories.length === 0 && memory.pending.length === 0, '失败时两份列表都清空，不留旧数据')
report(memory.loading === false, '失败后不卡在加载中')

// —— 抽取：丢弃计数要能解释「为什么只记了这么点」——
nextWrite = {
  status: 200,
  payload: { code: 0, data: { candidates: [pendingItem], proposed: 3, kept: 1,
    dropped: { noEvidence: 2 }, notes: ['2 条候选的出处找不到'], usageModel: 'stub' } },
}
const extracted = await memory.extract('作者：我喜欢短句')
report(extracted?.kept === 1, '抽取结果按契约返回')
report(memory.notice.includes('noEvidence 2 条'), `抽取提示要写出丢弃原因：${memory.notice}`)
report(describeExtract({ proposed: 1, kept: 1, dropped: {} }) === '提出 1 条，留下 1 条待确认。',
  '没有丢弃时提示简洁')

// —— 确认：新增与合并分开说，冲突单列 ——
nextWrite = {
  status: 200,
  payload: { code: 0, data: { added: 1, merged: 1,
    conflicts: [{ memoryId: 9, existingContent: '偏好写长文', candidateContent: '偏好写短文',
      candidateId: 12 }],
    notes: ['1 条候选与已有记忆冲突'] } },
}
await memory.confirm([3])
report(memory.hasConflicts === true, '冲突被单独记下来（它**没有**被写进去）')
report(memory.conflicts[0].existingContent === '偏好写长文', '冲突带上两地正文，界面才能对照着让人选')
report(memory.notice.includes('需要你选一个'), `提示必须说清冲突这件事：${memory.notice}`)

// —— 禁用：先就地把本地那条改掉（写成功即生效）——
memory.conflicts = []
nextList = { status: 200, payload: { code: 0, data: [active, disabled] } }
nextPending = { status: 200, payload: { code: 0, data: [] } }
await memory.load()
nextWrite = { status: 200, payload: { code: 0, data: active } }
const toggled = await memory.setStatus(1, 'disabled')
report(toggled === true, '禁用成功')
report(calls.some((item) => item.startsWith('PUT /ai/memory/1/status')), '禁用打到状态接口')

// —— 禁用失败：本地要**回滚**，否则界面在说谎 ——
nextList = { status: 200, payload: { code: 0, data: [active, disabled] } }
await memory.load()
nextWrite = { status: 503, payload: { code: 503, msg: '网关连不上' } }
await memory.setStatus(1, 'disabled')
report(memory.memories.find((item) => item.id === 1)?.status === 'active',
  '写失败时回滚本地状态（界面不能显示成已禁用）')

// —— 删除成功：本地立刻少一条 ——
nextList = { status: 200, payload: { code: 0, data: [active, disabled] } }
await memory.load()
nextWrite = { status: 200, payload: { code: 0, data: 1 } }
nextList = { status: 200, payload: { code: 0, data: [disabled] } }
await memory.remove(1)
report(memory.memories.length === 1, '删除后列表里少一条（写成功即生效）')
report(memory.notice.includes('派生画像'), '删除提示要说清连带清理了什么')

// —— 删除失败：那条要回到列表里 ——
nextList = { status: 200, payload: { code: 0, data: [active, disabled] } }
await memory.load()
nextWrite = { status: 503, payload: { code: 503, msg: '网关连不上' } }
await memory.remove(1)
report(memory.memories.length === 2, '删除失败时那条回到列表（不能凭空消失）')
report(memory.notice.includes('删除失败'), `失败要说出来：${memory.notice}`)

// —— 全部清除：连冲突清单一起清掉 ——
memory.conflicts = [{ memoryId: 9 }]
nextWrite = { status: 200, payload: { code: 0, data: { removed: 2 } } }
nextList = { status: 200, payload: { code: 0, data: [] } }
await memory.clearAll()
report(memory.memories.length === 0, '全部清除后列表为空')
report(memory.conflicts.length === 0, '冲突清单也清掉（它们指向的记忆已经没了）')
report(memory.notice.includes('派生画像'), '清除提示要说明派生画像也清了')

// —— 风格画像：null 与失败是两件事 ——
nextProfile = { status: 200, payload: { code: 0, data: null } }
await memory.loadStyleProfile()
report(memory.styleProfile === null && memory.styleFailed === false,
  '「还没生成过」不是失败（可以点刷新）')
nextProfile = { status: 503, payload: { code: 503, msg: '网关连不上' } }
await memory.loadStyleProfile()
report(memory.styleFailed === true, '取不到画像要标记失败（与「还没生成过」分开）')

// —— 标签与证据说明 ——
report(kindLabel('preference') === '偏好' && kindLabel('unknown') === 'unknown', '类型标签有兜底')
report(statusLabel('pending') === '待确认' && statusLabel('deleted') === '已删除', '四档状态各有标签')
report(evidenceLabel({ kind: 'quote', ref: '句子短一点' }).startsWith('原文：'), '原文证据标明是原文')
report(evidenceLabel({ kind: 'user', ref: '你确认过' }).startsWith('你确认过：'),
  '用户确认的证据**不能**说成原文（前者可核对，后者只能算「你说过」）')

await server.close()
console.log(failed === 0 ? '\n记忆自检全部通过' : `\n记忆自检失败 ${failed} 条`)
if (failed > 0) process.exit(1)
