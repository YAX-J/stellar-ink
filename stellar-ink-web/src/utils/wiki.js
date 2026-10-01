/**
 * 知识条目的定位逻辑（纯函数，便于离线自检）。
 *
 * 为什么用「按文本找」而不是「按块下标找」：条目里存的是**段落内容哈希与原文片段**，
 * 不是渲染后的块下标 —— 正文渲染出来的是 Markdown 块（段落/引用/列表/代码……），
 * 与切块用的子块**不是同一套下标**。按下标硬对，会在文章一编辑就悄悄指错地方；
 * 按文本找最多是「找不到」，而找不到是可以如实说出来的（见 `locateEvidence`）。
 */

/** 规范化空白：模型与原文在换行/多空格上常不一致，那不是「找不到证据」 */
export function normalizeText(text) {
  return String(text || '').replace(/\s+/g, '')
}

/**
 * 在正文容器里找出**包含这段原文**的第一个块元素。
 *
 * @param {HTMLElement|null} container 正文容器（`ref` 拿到的那个）
 * @param {string} quote 原文片段
 * @returns {HTMLElement|null} 命中的元素；找不到或参数不足时 null
 */
export function findEvidenceElement(container, quote) {
  const needle = normalizeText(quote)
  // 太短的片段会命中一大片无关内容：宁可不定位，也不要跳到错的地方
  if (!container || needle.length < 4) return null
  const nodes = container.querySelectorAll('.md-body > *')
  for (const node of nodes) {
    if (normalizeText(node.textContent).includes(needle)) return node
  }
  return null
}

/**
 * 滚动到证据所在的位置并短暂高亮。
 *
 * @returns {'located'|'missing'|'unavailable'} 三种结果分开返回而不是布尔：
 *   界面要说的是「正文里找不到这段文字（文章可能改过）」，而不是笼统的「定位失败」。
 */
export function locateEvidence(container, quote, { className = 'wiki-hit' } = {}) {
  if (!container) return 'unavailable'
  const element = findEvidenceElement(container, quote)
  if (!element) return 'missing'
  element.scrollIntoView({ behavior: 'smooth', block: 'center' })
  element.classList.add(className)
  // 高亮是临时的：留着会让整篇文章看起来像被标注过
  setTimeout(() => element.classList.remove(className), 2000)
  return 'located'
}
