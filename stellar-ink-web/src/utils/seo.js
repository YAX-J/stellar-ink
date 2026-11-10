/** canonical 不保留搜索参数、追踪参数和锚点，不接受跨站地址。 */
export function canonicalUrl(raw, origin, pathname) {
  const fallback = new URL(pathname, origin)
  let url
  try { url = new URL(raw || pathname, origin) } catch { url = fallback }
  if (url.origin !== origin || !['http:', 'https:'].includes(url.protocol)) url = fallback
  url.search = ''
  url.hash = ''
  return url.href
}

/** 只描述已发布的公开内容；不虚构作者、时间、图片或搜索结果评分。 */
export function articleData(item, kind, author) {
  if (!item?.title || Number(item.status) !== 1 || (kind === 'note' && item.visibility !== 'PUBLIC')) return null
  const data = { '@context': 'https://schema.org', '@type': 'BlogPosting', headline: item.title, inLanguage: 'zh-CN' }
  if (author && author !== '未知星客') data.author = { '@type': 'Person', name: author }
  for (const [key, value] of [['datePublished', item.date], ['dateModified', item.updatedAt]]) {
    if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}(T.*)?$/.test(value) && !Number.isNaN(Date.parse(value))) data[key] = value
  }
  if (Number.isFinite(Number(item.words)) && Number(item.words) > 0) data.wordCount = Number(item.words)
  return data
}

export const SITE_NAME = '星笺 · STELLAR INK'
export const HOME_TITLE = `${SITE_NAME} — 把思绪挂成星图`
export const DEFAULT_DESCRIPTION = '把文章比作星辰的夜间写作与技术笔记博客：随笔、技术笔记与星图。'

/** 服务端与浏览器共用摘要规则，避免原始 Markdown 出现在分享卡片里。 */
export function clip(text, max = 120) {
  const plain = String(text || '')
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`([^`]*)`/g, '$1')
    .replace(/!\[[^\]]*]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]+)]\([^)]*\)/g, '$1')
    .replace(/[#>*_~|]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return plain.length > max ? `${plain.slice(0, max)}…` : plain
}
