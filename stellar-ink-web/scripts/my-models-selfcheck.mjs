/* 「我的 AI 模型」（M12）前端自检：node 直接跑，用 vite 的 SSR 加载器加载真实 store。
 *
 * 这份用例盯的是四件会被误读的事：
 *
 * 1. **只列三个生成角色**：embedding/rerank 不按用户隔离（向量索引只有一份）。
 *    界面若把它们也列出来，用户配了不会生效 —— 那是最糟的交互；
 * 2. **「没配」与「用全局」分开**：没配 = 回落全局，界面要说清现在在用谁的模型；
 * 3. **取数失败必须说出来**：与阅读页知识条目刻意相反 ——
 *    显示成「你还没配」会让用户以为配置丢了；
 * 4. **写成功就地生效、写失败不改本地、删除失败那条要回来**。
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
let nextList = { status: 200, payload: { code: 0, data: [] } }
let nextWrite = { status: 200, payload: { code: 0, data: {} } }
let nextDelete = { status: 200, payload: { code: 0, data: true } }

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

  if (method === 'GET' && path.startsWith('/ai/me/providers')) {
    return reply(nextList.status, nextList.payload)
  }
  if (method === 'DELETE' && path.startsWith('/ai/me/providers/')) {
    return reply(nextDelete.status, nextDelete.payload)
  }
  if (path.startsWith('/ai/me/providers')) {
    return reply(nextWrite.status, nextWrite.payload)
  }
  return reply(404, { code: 404, msg: `自检未覆盖的接口：${path}` })
}

let failed = 0
function report(ok, label) {
  console.log(`${ok ? '✓' : '✗'} ${label}`)
  if (!ok) failed += 1
}

const server = await createServer({
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'error',
})
const { useMyModelsStore, MY_MODEL_ROLES, GLOBAL_ONLY_NOTE } = await server.ssrLoadModule(
  '/src/stores/myModels.js',
)
setActivePinia(createPinia())
const mine = useMyModelsStore()

const chatConfig = {
  id: 3,
  role: 'chat',
  displayName: '我自己的 qwen-plus',
  provider: 'openai_compatible',
  baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  model: 'qwen-plus',
  apiKeyConfigured: true,
  apiKeyMask: 'sk-…9f3a',
  enabled: true,
  lastCheckStatus: 'unknown',
}

// —— 角色清单：只放开三个生成角色 ——
report(MY_MODEL_ROLES.length === 3, '只列三个角色')
report(
  MY_MODEL_ROLES.map((item) => item.key).join(',') === 'chat,fast,reasoning',
  '恰好是 chat / fast / reasoning（与 Java isUserScoped、Python USER_SCOPED_ROLES 一致）',
)
report(
  !MY_MODEL_ROLES.some((item) => item.key === 'embedding' || item.key === 'rerank'),
  '**不列** embedding / rerank：配了也不会生效（向量索引只有一份）',
)
report(GLOBAL_ONLY_NOTE.includes('只有一份'), '说明里讲清了为什么这两个角色不在这里')

// —— 正常路径：只有 chat 是自己配的，另外两个回落到全局 ——
nextList = { status: 200, payload: { code: 0, data: [chatConfig] } }
await mine.load()
report(mine.personalCount === 1, '拿到我自己配的那一条')
report(mine.rows.length === 3, '三个角色都出，便于显示「哪个在用全局」')
report(mine.rows.find((item) => item.key === 'chat').personal === true, 'chat 标记为「我自己配的」')
report(
  mine.rows.filter((item) => !item.personal).length === 2,
  '另外两个没配 → 界面据此说「用全局配置」而不是留空',
)
report(mine.failed === false, '成功时不标记失败')

// —— 取数失败：必须说出来（与 Wiki 的静默降级相反）——
nextList = { status: 503, payload: { code: 503, msg: '网关连不上' } }
await mine.load()
report(mine.failed === true, '取数失败被记下来（界面说「服务坏了」，而不是「你还没配」）')
report(mine.personalCount === 0, '失败时清空列表，不展示可能已过期的端点与掩码')

// —— 保存成功：就地生效，不再多发一次请求 ——
nextWrite = { status: 200, payload: { code: 0, data: chatConfig } }
calls.length = 0
await mine.save('chat', {
  displayName: '我自己的 qwen-plus',
  baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  model: 'qwen-plus',
  apiKey: 'sk-live-x',
})
report(mine.personalCount === 1, '保存后就地生效（不等下一次取数）')
report(
  calls.filter((item) => item.startsWith('GET')).length === 0,
  '保存成功后没有再打一次列表接口（刷新是 best-effort，写成功不该被刷新拖累）',
)
report(mine.failed === false, '写成功清掉失败标记（服务刚才是好的）')

// —— 保存失败：**不改本地**并留下面板内的错误 ——
nextWrite = { status: 400, payload: { code: 400, msg: '「127.0.0.1」是内网或本机地址，个人配置只能填公网地址' } }
const before = { ...mine.items }
let threw = false
try {
  await mine.save('fast', { displayName: 'x', baseUrl: 'http://127.0.0.1:8000/v1', model: 'local', apiKey: 'sk' })
} catch {
  threw = true
}
report(threw, '保存失败要抛出去（调用方据此不关表单）')
report(
  JSON.stringify(mine.items) === JSON.stringify(before),
  '保存失败**不改本地**：乐观更新会让人以为存上了',
)
report(mine.formError.includes('公网地址'), '后端那句可操作提示原样留在表单上方（不只靠会消失的 toast）')
report(mine.savingRole === '', '失败后清掉「保存中」，按钮恢复可点')

// —— 删除失败：那条要回来（不能凭空消失）——
nextList = { status: 200, payload: { code: 0, data: [chatConfig] } }
await mine.load()
nextDelete = { status: 403, payload: { code: 403, msg: '没有权限' } }
let deleteThrew = false
try {
  await mine.remove('chat')
} catch {
  deleteThrew = true
}
report(deleteThrew || mine.personalCount === 1, '删除失败时本地那条仍在（界面不说谎）')

// —— 删除成功：该角色回到「用全局」 ——
nextDelete = { status: 200, payload: { code: 0, data: true } }
await mine.remove('chat')
report(mine.personalCount === 0, '删除成功后该角色回落全局')
report(
  mine.rows.every((item) => item.personal === false),
  '三个角色都变回「用全局配置」',
)

// —— 自检走的是「我这份」的端点 ——
nextWrite = { status: 200, payload: { code: 0, data: { ok: true, scope: 'tcp_only', latencyMs: 12, message: 'ok' } } }
calls.length = 0
await mine.check('chat')
report(
  calls.some((item) => item === 'POST /ai/me/providers/chat/check'),
  '自检打的是 /ai/me/... （我这份），不是管理端那条',
)
report(mine.checkResults.chat.ok === true, '自检结论进了状态，面板可显示')

if (failed > 0) {
  console.error(`\n我的模型自检失败 ${failed} 条`)
  process.exit(1)
}
console.log('\n我的模型自检全部通过')
await server.close()
