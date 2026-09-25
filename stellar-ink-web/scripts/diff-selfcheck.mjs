/* 行级差异的自检（不引测试框架：前端没有测试运行器，临时用 node 直接跑）。
 * 手写期望值而不是复算一遍算法 —— 复算只能证明「代码等于代码」。 */
import { diffLines, splitLines, MAX_DIFF_LINES } from '../src/utils/diff.js'
import { actionFor, resolveText, MAX_TITLE_LENGTH } from '../src/utils/copilot-action.js'
import { parseFrame, FrameSplitter } from '../src/utils/sse.js'

let failed = 0
function check(name, actual, expected) {
  const a = JSON.stringify(actual)
  const e = JSON.stringify(expected)
  if (a !== e) {
    failed += 1
    console.error(`✗ ${name}\n  期望 ${e}\n  实际 ${a}`)
  } else {
    console.log(`✓ ${name}`)
  }
}

function shape(before, after, opts) {
  const r = diffLines(before, after, opts)
  return {
    rows: r.rows.map((row) => `${row.type === 'same' ? ' ' : (row.type === 'add' ? '+' : (row.type === 'del' ? '-' : '~'))}${row.text}`),
    added: r.added,
    removed: r.removed,
    unchanged: r.unchanged,
    truncated: r.truncated,
  }
}

check('拆行去掉末尾空元素', splitLines('a\nb\n'), ['a', 'b'])
check('CRLF 与 LF 等价', shape('a\r\nb', 'a\nb').unchanged, true)
check('完全相同', shape('甲\n乙', '甲\n乙').rows, [' 甲', ' 乙'])
check('追加一行', shape('甲\n乙', '甲\n乙\n丙'), {
  rows: [' 甲', ' 乙', '+丙'], added: 1, removed: 0, unchanged: false, truncated: false,
})
check('修改中间一行', shape('甲\n乙\n丙', '甲\n乙改\n丙'), {
  rows: [' 甲', '-乙', '+乙改', ' 丙'], added: 1, removed: 1, unchanged: false, truncated: false,
})
check('删除全部', shape('甲\n乙', ''), {
  rows: ['-甲', '-乙'], added: 0, removed: 2, unchanged: false, truncated: false,
})
check('从空开始', shape('', '甲'), {
  rows: ['+甲'], added: 1, removed: 0, unchanged: false, truncated: false,
})
check('空对空', shape('', '').rows, [])
/* 折叠：未改动段比 2*context+1 更长才折。这里前 6 行留 2 行 + 折 2 行 + 2 行上下文。 */
const longBefore = ['1', '2', '3', '4', '5', '6', '改动前', '8', '9', '10', '11'].join('\n')
const longAfter = ['1', '2', '3', '4', '5', '6', '改动后', '8', '9', '10', '11'].join('\n')
check('上下文折叠（前缀）', diffLines(longBefore, longAfter, { context: 2 }).rows
  .map((row) => (row.type === 'fold' ? `~${row.count}` : row.type)).slice(0, 5),
['same', 'same', '~2', 'same', 'same'])
check('上下文折叠（后缀）', diffLines(longBefore, longAfter, { context: 2 }).rows
  .map((row) => (row.type === 'fold' ? `~${row.count}` : row.type)).slice(-4),
['same', 'same', 'same', 'same'])
check('未改动不足时不折（折了不省行）', diffLines('甲\n乙', '甲\n乙\n丙', { context: 2 }).rows.map((r) => r.type),
  ['same', 'same', 'add'])
check('折叠后省不下一行就不折', diffLines('甲\n乙', '甲\n乙\n丙', { context: 2 }).rows.map((r) => r.type),
  ['same', 'same', 'add'])
check('长文退化为整段替换', (() => {
  const before = Array.from({ length: MAX_DIFF_LINES + 5 }, (_, i) => `旧${i}`).join('\n')
  const after = Array.from({ length: MAX_DIFF_LINES + 5 }, (_, i) => `新${i}`).join('\n')
  const r = diffLines(before, after)
  return { truncated: r.truncated, why: r.why, removed: r.removed, added: r.added }
})(), { truncated: true, why: 'too-long', removed: MAX_DIFF_LINES + 5, added: MAX_DIFF_LINES + 5 })

