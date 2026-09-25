/* 「保存按钮是假的」回归自检（node 直接跑，用 vite 的 SSR 加载器跑真实 store）。
 *
 * 为什么需要它：用户报过一次「添加模型的保存按钮是假的」。后端探针证明 POST/GET/DELETE
 * 全是 200，问题出在 store —— `saveModel` 在 POST 成功后又 `await this.loadModels()`，
 * 而模型库那条 GET 依赖 Redis 撤销列表（网关 fail-closed，抖动时会 503）。
 * 于是一次刷新失败就把**已经落库**的保存显示成「保存失败」：没有成功提示、表单不关、
 * 列表里一条都没有；用户再点一次还会看到「已经有同名模型」—— 看起来就是按钮没生效。
 *
 * 判据：**写成功之后，随后的刷新失败绝不能把这次写变成「失败」**。
 * 这里把刷新接口全打成 503，断言 saveModel / removeModel / bindModel 都不抛错、
 * 且就地生效（列表 / 绑定关系立刻可见）。
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

/** 刷新接口是否应当失败（模拟网关 503） */
let failRefresh = false
const calls = []

const savedModel = {
  id: 7,
  displayName: '主力对话模型',
  provider: 'openai_compatible',
  baseUrl: 'https://api.example.com/v1',
  model: 'example-chat',
  capabilities: ['chat'],
  enabled: true,
  boundRoles: [],
}

globalThis.fetch = async (url, init = {}) => {
  const path = new URL(String(url)).pathname
  const method = (init.method || 'GET').toUpperCase()
  calls.push(`${method} ${path}`)
  const reply = (status, body) => ({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    text: async () => JSON.stringify(body),
  })
  const unavailable = () => reply(503, {
    code: 503,
    msg: '会话校验服务不可用：网关连不上 Redis 撤销列表',
  })

  if (method === 'POST' && path === '/ai/admin/models') {
    return reply(200, { code: 0, data: savedModel })
  }
  if (method === 'POST' && path.endsWith('/check')) {
    return reply(200, { code: 0, data: { ok: true, scope: 'tcp', latencyMs: 3, message: '端点可达' } })
  }
  if (method === 'DELETE' && path.startsWith('/ai/admin/models/')) {
    return reply(200, { code: 0, data: true })
  }
  if (method === 'PUT' && path.startsWith('/ai/admin/providers/')) {
    return reply(200, {
      code: 0,
      data: { ...savedModel, role: path.split('/')[4], modelId: savedModel.id },
    })
  }
  if (path === '/ai/admin/models') {
    if (failRefresh) return unavailable()
    return reply(200, { code: 0, data: calls.some((call) => call.startsWith('POST')) ? [savedModel] : [] })
  }
  if (path === '/ai/admin/providers') {
    if (failRefresh) return unavailable()
    return reply(200, { code: 0, data: [] })
  }
  return reply(404, { code: 404, msg: `自检未覆盖的接口：${method} ${path}` })
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
const { useAiStore } = await server.ssrLoadModule('/src/stores/ai.js')
setActivePinia(createPinia())
const ai = useAiStore()

await ai.loadModels()
report(ai.models.length === 0, '模型库初读为空')

// —— 关键场景：POST 成功、随后的刷新全 503 ——
failRefresh = true
let thrown = null
try {
  await ai.saveModel({
    displayName: savedModel.displayName,
    baseUrl: savedModel.baseUrl,
    model: savedModel.model,
    capabilities: ['chat'],
    apiKey: 'sk-selfcheck',
  })
} catch (error) {
  thrown = error
}
report(thrown === null, '刷新 503 时保存不抛错（不会把已保存显示成保存失败）')
report(ai.models.some((item) => item.id === savedModel.id), '保存成功后列表里立刻有这条模型')
report(!!ai.modelsError, '刷新失败的原因仍被记录（modelsError 非空）')
report(ai.savingModel === false, '保存按钮不会卡在「保存中」')

// —— 能力过滤：决定它出现在哪些角色的下拉框里 ——
report(
  ai.list.find((item) => item.key === 'chat').options.some((item) => item.id === savedModel.id),
  'chat 角色的下拉框能选到它',
)
report(
  !ai.list.find((item) => item.key === 'embedding').options.some((item) => item.id === savedModel.id),
  'embedding 角色看不到纯对话模型（能力过滤生效）',
)

// —— 自检：结果就地可见 ——
const checked = await ai.checkModel(savedModel.id)
report(checked.ok === true, '自检返回端点可达')
report(ai.models[0].lastCheckStatus === 'ok', '自检结果就地写进列表（不必等刷新）')

// —— 删除：同样先就地生效 ——
thrown = null
try {
  await ai.removeModel(savedModel.id, false)
} catch (error) {
  thrown = error
}
report(thrown === null, '刷新 503 时删除不抛错')
report(!ai.models.some((item) => item.id === savedModel.id), '删除后列表里立刻没有这条模型')

// —— 绑定：写成功即生效 ——
ai.providers = {}
ai.models = [{ ...savedModel, boundRoles: [] }]
thrown = null
try {
  await ai.bindModel('chat', savedModel.id)
} catch (error) {
  thrown = error
}
report(thrown === null, '刷新 503 时应用模型不抛错')
report(!!ai.providers.chat, '应用后角色配置就地更新')
report((ai.models[0].boundRoles || []).includes('chat'), '应用后「正被谁使用」就地更新')

await server.close()
console.log(failed ? `\n${failed} 项失败` : '\n模型库前端自检通过')
process.exit(failed ? 1 : 0)
