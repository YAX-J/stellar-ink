/* ================= 行级差异（LCS） =================
 * Copilot 的候选要**先看差异、再决定采纳**，所以必须能算出「原文 → 候选」到底动了哪几行。
 * 这里手写 LCS，不引 diff 依赖：需求只有「行级 + 上下文折叠」，几十行代码的事，
 * 而任何 diff 库都会进首屏包（AGENTS.md §3：新增依赖要克制）。
 *
 * 两个刻意的取舍：
 * - **超长文本不硬算**：LCS 是 O(n·m)，几千行的草稿会把矩阵撑到几百万格、
 *   在输入框里一卡就是几百毫秒。超过阈值直接退化成「整段替换」，并标注 why=too-long ——
 *   宁可粗糙也不要假死，长文本来就该整段接受。
 * - **相同行的匹配是朴素的**：不做「相似行」模糊配对。润色场景下几乎每行都变了，
 *   模糊配对算出来的「修改」反而更难读，不如老老实实显示「删了这些、加了这些」。
 */

/** 超过这个行数就放弃 LCS：n·m 会到千万级，前端算不动 */
export const MAX_DIFF_LINES = 400
/** 矩阵单元格硬上限（双保险：两轴都不大但乘积很大时也拦住） */
const MAX_CELLS = 160000

export const DIFF_TOO_LONG = 'too-long'

/**
 * 逐行比较两段文本。
 * @param {string} before 原文（编辑区现状）
 * @param {string} after  候选（Copilot 建议）
 * @param {{ context?: number }} [opts] context=保留的被折叠上下文行数，0 表示全部保留
 * @returns {{ rows: Array<{ type: 'same'|'add'|'del'|'fold', text: string, count?: number, before?: number|null, after?: number|null }>,
 *             added: number, removed: number, truncated: boolean, unchanged: boolean, why?: string }}
 */
export function diffLines(before, after, { context = 0 } = {}) {
  const oldLines = splitLines(before)
  const newLines = splitLines(after)
  const stats = { added: 0, removed: 0, truncated: false, unchanged: false }

  if (oldLines.join('\n') === newLines.join('\n')) {
    stats.unchanged = true
    return { rows: toRows(oldLines, 'same', context), ...stats }
  }

  const tooLong = oldLines.length > MAX_DIFF_LINES
    || newLines.length > MAX_DIFF_LINES
    || oldLines.length * newLines.length > MAX_CELLS
  if (tooLong) {
    stats.truncated = true
    stats.removed = oldLines.length
    stats.added = newLines.length
    return {
      rows: [
        ...toRows(oldLines, 'del', 0),
        ...toRows(newLines, 'add', 0),
      ],
      ...stats,
      why: DIFF_TOO_LONG,
    }
  }

  const ops = trace(oldLines, newLines)
  const rows = []
  let beforeNo = 1
  let afterNo = 1
  for (const op of ops) {
    if (op.type === 'same') {
      rows.push({ type: 'same', text: op.text, before: beforeNo, after: afterNo })
      beforeNo += 1
      afterNo += 1
    } else if (op.type === 'del') {
      stats.removed += 1
      rows.push({ type: 'del', text: op.text, before: beforeNo, after: null })
      beforeNo += 1
    } else {
      stats.added += 1
      rows.push({ type: 'add', text: op.text, before: null, after: afterNo })
      afterNo += 1
    }
  }
  return { rows: foldContext(rows, context), ...stats }
}

/** 拆行：统一换行符，保证「最后一行有没有换行」不制造假差异 */
export function splitLines(text) {
  const normalized = String(text ?? '').replace(/\r\n?/g, '\n')
  if (!normalized) return []
  const lines = normalized.split('\n')
  // 末尾换行会产生一个空元素，它不是一行内容，去掉以免凭空多出一条空行差异
  if (lines.length > 1 && lines[lines.length - 1] === '') lines.pop()
  return lines
}

/**
 * LCS 回溯。
 * 先填 DP 表，再从左上往右下走：相等即 same，否则走「上/左」里更长的那侧。
 *
 * 平局取 `del`（而不是 `add`）是**刻意的**：把「删掉旧行」排在「加上新行」前面，
 * 差异块看起来就是先减后加；若取 add，同一个改动会被显示成「先加新行、再删旧行」，
 * 而 `-`/`+` 配对错位会让人误读成「改了两处」。
 *
 * 结果按走位顺序直接 push（不再分头尾两段反转）—— 一旦分段反转，
 * 差异块之后的上下文行会被倒序，看起来像把段落搬了家。
 * @returns {Array<{ type: 'same'|'add'|'del', text: string }>}
 */
function trace(oldLines, newLines) {
  const n = oldLines.length
  const m = newLines.length
  const table = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1))
  for (let i = n - 1; i >= 0; i -= 1) {
    for (let j = m - 1; j >= 0; j -= 1) {
      table[i][j] = oldLines[i] === newLines[j]
        ? table[i + 1][j + 1] + 1
        : Math.max(table[i + 1][j], table[i][j + 1])
    }
  }

  const ops = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (oldLines[i] === newLines[j]) {
      ops.push({ type: 'same', text: oldLines[i] })
      i += 1
      j += 1
    } else if (table[i + 1][j] >= table[i][j + 1]) {
      ops.push({ type: 'del', text: oldLines[i] })
      i += 1
    } else {
      ops.push({ type: 'add', text: newLines[j] })
      j += 1
    }
  }
  while (i < n) {
    ops.push({ type: 'del', text: oldLines[i] })
    i += 1
  }
  while (j < m) {
    ops.push({ type: 'add', text: newLines[j] })
    j += 1
  }
  return ops
}

/** 整段同类型（未改变 / 退化替换）时用的行列表 */
function toRows(lines, type, context) {
  const rows = lines.map((text, index) => ({
    type,
    text,
    before: type === 'add' ? null : index + 1,
    after: type === 'del' ? null : index + 1,
  }))
  return type === 'same' ? foldContext(rows, context) : rows
}

/** 把连续 same 行按 context 折叠成 `…(N 行未变)…`，让差异自己浮出来 */
function foldContext(rows, context) {
  if (!context || context < 0) return rows
  const out = []
  let index = 0
  while (index < rows.length) {
    if (rows[index].type !== 'same') {
      out.push(rows[index])
      index += 1
      continue
    }
    let end = index
    while (end < rows.length && rows[end].type === 'same') end += 1
    const run = end - index
    // 折叠要真的省行：context=0 时折掉 1 行没意义，至少 2 行才折
    const threshold = context === 0 ? 1 : context * 2 + 1
    if (run <= threshold) {
      out.push(...rows.slice(index, end))
    } else {
      out.push(...rows.slice(index, index + context))
      out.push({ type: 'fold', text: '', count: run - context * 2 })
      out.push(...rows.slice(end - context, end))
    }
    index = end
  }
  return out
}
