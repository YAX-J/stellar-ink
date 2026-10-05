import { request } from '@/api/client'

/**
 * 引用核验（A2）：把答案与引用交给 `POST /ai/agent/verify`，换回一份**确定性**报告。
 *
 * 为什么值得每轮自动调一次：服务端那一侧是**零模型调用、不占配额**的纯计算
 * （只做三件确定性判断：编号是否越界、答案有没有标编号、引用片段能否在原文里找到），
 * 却回答了「这次给的引用站不站得住」——而这件事光看界面看不出来。
 *
 * ⚠️ 语义边界（服务端文档同样写明，前端文案必须跟着走）：
 * - `verdict === 'ok'` 只表示**引用能对上原文**，**不等于「答案已被核实」**；
 * - `checked === 0`（或 `evidenceAvailable === false`）是「这次没有原文可比」，
 *   不是「比过了没问题」——这两者长得一样，但意思相反。
 * 所以界面上说的是「引用核验」，不能说成「答案已核实」。
 */
export function verifyAnswer({ answer, citations }) {
  return request('/ai/agent/verify', {
    method: 'POST',
    body: { answer: String(answer || ''), citations: citations || [] },
  })
}

/**
 * 把核验报告说成一句人话。分三态，不能合并：
 * - `checked > 0` 的 ok：真的比过若干条；
 * - `checked === 0` 的 ok：没得比（原文不在语料里）——**不能说成"都没问题"**；
 * - warn：至少一处需要注意（编号越界 / 没标编号 / 片段对不上 / 段落已不在语料）。
 */
export function verifyText(report) {
  if (!report) return ''
  if (report.verdict !== 'warn') {
    return report.checked > 0
      ? `引用核验：${report.checked} 条引用都能在原文里找到`
      : '引用核验：这次没有可比的原文（不代表没有问题）'
  }
  const problems = report.problems || []
  const first = problems[0]?.message || '有引用对不上原文'
  return problems.length > 1
    ? `引用核验：${first}（另有 ${problems.length - 1} 处）`
    : `引用核验：${first}`
}
