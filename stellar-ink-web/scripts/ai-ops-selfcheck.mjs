/* AI 观测面（第一优先，M12-S4）前端自检。
 *
 * 这两块面板最容易犯的错不是「算错」，而是**说出它证明不了的话**：
 *
 * 1. 把「面板里配了」说成「运行中」—— 那要等一次真实调用才能证明；
 * 2. 把「端点 TCP 可达」说成「配置正确」—— 模型名写错、密钥无效都测不出来；
 * 3. 把「成本 0」说成「不花钱」—— 没单价的调用按 0 计，这个数不显示出来就是误导；
 * 4. 把「没跑过」算成「失败率 0%」—— 0/0 不是 0。
 *
 * 另外两条工程口径：探活失败**不该**让配置那块一起失败（配置读到了就有价值），
 * 以及取数失败要能重试且不留旧数据。
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
let nextProviders = { status: 200, payload: { code: 0, data: [] } }
let nextHealth = { status: 200, payload: { code: 0, data: { available: true, reason: '' } } }
let nextUsage = { status: 200, payload: { code: 0, data: null } }
let nextAudit = { status: 200, payload: { code: 0, data: null } }
let nextTrace = { status: 200, payload: { code: 0, data: null } }

const reply = (status, payload) => ({
  ok: status >= 200 && status < 300,
  status,
  headers: { get: () => null },
  text: async () => JSON.stringify(payload),
})

globalThis.fetch = async (url, init = {}) => {
  const path = String(url).replace(/^https?:\/\/[^/]+/, '')
  calls.push(`${(init.method || 'GET').toUpperCase()} ${path}`)
  if (path.startsWith('/ai/admin/providers')) return reply(nextProviders.status, nextProviders.payload)
  if (path.startsWith('/ai/health')) return reply(nextHealth.status, nextHealth.payload)
  if (path.startsWith('/ai/admin/usage/summary')) return reply(nextUsage.status, nextUsage.payload)
  if (path.startsWith('/ai/admin/retrieval-audit/summary')) return reply(nextAudit.status, nextAudit.payload)
  if (path.startsWith('/ai/admin/trace/')) return reply(nextTrace.status, nextTrace.payload)
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
const { useAiOpsStore, AI_ROLE_ORDER, RUNTIME_CAVEAT, AUDIT_HINT } = await server.ssrLoadModule(
  '/src/stores/aiOps.js',
)
setActivePinia(createPinia())
const ops = useAiOpsStore()

const configured = (role, extra = {}) => ({
  role,
  displayName: `测试 ${role}`,
  provider: 'openai_compatible',
  baseUrl: 'https://api.example.com/v1',
  model: `${role}-model`,
  enabled: true,
  lastCheckStatus: 'unknown',
  ...extra,
})

// —— 角色清单：五个都列（这里与「我的 AI 模型」不同：站长要看全部）——
report(AI_ROLE_ORDER.length === 5, '站长这份列全部五个角色')
report(RUNTIME_CAVEAT.includes('不证明'), '说明里写清了「面板已配 ≠ 真的生效」')

// —— 全部配齐 + Python 可达 ——
nextProviders = { status: 200, payload: { code: 0, data: AI_ROLE_ORDER.map((r) => configured(r)) } }
nextHealth = { status: 200, payload: { code: 0, data: { available: true, reason: '' } } }
await ops.loadRuntime()
report(ops.runtimeRows.length === 5, '五个角色各一行')
report(ops.missingRoles.length === 0, '没有缺角色')
report(ops.pythonReachable === true, '探活说可达')
report(ops.verdict.includes('都已配置'), '结论句直接可读：都已配置且可达')
report(
  ops.runtimeRows.every((row) => row.statusText === '已配置'),
  '措辞是「已配置」而**不是**「运行中」（后者在说一件没被证明的事）',
)

// —— 缺角色：要说清「没配会怎样」 ——
nextProviders = {
  status: 200,
  payload: { code: 0, data: [configured('chat'), configured('embedding')] },
}
await ops.loadRuntime()
report(ops.missingRoles.length === 3, '缺三个角色被数出来')
report(
  ops.runtimeRows.find((row) => row.role === 'rerank').impact.includes('重排'),
  '每个角色都带「没配会怎样」，而不是只显示「未配置」',
)
report(ops.verdict.includes('3 个角色没配'), '结论句点出缺几个')

// —— 停用与未配置是两种状态（都不能用，但原因不同）——
nextProviders = {
  status: 200,
  payload: { code: 0, data: [configured('chat'), configured('fast', { enabled: false })] },
}
await ops.loadRuntime()
const fastRow = ops.runtimeRows.find((row) => row.role === 'fast')
report(fastRow.status === 'disabled' && fastRow.statusText === '已停用', '停用显示为「已停用」而不是「未配置」')
report(ops.disabledRoles.join(',') === 'fast', '停用的角色单独列出来')

// —— 探活失败：配置那块不该一起失败 ——
nextProviders = { status: 200, payload: { code: 0, data: [configured('chat')] } }
nextHealth = { status: 503, payload: { code: 503, msg: '网关连不上' } }
await ops.loadRuntime()
report(ops.loaded === true && ops.failed === false, '探活失败不影响「配置读到了」这个事实')
report(ops.healthFailed === true, '探活失败被单独标记（界面说「Python 是否可达未知」）')
report(ops.verdict.includes('不可达'), '结论句如实说 Python 不可达')
report(ops.pythonReachable === false, '拿不到探活结果时不假装可达')

// —— 配置读不到：整体失败且清空，不留旧数据 ——
nextProviders = { status: 500, payload: { code: 500, msg: '服务繁忙' } }
nextHealth = { status: 200, payload: { code: 0, data: { available: true } } }
await ops.loadRuntime()
report(ops.failed === true, '配置读不到标记为失败')
report(Object.keys(ops.providers).length === 0, '失败时清空，不展示可能已过期的端点')

// —— 用量：正常路径 ——
nextUsage = {
  status: 200,
  payload: {
    code: 0,
    data: {
      days: 7,
      calls: 100,
      successCalls: 92,
      failedCalls: 8,
      promptTokens: 120000,
      completionTokens: 30000,
      totalTokens: 150000,
      cost: 3.5,
      unpricedCalls: 0,
      untokenizedCalls: 0,
      byScene: [{ key: 'qa', calls: 90, totalTokens: 140000, cost: 3.2, unpricedCalls: 0, untokenizedCalls: 0 }],
      byModel: [{ key: 'qwen-plus', calls: 100, totalTokens: 150000, cost: 3.5, unpricedCalls: 0, untokenizedCalls: 0 }],
    },
  },
}
await ops.loadUsage(7)
report(ops.usage.calls === 100, '拿到用量汇总')
report(Math.abs(ops.failureRate - 0.08) < 1e-9, '失败率 = 失败数 / 总数（不是成功率）')
report(ops.costCaveats.length === 0, '价格与 token 都齐时不额外报警')
report(
  calls.some((item) => item === 'GET /ai/admin/usage/summary?days=7'),
  '窗口参数真的传下去了（默认 7 天）',
)

// —— 没跑过：失败率是 null 而不是 0（0/0 不是 0%）——
nextUsage = { status: 200, payload: { code: 0, data: { days: 7, calls: 0, failedCalls: 0, cost: 0 } } }
await ops.loadUsage()
report(ops.failureRate === null, '一次都没跑过时失败率是 null（0/0 不是 0）')

// —— 有没单价的调用：必须说「成本偏低不代表便宜」——
nextUsage = {
  status: 200,
  payload: {
    code: 0,
    data: { days: 7, calls: 10, failedCalls: 0, cost: 0, unpricedCalls: 7, untokenizedCalls: 3 },
  },
}
await ops.loadUsage()
report(ops.costCaveats.length === 2, '两种情况各给一条提示')
report(
  ops.costCaveats.some((note) => note.includes('没有单价')) &&
    ops.costCaveats.some((note) => note.includes('token')),
  '分别说清「按 0 计成本」与「连算都算不了」',
)

// —— 用量读不到：单独失败，不影响运行时那块 ——
nextUsage = { status: 500, payload: { code: 500, msg: '服务繁忙' } }
await ops.loadUsage()
report(ops.usageFailed === true && ops.usage === null, '用量失败单独标记并清空')
report(ops.usageError.length > 0, '失败原因留给界面显示与重试')

/* ================= 第二优先：检索审计 + 链路回放 ================= */

