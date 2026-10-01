/* 深挖（只读 Agent）前端自检：node 直接跑，用 vite 的 SSR 加载器加载真实 store。
 *
 * 为什么需要它：Agent 有三种「没给出答案」的形态，而它们的**产品含义完全不同**：
 * ① `doneReason=length` —— 预算用尽，**不是失败**：answer 可能为空，但 citations 往往有值；
 * ② `interruptedBy=caller` / 用户按停止 —— 是主动行为，不该显示成错误；
 * ③ 真的抛错（含 429 配额）—— 只有这一种该进 `error` 并弹提示。
 *
 * 把①当成③是这类功能最容易犯的错：用户看到「失败」而其实已经查到东西了。
 * 这里把三种形态都摆出来断言一遍，另外盯住两件小事：
 * 客户端**只能收紧**预算（quick=3 步，服务端默认 4），以及「停止」要真的中止请求。
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

const body = (payload) => ({ code: 0, data: payload, traceId: 'trace-agent' })
const calls = []
/** 下一次 /ai/agent/ask 的响应（自检按场景改它） */
let next = null

globalThis.fetch = async (url, init = {}) => {
  const path = new URL(String(url)).pathname
  calls.push({ path, body: init.body ? JSON.parse(init.body) : null, signal: init.signal })
  const reply = (status, payload) => ({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    text: async () => JSON.stringify(payload),
  })

  if (path !== '/ai/agent/ask') {
    return reply(404, { code: 404, msg: `自检未覆盖的接口：${path}` })
  }
  if (next?.hang) {
    // 「停止」场景：请求一直不回，等外部 abort
    return new Promise((resolve, reject) => {
      init.signal?.addEventListener('abort', () => {
        const error = new Error('aborted')
        error.name = 'AbortError'
        reject(error)
      })
    })
  }
  return reply(next?.status || 200, next?.payload ?? body({}))
}

let failed = 0
function report(ok, label) {
  console.log(`${ok ? '✓' : '✗'} ${label}`)
  if (!ok) failed += 1
}

const server = await createServer({ server: { middlewareMode: true }, appType: 'custom', logLevel: 'error' })
const { useAgentStore, AGENT_STEPS } = await server.ssrLoadModule('/src/stores/agent.js')
setActivePinia(createPinia())
const agent = useAgentStore()

// —— ① 预算用尽：不是失败 ——
next = {
  payload: body({
    answer: '',
    citations: [{ postId: 3, title: '一年写十八万字', chunkIndex: 1, snippet: '每天写五百字', score: 0.7 }],
    doneReason: 'length',
    steps: [
      { index: 0, tool: 'search_posts', label: '检索到 2 段', error: '' },
      { index: 1, tool: 'author_style', label: '', error: '工具超时' },
    ],
    toolCalls: 2,
    interruptedBy: 'budget',
    usageModel: 'deepseek-flash',
    latencyMs: 4200,
  }),
}
let thrown = null
try {
  await agent.ask('一年写十八万字的方法是什么？')
} catch (error) {
  thrown = error
}
report(thrown === null, '预算用尽不抛错（doneReason=length 是正常收尾）')
report(agent.error === '', '预算用尽不写 error（界面不该显示成失败）')
report(agent.budgetExhausted === true, 'budgetExhausted 判据成立')
report(agent.hasSomething === true, 'answer 为空但有引用时仍算「有东西可显示」')
report(agent.citations.length === 1, '引用原样保留')
report(agent.steps.length === 2, '步骤原样保留（过程要看得见）')
report(agent.steps[1].error === '工具超时', '单步失败如实保留在那一步上')
report(agent.offline === false, '真实模型不挂「离线自测」标记')
report(agent.running === false, '跑完之后不卡在「检索中」')

// —— 预算只能收紧：quick 传 3 步，standard **不带字段**（由服务端决定） ——
report(
  AGENT_STEPS.quick === 3 && AGENT_STEPS.standard === null,
  '两档预算符合「只能收紧」：3 步 / 不带字段',
)
report(calls.at(-1).body.maxSteps === 3, 'quick 档发出的是 maxSteps=3')
await agent.ask('再问一次', { depth: 'standard' })
report(
  !('maxSteps' in calls.at(-1).body),
  'standard 档**不带** maxSteps：服务端默认是 4，界面不能替它承诺一个数',
)

// —— ② 用户停止：不是错误 ——
agent.reset()
next = { hang: true }
const pending = agent.ask('一个会很久的问题')
await new Promise((resolve) => setTimeout(resolve, 10))
agent.abort()
thrown = null
try {
  await pending
} catch (error) {
  thrown = error
}
report(thrown === null, '「停止」不抛错')
report(agent.stopped === true, '停止后如实标记 stopped')
report(agent.error === '', '停止不写 error')
report(agent.running === false, '停止后不卡在「检索中」')
report(agent.interruptedByCaller === true, '界面据此显示「已停止等待」而不是失败')

// —— ③ 真的失败（429 配额）：这才该进 error ——
agent.reset()
next = { status: 429, payload: { code: 429, msg: '今日 AI 用量已达上限，请稍后再试。' } }
thrown = null
try {
  await agent.ask('配额用尽时的问题')
} catch (error) {
  thrown = error
}
report(thrown !== null, '429 抛错（要能被调用方感知）')
report(agent.error !== '', '429 写进 error 供局部展示')
report(agent.running === false, '失败后不卡在「检索中」')

// —— 边界：空问题不打接口 ——
const before = calls.length
report((await agent.ask('   ')) === null, '空问题直接返回 null')
report(calls.length === before, '空问题不发请求（不浪费额度）')

await server.close()
console.log(failed ? `\n${failed} 项失败` : '\n深挖前端自检通过')
process.exit(failed ? 1 : 0)
