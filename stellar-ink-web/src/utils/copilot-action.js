/* ================= Copilot 采纳动作 =================
 * 「候选 → 正文的哪一种改动」是这一版唯一的判断逻辑，也是最容易出错的地方：
 * 把「续写」当成替换会**直接抹掉作者已经写好的正文**。
 * 因此把它抽成纯函数放在这里，由 `scripts/diff-selfcheck.mjs` 直接跑断言 ——
 * 前端没有测试运行器（AGENTS.md §3 不加测试依赖），但这条判断不能只靠肉眼看模板。
 */

/**
 * 采纳动作 = 候选该怎么落到正文上。
 * - `replace`：整段替换正文（润色）
 * - `title`  ：只填标题，绝不碰正文
 * - `insert` ：插到光标处 / 末尾（续写、提纲）
 * - `copy`   ：不动正文，只复制（标签、摘要这类不是正文的内容）
 */
const ACTIONS = {
  polish: { mode: 'replace', verb: '替换正文' },
  continue: { mode: 'insert', verb: '插到光标处' },
  outline: { mode: 'append', verb: '追加到末尾' },
  title: { mode: 'title', verb: '填入标题' },
  tags: { mode: 'copy', verb: '复制' },
  summary: { mode: 'copy', verb: '复制' },
}

const FALLBACK = { mode: 'copy', verb: '复制' }

/** 标题上限：与后端 `@Size`/前端标题输入保持一致，别把整段候选塞进标题栏 */
export const MAX_TITLE_LENGTH = 200

/** 查某个任务的采纳动作；未知任务一律退到「只复制」，绝不猜成替换 */
export function actionFor(task) {
  return ACTIONS[task] || FALLBACK
}

/**
 * 候选落成正文时实际要写入的文本。
 * 续写用「空行 + 候选」拼接，避免新段落黏在原来的最后一行后面。
 */
export function resolveText(mode, rawText) {
  const text = String(rawText ?? '').trim()
  if (!text) return ''
  if (mode === 'title') return text.slice(0, MAX_TITLE_LENGTH)
  if (mode === 'append') return `\n\n${text}`
  return text
}
