/* 星海问答（问星笺 / 助手浮层）前端自检：node 直接跑，用 vite 的 SSR 加载器加载**真实** composable。
 *
 * 为什么需要它（两个真实踩过的坑）：
 *
 * ① **构建通过 ≠ 运行时正确**：打包器把未定义的标识符当成全局变量，build 照样成功 ——
 *    抽模块时漏搬一个常量（`STREAM_PATH`）只会在用户点开页面时炸成 ReferenceError。
 *    这里真的调一次 `askStream()`，那类错误会当场红。
 * ② **流式路径的 401/429 曾经是静默失效的**：判定函数要求 `error instanceof ApiError`，
 *    而流式分支原先抛的是普通 Error —— 表现为「登录过期只显示一句『流式问答不可用』、
 *    配额触顶不弹提示」，而同一个用户走非流式却是正常的。这里把两条都钉住。
 *
 * 另外盯住 S3 重构的核心不变量：**两个实例互不干扰**（助手浮层与深读页面板会同时存在，
 * 共用一个状态时一边的 delta 会写进另一边的界面），以及多轮的 `history` 只在非空时才发。
 */
import { createServer } from 'vite'

globalThis.window = { location: { origin: 'http://localhost:5173' } }
const memory = new Map()
globalThis.localStorage = {
  getItem: (key) => (memory.has(key) ? memory.get(key) : null),
  setItem: (key, value) => memory.set(key, String(value)),
  removeItem: (key) => memory.delete(key),
}

const encoder = new TextEncoder()

/**
 * 取 ref 的值：`useQaStream()` 返回的是 ref，**只有组件模板会自动解包**，
 * 纯 JS 里必须显式取 `.value`（自检第一版忘了这件事，得到的是 ref 对象而不是答案）。
 */
const val = (maybeRef) =>
  maybeRef && typeof maybeRef === 'object' && 'value' in maybeRef ? maybeRef.value : maybeRef

/** 一帧 SSE：`data: {json}\n\n`（类型在 JSON 里，不用 `event:` 名） */
const frame = (type, payload = {}) => `data: ${JSON.stringify({ type, ...payload })}\n\n`

/**
 * 假 SSE 响应体。`chunks` 是**文本块**而不是帧：故意让调用方把一帧劈成两半喂进来，
 * 覆盖「帧被 TCP 分片劈开」这条最容易错的情况。
 */
function sseBody(chunks) {
  let index = 0
  return {
    getReader: () => ({
      read: async () =>
        index < chunks.length
          ? { value: encoder.encode(chunks[index++]), done: false }
          : { value: undefined, done: true },
      cancel: async () => {},
    }),
  }
}

const calls = []
/** 下一次流式请求的响应（自检按场景改它） */
let next = null