/* 行号连续性：删/加两侧各自独立编号，前端 gutter 靠它，错位会看不出改了哪几行 */
const numbered = diffLines('甲\n乙\n丙', '甲\n丙\n丁')
check('行号', numbered.rows.map((row) => [row.type, row.before, row.after]), [
  ['same', 1, 1], ['del', 2, null], ['same', 3, 2], ['add', null, 3],
])

/* 重复行：两种切法编辑距离相同，朴素 LCS 会保留「更靠后的那个同名行」，
 * 于是删/加出现在另一处 —— 这是等价的差异描述，不是错误。这里把实际形态写下来锁住行为，
 * 避免以后换算法时无声地改变界面上看到的东西。 */
check('重复行（首行重复）', shape('甲\n甲\n乙', '甲\n乙').rows, [' 甲', '-甲', ' 乙'])
check('重复行（首尾重复）', shape('甲\n乙\n甲', '乙').rows, ['-甲', ' 乙', '-甲'])

/* ---- Copilot 采纳动作：错一个就会抹掉作者的正文，必须钉死 ---- */
check('润色只能替换正文', actionFor('polish').mode, 'replace')
check('续写绝不能替换正文（否则会抹掉已写内容）', actionFor('continue').mode, 'insert')
check('续写要插到光标处', actionFor('continue').verb, '插到光标处')
check('提纲追加到末尾', actionFor('outline').mode, 'append')
check('标题只动标题', actionFor('title').mode, 'title')
check('标签/摘要只复制，不动正文', [actionFor('tags').mode, actionFor('summary').mode], ['copy', 'copy'])
check('未知任务退到只复制（不猜成替换）', actionFor('translate'), { mode: 'copy', verb: '复制' })
check('提纲追加带空行', resolveText('append', '一、星星'), '\n\n一、星星')
check('续写不加前缀', resolveText('insert', '  接着写。  '), '接着写。')
check('空候选不产生写入', resolveText('replace', '   '), '')
check('标题超长被截断', resolveText('title', '标'.repeat(MAX_TITLE_LENGTH + 30)).length, MAX_TITLE_LENGTH)
/* 这条是回归护栏：整段替换必须原样写入（不做 trim 之外的加工），否则差异预览会骗人 */
check('替换原样写入', resolveText('replace', '第一行\n第二行'), '第一行\n第二行')

/* ---- SSE 切帧：问答流式的读侧，错了会表现为「答到一半没了」 ---- */
check('解析 data 帧', parseFrame('data: {"type": "delta", "text": "好"}')?.type, 'delta')
check('冒号后无空格也认', parseFrame('data:{"type":"done"}')?.type, 'done')
check('注释行不是事件', parseFrame(': ping'), null)
check('空帧不是事件', parseFrame(''), null)
check('半截 JSON 标 unknown 而不是丢掉', parseFrame('data: {"type": "del').type, 'unknown')

const splitter = new FrameSplitter()
check('一块里切出两帧', splitter.push('data: {"type":"a"}\n\ndata: {"type":"b"}\n\n').map((f) => f.type), ['a', 'b'])
check('残留的半帧不吐出来', splitter.push('data: {"type":"c"}').length, 0)
check('后半帧到了才吐（TCP 分片）', splitter.push('\n\ndata: {"type":"d"}\n\n').map((f) => f.type), ['c', 'd'])
check('心跳夹在中间被丢掉', splitter.push(': ping\n\ndata: {"type":"e"}\n\n').map((f) => f.type), ['e'])
check('结尾没有空行时 flush 补上', splitter.push('data: {"type":"f"}') && splitter.flush()?.type, 'f')
check('flush 之后缓冲清空', splitter.flush(), null)
/* 一问一答里最常见的中文被劈开：TextDecoder 交给调用方，这里验证切帧不受影响 */
const multi = new FrameSplitter()
check('逐字符喂也能切出帧', (() => {
  const text = 'data: {"type":"delta","text":"每"}\n\ndata: {"type":"done"}\n\n'
  const out = []
  for (const char of text) out.push(...multi.push(char))
  return out.map((f) => f.type)
})(), ['delta', 'done'])

console.log(failed ? `\n${failed} 项失败` : '\n全部通过')
process.exit(failed ? 1 : 0)
