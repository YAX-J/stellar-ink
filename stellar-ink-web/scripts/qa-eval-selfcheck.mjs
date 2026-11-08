/* 真正运行 Pinia store，保护停止调度、失败恢复和跨账号异步结果隔离。 */
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { createPinia, setActivePinia } from 'pinia'

globalThis.window = { location: { origin: 'http://localhost:5173' } }
globalThis.localStorage = { getItem: () => null }
const server = await createServer({ server: { middlewareMode: true }, appType: 'custom', logLevel: 'error' })
const { useQaEvalStore } = await server.ssrLoadModule('/src/stores/qaEval.js')
const fresh = () => { setActivePinia(createPinia()); return useQaEvalStore() }
const response = (data, status = 200) => ({
  ok: status === 200, status, headers: { get: () => null },
  text: async () => JSON.stringify(status === 200 ? { code: 0, data } : data),
})
const snapshot = (done = 0) => ({
  runId: 'qa-run', total: 2, completed: done, busy: false,
  cases: [1, 2].map((n) => ({
    caseId: `a00${n}`, status: n <= done ? 'completed' : 'pending', answerHash: 'a'.repeat(64),
    review: { correct: null, citationsSupported: null, note: '' },
  })),
  summary: { correctReviewed: 0, answerAccuracy: null, citationSupportRate: null },
})
const deferred = () => { let resolve; const promise = new Promise((r) => { resolve = r }); return { promise, resolve } }
const tick = async () => { for (let i = 0; i < 8; i++) await new Promise((r) => setImmediate(r)) }