// —— 审计正常路径：拒答率与失败率**分开** ——
nextAudit = {
  status: 200,
  payload: {
    code: 0,
    data: {
      days: 7,
      total: 40,
      refused: 10,
      failed: 2,
      refusalRate: 0.25,
      failureRate: 0.05,
      // ⚠️ Java 的 Map<Long,Integer> 序列化出来**键是字符串** —— 这里照真实形状给
      topPosts: { '7': 5, '9': 2 },
      repeatQuestions: { ['a'.repeat(64)]: 3 },
      notes: ['10 次拒答（25.0%）—— 拒答率高说明语料没覆盖，不是链路坏了。'],
    },
  },
}
await ops.loadAudit(7)
report(ops.audit.total === 40, '拿到审计汇总')
report(ops.auditRefusalRate === 0.25 && ops.auditFailureRate === 0.05, '拒答率与失败率是两个数')
report(
  calls.some((item) => item === 'GET /ai/admin/retrieval-audit/summary?days=7'),
  '窗口参数传下去了',
)
report(
  ops.auditTopPosts.length === 2 && ops.auditTopPosts[0].postId === 7,
  'Map 的字符串键被归一成文章 id（不归一的话 postId 会是 "7"，链接与比较都会错）',
)
report(
  ops.auditRepeatQuestions[0].hash.length === 64 && ops.audit.notes.length > 0,
  '审计不存问题原文，界面只有哈希 —— 后端那句结论原样带回来显示',
)

