/**
 * 字数统计口径（与后端 common-core 的 WordCount.java 保持一致）。
 *
 * 规则：先剥掉 Markdown 的「结构符号」，再统计非空白字符数。
 * - 标题井号、引用符、列表符号与序号、围栏代码块的 ``` 标记都不算字；
 * - 强调符号 * _ ~ 与行内代码反引号不算字；
 * - 链接只算显示文字，URL 不算字；
 * - 中英文一律按「非空白字符」计，代码块里的代码仍然算字。
 *
 * 改这里的正则，必须同步改 WordCount.java，否则编辑器实时字数会与落库字数对不上。
 */

/** 围栏代码块：```lang ... ``` —— 只去围栏行，保留代码本身 */
const CODE_FENCE = /^\s*```.*$/gm
/** 行首结构符号：# 标题、> 引用、- * + 列表、1. 1) 有序列表（序号后必须有空格） */
const LEADING_MARKER = /^[ \t]{0,3}(?:#{1,6}[ \t]+|>[ \t]?|[-*+][ \t]+|\d{1,9}[.)][ \t]+)/gm
/** 链接：只保留显示文字，丢掉 URL 与图片语法 */
const LINK = /!?\[([^\]]*)\]\([^)]*\)/g
/** 行内代码与强调符号 */
const INLINE_MARK = /[*`_~]/g
/** 所有空白（含全角空格与换行） */
const WHITESPACE = /[\s\u3000]+/g

/**
 * 统计 Markdown 正文的净字数。
 * @param {string} content Markdown 正文
 * @returns {number} 非空白字符数
 */
export function countWords(content) {
  if (!content) return 0
  return content
    .replace(CODE_FENCE, '')
    .replace(LEADING_MARKER, '')
    .replace(LINK, '$1')
    .replace(INLINE_MARK, '')
    .replace(WHITESPACE, '')
    .length
}
