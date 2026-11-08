/** 展示时按文档归并引用，保留原始编号和片段；文章与同号笔记是不同文档。 */
export function groupCitations(citations = []) {
  const groups = new Map()
  citations.forEach((citation, index) => {
    const kind = citation.kind || 'post'
    const key = citation.postId == null ? `missing:${index}` : `${kind}:${citation.postId}`
    if (!groups.has(key)) {
      groups.set(key, { key, kind, postId: citation.postId, title: citation.title, citations: [] })
    }
    groups.get(key).citations.push({ ...citation, no: citation.no ?? index + 1 })
  })
  return [...groups.values()]
}