globalThis.fetch = async (url, init = {}) => {
  // 流式路径走的是 `fetch('/ai/qa/stream')`（相对路径）：node 里 `new URL` 没有 base 会抛，
  // 所以这里按「有 scheme 才解析」处理 —— 与浏览器里的行为等价
  const raw = String(url)
  const path = raw.startsWith('http') ? new URL(raw).pathname : raw.split('?')[0]
  calls.push({ path, body: init.body ? JSON.parse(init.body) : null, signal: init.signal })

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
  if (next?.status && next.status >= 400) {
    return { ok: false, status: next.status, body: null, text: async () => '' }
  }
  return { ok: true, status: 200, body: sseBody(next?.chunks || []), text: async () => '' }
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
const { useQaStream } = await server.ssrLoadModule('/src/composables/useQaStream.js')
const { isAuthError, isRateLimited } = await server.ssrLoadModule('/src/api/client.js')

/** 每个场景一个新实例：composable 是有状态的，复用会让上一场景的 answer 混进来 */
const fresh = () => useQaStream()

// —— ① 正常一轮：帧顺序 meta → citation → delta* → done，引用带 kind ——
const qa = fresh()
next = {
  chunks: [
    frame('meta', { model: 'deepseek-flash', questionLength: 12, topK: 5 }),
    // 故意把一帧劈成两半：切帧必须保留不完整的尾巴
    'data: {"type": "citation", "citation": {"kind": "no',
    'te", "postId": 11, "title": "GET /tags 偶尔 20 秒", "chunkIndex": 0, ' +
      '"snippet": "Redis 命令超时 500ms。", "score": 0.72}}\n\n',
    frame('delta', { text: '因为 ' }),
    frame('delta', { text: 'Redis 超时。' }),
    frame('done', {
      answer: '因为 Redis 超时。',
      doneReason: 'stop',
      usage: { model: 'deepseek-flash' },
      evidenceSufficient: true,
    }),
  ],
}
await qa.askStream('为什么偶尔很慢？')
report(val(qa.answer)?.answer === '因为 Redis 超时。', 'delta 逐帧拼成完整答案（含被劈开的帧）')
report(val(qa.answer)?.citations?.length === 1, '引用先于正文到达并被保留')
report(val(qa.answer)?.citations?.[0]?.kind === 'note', '引用的 kind 原样保留（前端据此跳 /note/:id）')
report(val(qa.answer)?.usage?.model === 'deepseek-flash', 'meta 里的模型标识进了 usage')
report(val(qa.completed) === true, '收到 done 才算完成')
report(val(qa.interrupted) === false, '正常收尾不算中断')
report(val(qa.refused) === false, '有依据时不算拒答')
report(val(qa.offline) === false, '真实模型不挂「离线自测」')
report(val(qa.streaming) === false && val(qa.asking) === false, '结束后不卡在「生成中」')
report(calls.at(-1).path === '/ai/qa/stream', '打的是流式端点')
report(!('history' in calls.at(-1).body), '单轮（没传 history）时**不带**该字段')

// —— ② 拒答：证据不足要能与「中断」分开 ——
const refused = fresh()
next = {
  chunks: [
    frame('meta', { model: 'fake' }),
    frame('done', {
      answer: '站内没有找到依据。',
      doneReason: 'refused',
      usage: { model: 'fake' },
      evidenceSufficient: false,
    }),
  ],
}
await refused.askStream('一个站里没写过的问题')
report(val(refused.refused) === true, 'evidenceSufficient=false 判为拒答')
report(val(refused.offline) === true, 'Fake 模型如实挂「离线自测」')
report(val(refused.interrupted) === false, '拒答不是中断（两者界面文案不同）')

// —— ③ 没收到 done：必须如实说「中断」，不能装作答完了 ——
const broken = fresh()
next = { chunks: [frame('meta', { model: 'fake' }), frame('delta', { text: '答到一半' })] }
await broken.askStream('会断掉的问题')
report(val(broken.completed) === false, '没收到 done 就不算完成')
report(val(broken.interrupted) === true, '判为中断')
report(String(val(broken.error)).includes('中断'), `中断要写进 error（当前：${val(broken.error)}）`)

// —— ③b 服务端明确报错时，真因不能被「中断」文案盖掉 ——
// 真实踩过：Java→Python 的流式验签 401 被显示成「回答中断了，内容可能不完整」，
// 看起来像网络抖动，而真正的原因（内部鉴权失败）一句都没露出来。
const errored = fresh()
next = {
  chunks: [
    frame('meta', { model: 'fake' }),
    frame('error', { code: 'AI_UPSTREAM_UNAVAILABLE', message: 'AI 服务暂时不可用，请稍后重试' }),
  ],
}
await errored.askStream('会返回 error 帧的问题')
report(
  String(val(errored.error)).includes('AI 服务暂时不可用'),
  `error 帧的真因要保留（当前：${val(errored.error)}）`,
)
report(!String(val(errored.error)).includes('中断'), '有 error 帧时不再写「回答中断了」')

// —— ④ 流式的 401 / 429 必须能被既有判定函数认出来（曾经是静默失效的） ——
const unauthorized = fresh()
next = { status: 401 }
let thrown = null
try {
  await unauthorized.askStream('登录过期时的问题')
} catch (error) {
  thrown = error
}
report(thrown !== null, '401 抛错（要能被调用方感知）')
report(isAuthError(thrown) === true, '抛的是 ApiError —— isAuthError() 认得出（曾静默失效）')

const limited = fresh()
next = { status: 429 }
thrown = null
try {
  await limited.askStream('配额用尽时的问题')
} catch (error) {
  thrown = error
}
report(isRateLimited(thrown) === true, '429 能被 isRateLimited() 认出来（前端有独立文案）')

// —— ⑤ 多轮：history 只在非空时才发 ——
const multi = fresh()
next = { chunks: [frame('done', { answer: '好', doneReason: 'stop', evidenceSufficient: true })] }
await multi.askStream('那它呢？', { history: [{ question: '上一问', answer: '上一答' }] })
report(calls.at(-1).body.history?.length === 1, '非空 history 会带上（多轮上下文）')
report(calls.at(-1).body.history?.[0]?.question === '上一问', 'history 原样透传，前端不加工')

// —— ⑥ S3 的核心不变量：两个实例互不干扰（浮层与深读页会同时存在） ——
const deepRead = fresh()
const dock = fresh()
next = { chunks: [frame('delta', { text: '浮层里的字' })] }
await dock.askStream('浮层的问题')
report(val(dock.answer)?.answer === '浮层里的字', '浮层实例拿到了自己的增量')
report(val(deepRead.answer) === null, '深读页实例**没有**被写入（这正是抽 composable 的理由）')
report(val(deepRead.streaming) === false, '深读页实例不会被别人的流改成「生成中」')

// —— ⑦ 停止：主动中止不算失败 ——
const stopped = fresh()
next = { hang: true }
const pending = stopped.askStream('一个会很久的问题')
await new Promise((resolve) => setTimeout(resolve, 10))
stopped.abort()
thrown = null
try {
  await pending
} catch (error) {
  thrown = error
}
report(thrown === null, '「停止」不抛错')
report(val(stopped.streaming) === false, '停止后不卡在「生成中」')
report(val(stopped.error) === '', '停止不写 error（界面显示「已停止等待」而不是失败）')

// —— ⑧ 边界：空问题不打接口 ——
const empty = fresh()
const before = calls.length
report((await empty.askStream('   ')) === null, '空问题返回 null')
report(calls.length === before, '空问题不发请求（不浪费额度）')

await server.close()
console.log(failed ? `\n${failed} 项失败` : '\n星海问答前端自检通过')
process.exit(failed ? 1 : 0)