// —— 零记录：**不能**推出「没人问过」（表还没建也会这样）——
nextAudit = {
  status: 200,
  payload: {
    code: 0,
    data: {
      days: 7,
      total: 0,
      refused: 0,
      failed: 0,
      refusalRate: 0,
      failureRate: 0,
      topPosts: {},
      repeatQuestions: {},
      notes: ['这段时间没有检索记录（要么还没人问，要么审计表还没建）。'],
    },
  },
}
await ops.loadAudit()
report(ops.auditIsEmpty === true, '零记录被识别为「空」状态')
report(ops.auditRefusalRate === null, '零记录时拒答率是 null（0/40 与 0/0 不是一回事）')
report(
  ops.audit.notes[0].includes('审计表还没建'),
  '后端把两种可能都说了出来，界面照实显示而不是画一张「0 次」的空图',
)

// —— 审计读不到（最常见：表没建）：说「读不出来」，不是「没人用过」——
nextAudit = {
  status: 500,
  payload: { code: 500, msg: "Table 'stellar_ink.ai_retrieval_audit' doesn't exist" },
}
await ops.loadAudit()
report(ops.auditFailed === true && ops.audit === null, '审计读不到单独标记为失败')
report(ops.auditError.length > 0, '失败原因留给界面显示与重试')
// ⚠️ 这条最初写成「断言 error.message 里有表名」—— **那是错的假设**：
// `api/client.js` 对 5xx 会丢弃后端 msg（统一成「星笺暂时无法响应」），
// 表名根本到不了界面。所以提示必须是界面自带的一条静态指引，断言也改成盯它
report(
  AUDIT_HINT.includes('17_ai_retrieval_audit.sql'),
  '审计失败时给出可操作提示（指出要执行哪个脚本），而不是只显示一句「服务繁忙」',
)

// —— 链路回放：调用账与检索回放是两份数据 ——
nextTrace = {
  status: 200,
  payload: {
    code: 0,
    data: {
      traceId: 'abc123',
      calls: [
        { scene: 'qa', model: 'qwen-plus', latencyMs: 900, success: 1, totalTokens: 500 },
        { scene: 'qa', model: 'text-embedding-v3', latencyMs: 40, success: 1, totalTokens: 20 },
      ],
      events: [{ kind: 'retrieval', candidates: 20 }],
      pythonAvailable: true,
      pythonFound: true,
      notes: [],
    },
  },
}
await ops.loadTrace('abc123')
report(ops.traceCalls.length === 2, '调用账有两条（来自数据库，跨副本、持久）')
report(ops.traceHasReplay === true, '回放命中时标记为有')
report(ops.traceEvents.length === 1, '检索事件单独一份')

// —— pythonFound=false：**不能说「这条链路不存在」** ——
nextTrace = {
  status: 200,
  payload: {
    code: 0,
    data: {
      traceId: 'abc123',
      calls: [{ scene: 'qa', model: 'qwen-plus', success: 1 }],
      events: [],
      pythonAvailable: true,
      pythonFound: false,
      notes: [],
    },
  },
}
await ops.loadTrace('abc123')
report(ops.traceHasReplay === false, '回放没命中')
report(
  ops.traceMissReason.includes('这个副本上') && !ops.traceMissReason.includes('不存在'),
  '措辞是「这个副本上没有」而不是「不存在」—— 多副本时它可能在别处（已知限制）',
)
report(ops.traceCalls.length === 1, '回放没命中时调用账仍然显示（那才是权威来源）')

// —— Python 不可达与「副本上没有」要分开说 ——
nextTrace = {
  status: 200,
  payload: {
    code: 0,
    data: {
      traceId: 'abc123',
      calls: [],
      events: [],
      pythonAvailable: false,
      pythonFound: false,
      notes: [],
    },
  },
}
await ops.loadTrace('abc123')
report(ops.traceMissReason.includes('不可达'), 'Python 不可达时说的是「不可达」，不是「副本上没有」')

// —— 空输入与失败 ——
await ops.loadTrace('   ')
report(ops.trace === null && ops.traceError.includes('traceId'), '空输入就地提示，不发请求')
report(ops.traceFailed === false, '空输入不算「失败」')
nextTrace = { status: 403, payload: { code: 403, msg: '没有权限' } }
await ops.loadTrace('abc123')
report(ops.traceFailed === true && ops.traceError.length > 0, '回放请求失败单独标记并留原因')

if (failed > 0) {
  console.error(`\nAI 观测面自检失败 ${failed} 条`)
  process.exit(1)
}
console.log('\nAI 观测面自检全部通过')
await server.close()