try {
  let pending = deferred()
  let steps = 0
  let reviewBody
  let serverRun = snapshot()
  globalThis.fetch = async (url, init) => {
    const path = new URL(url).pathname
    if (path.endsWith('/start')) return response(serverRun)
    if (path.endsWith('/step')) { steps += 1; return pending.promise }
    if (path.endsWith('/review')) {
      reviewBody = JSON.parse(init.body)
      serverRun.cases[0].review = { correct: reviewBody.correct, citationsSupported: reviewBody.citationsSupported, note: reviewBody.note }
      serverRun.summary = { correctReviewed: 1, answerAccuracy: 1, citationSupportRate: null }
      return response(serverRun)
    }
    return response(serverRun)
  }
  const store = fresh()
  store.resetForUser(1)
  const run = store.start('acceptance_v1')
  await tick()
  assert.equal(steps, 1)
  store.stop()
  serverRun = snapshot(1)
  pending.resolve(response(serverRun))
  await run
  assert.equal(steps, 1, '停止后不能继续发下一题')
  assert.equal(store.run.completed, 1, '保留当前题结果')
  assert.equal(store.hasPending, true)
  assert.equal(store.run.summary.answerAccuracy, null, '不能把有答案直接判为正确')
  assert.equal(store.selectedCase.review.correct, null)

  await store.saveReview(store.selectedCase, 'correct', true, '')
  assert.equal(reviewBody.answerHash, 'a'.repeat(64))
  assert.equal(reviewBody.correct, true)
  assert.equal(reviewBody.citationsSupported, null, '只复核答案不应代替引用复核')
  assert.equal(store.run.summary.correctReviewed, 1)

  pending = deferred()
  const resumed = store.resume()
  await tick()
  assert.equal(steps, 2)
  serverRun = snapshot(2)
  pending.resolve(response(serverRun))
  await resumed
  assert.equal(store.hasPending, false)
  assert.equal(store.run.completed, 2)

  // 同一账号开始新一轮后，旧轮的延迟读取也不得覆盖新轮。
  const oldRead = deferred()
  globalThis.fetch = async (url) => new URL(url).pathname.endsWith('/start')
    ? response({ ...snapshot(2), runId: 'new-run' }) : oldRead.promise
  const oldRefresh = store.refresh()
  await store.start('acceptance_v1')
  oldRead.resolve(response(snapshot(2)))
  await oldRefresh
  assert.equal(store.run.runId, 'new-run')

  // 服务端执行失败后读取真实记录，不能把失败当成拒答或丢掉上一题答案。
  serverRun = snapshot(1)
  serverRun.cases[1].status = 'error'
  serverRun.completed = 2
  globalThis.fetch = async (url) => new URL(url).pathname.endsWith('/step')
    ? response({ code: 502, msg: '上游模型不可用', traceId: 'trace-qa' }, 502)
    : response(serverRun)
  store.applySnapshot(snapshot(1))
  await store.resume()
  assert.equal(store.error, '星笺暂时无法响应，请稍后重试')
  assert.equal(store.traceId, 'trace-qa')
  assert.equal(store.run.cases[0].status, 'completed')
  assert.equal(store.run.cases[1].status, 'error')

  // 账号变化后，旧请求回来也不能把前一个人的评测重新显示出来。
  pending = deferred()
  globalThis.fetch = async () => pending.promise
  const refreshing = store.refresh()
  store.resetForUser(2)
  pending.resolve(response(serverRun))
  await refreshing
  assert.equal(store.run, null)
  assert.equal(store.error, '')
  pending = deferred()
  const starting = store.start('acceptance_v1')
  store.resetForUser(3)
  pending.resolve(response(serverRun))
  await starting
  assert.equal(store.run, null)
  assert.equal(store.running, false)

  // 模型复核逐题调度，不能覆盖人工结论，停止后不再请求下一题。
  const judgeStore = fresh()
  judgeStore.resetForUser(7)
  let judgeRun = snapshot(2)
  judgeRun.cases[0].review.correct = false
  judgeStore.applySnapshot(judgeRun)
  let judgePending = deferred()
  const judgeCalls = []
  globalThis.fetch = async (url, init) => {
    if (new URL(url).pathname.endsWith('/judge')) {
      judgeCalls.push(JSON.parse(init.body))
      return judgePending.promise
    }
    return response(judgeRun)
  }
  const firstJudge = judgeStore.judge()
  await tick()
  assert.equal(judgeCalls.length, 1)
  assert.equal(judgeCalls[0].caseId, 'a001')
  assert.equal(judgeCalls[0].answerHash, 'a'.repeat(64))
  judgeStore.stop()
  judgeRun = structuredClone(judgeRun)
  judgeRun.cases[0].modelReview = { correct: true, citationsSupported: true }
  judgeRun.modelSummary = { reviewed: 1, answerPassRate: 1 }
  judgePending.resolve(response(judgeRun))
  await firstJudge
  assert.equal(judgeCalls.length, 1)
  assert.equal(judgeStore.run.cases[0].review.correct, false, '模型判定不能覆盖人工结论')
  assert.equal(judgeStore.run.modelSummary.reviewed, 1)

  judgePending = deferred()
  const secondJudge = judgeStore.judge()
  await tick()
  assert.equal(judgeCalls.at(-1).caseId, 'a002', '继续批量复核只请求尚未模型复核的题')
  judgeRun = structuredClone(judgeRun)
  judgeRun.cases[1].modelReview = { correct: null, needsHumanReview: true }
  judgeRun.modelSummary = { reviewed: 2, conclusive: 1, answerPassRate: 1 }
  judgePending.resolve(response(judgeRun))
  await secondJudge
  assert.equal(judgeStore.run.modelSummary.reviewed, 2)
  assert.equal(judgeStore.run.summary.answerAccuracy, null)

  judgePending = deferred()
  const staleJudge = judgeStore.judge('a001')
  await tick()
  judgeStore.resetForUser(8)
  judgePending.resolve(response(judgeRun))
  await staleJudge
  assert.equal(judgeStore.run, null, '模型复核旧响应不能泄漏到新账号')
  assert.equal(judgeStore.judging, false)

  // 刷新页面后只读取保存的报告，自动选中需人工关注的题，不产生模型调用。
  const reportStore = fresh()
  reportStore.resetForUser(1)
  const report = snapshot(2)
  report.cases[1].modelReview = { needsHumanReview: true }
  const reportCalls = []
  globalThis.fetch = async (url) => {
    reportCalls.push(new URL(url).pathname)
    return response(report)
  }
  await reportStore.loadLatest({ onlyIfEmpty: true })
  assert.equal(reportStore.run.completed, 2)
  assert.equal(reportStore.selectedCaseId, 'a002')
  assert.equal(reportStore.reportView, true)
  assert.deepEqual(reportCalls, ['/ai/admin/eval/qa/latest'])
  await reportStore.loadLatest({ onlyIfEmpty: true })
  assert.equal(reportCalls.length, 1, '已有本轮结果时自动读取不得覆盖它')
  pending = deferred()
  globalThis.fetch = async () => pending.promise
  const staleReport = reportStore.loadLatest()
  reportStore.resetForUser(2)
  pending.resolve(response(report))
  await staleReport
  assert.equal(reportStore.run, null)
  assert.equal(reportStore.loadingReport, false)
  console.log('✓ 完整问答验收：停止与继续、复核分离、失败恢复、账号隔离')
  console.log('✓ 决策模型复核：逐题停止、跳过已审题、答案哈希、保留人工结论、账号隔离')
  console.log('✓ 最近报告：读取不调用模型、优先人工关注题、保护本轮结果与账号隔离')
} finally {
  await server.close()
}
