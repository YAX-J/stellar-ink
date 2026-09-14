/* ================= 轻量 Markdown 渲染器 =================
 * 为什么自己写：项目约定「前端不加依赖」，而正文当前只按换行切段，
 * 导致 CSS 里已备好的 blockquote / h3 / 引用样式永远不会被渲染。
 *
 * 安全约定（重要）：
 *  1. 先 escapeHtml 把 < > & " ' 全部转义，原始 HTML 一律不解析；
 *  2. 行内标记由本文件在转义后的文本上插入自己的标签，标签集合是白名单常量；
 *  3. 链接只允许 http/https/mailto 及站内相对路径，其他协议（javascript: 等）直接降级为纯文本。
 * 因此 v-html 输出的是「已转义的文本 + 白名单标签」，不构成 XSS 面。
 *
 * 支持：标题 h1-h4、引用、无序/有序列表、围栏代码块、分割线、
 *       提示卡 `> [!NOTE]`、**加粗**、*斜体*、`行内代码`、[链接](url)
 */

const ESCAPE_MAP = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
}

export function escapeHtml(text) {
  return String(text ?? '').replace(/[&<>"']/g, (ch) => ESCAPE_MAP[ch])
}

/** 链接协议白名单：挡掉 javascript: / data: 之类的伪协议 */
function safeUrl(raw) {
  const url = String(raw || '').trim()
  if (!url) return ''
  if (/^(https?:|mailto:|tel:)/i.test(url)) return url
  /* 站内相对路径与锚点允许 */
  if (/^[/#]/.test(url)) return url
  return ''
}

/** 行内标记：输入必须是已转义文本 */
export function renderInline(escaped) {
  let out = escaped
  /* 行内代码优先摘出，避免其内部再被加粗/斜体规则命中 */
  const codes = []
  out = out.replace(/`([^`]+)`/g, (_, code) => {
    codes.push(code)
    return `\u0000${codes.length - 1}\u0000`
  })
  out = out
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
    .replace(/~~([^~]+)~~/g, '<del>$1</del>')
    .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (match, label, href) => {
      const url = safeUrl(href)
      if (!url) return label
      const external = /^https?:/i.test(url)
      const attrs = external ? ' target="_blank" rel="noopener noreferrer"' : ''
      return `<a href="${url}"${attrs}>${label}</a>`
    })
  /* 还原行内代码 */
  out = out.replace(/\u0000(\d+)\u0000/g, (_, index) => `<code>${codes[Number(index)]}</code>`)
  return out
}

/** 行内纯文本：去掉行内标记，用于生成目录与锚点 id */
function inlineText(escaped) {
  return escaped
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1$2')
    .replace(/~~([^~]+)~~/g, '$1')
    .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, '$1')
    .trim()
}

/** 生成稳定锚点 id：同名标题自增后缀 */
function slugify(text, used) {
  const base = text
    .toLowerCase()
    .replace(/[\s]+/g, '-')
    .replace(/[^\w\u4e00-\u9fa5-]/g, '')
    .slice(0, 48) || 'section'
  let slug = base
  let n = 2
  while (used.has(slug)) {
    slug = `${base}-${n++}`
  }
  used.add(slug)
  return slug
}

/**
 * 解析 Markdown 为块级结构。
 * @param {string} source 原始正文
 * @returns {{ blocks: Array<object>, toc: Array<{id:string,text:string,level:number}> }}
 */
export function parseMarkdown(source) {
  const lines = String(source ?? '').replace(/\r\n?/g, '\n').split('\n')
  const blocks = []
  const toc = []
  const usedSlugs = new Set()
  let i = 0

  const flushParagraph = (buffer) => {
    if (!buffer.length) return
    blocks.push({ type: 'paragraph', html: renderInline(escapeHtml(buffer.join(' '))) })
    buffer.length = 0
  }
  const paragraph = []

  while (i < lines.length) {
    const line = lines[i]

    /* 围栏代码块 */
    const fence = line.match(/^\s*```\s*(\S*)\s*$/)
    if (fence) {
      const lang = fence[1] || ''
      const code = []
      i += 1
      while (i < lines.length && !/^\s*```\s*$/.test(lines[i])) {
        code.push(lines[i])
        i += 1
      }
      i += 1
      blocks.push({ type: 'code', lang, text: code.join('\n') })
      continue
    }

    /* 空行：段落分界 */
    if (!line.trim()) {
      flushParagraph(paragraph)
      i += 1
      continue
    }

    /* 分割线 */
    if (/^\s*([-*_])\s*(\1\s*){2,}$/.test(line)) {
      flushParagraph(paragraph)
      blocks.push({ type: 'hr' })
      i += 1
      continue
    }

    /* 提示卡：> [!NOTE] 后续引用行合并为一张 note */
    const noteHead = line.match(/^\s*>\s*\[!(NOTE|TIP|WARN|IMPORTANT)\]\s*(.*)$/i)
    if (noteHead) {
      const body = noteHead[2] ? [noteHead[2]] : []
      i += 1
      while (i < lines.length && /^\s*>/.test(lines[i])) {
        body.push(lines[i].replace(/^\s*>\s?/, ''))
        i += 1
      }
      blocks.push({
        type: 'note',
        kind: noteHead[1].toUpperCase(),
        html: renderInline(escapeHtml(body.join(' ').trim())),
      })
      continue
    }

    /* 标题 */
    const heading = line.match(/^\s{0,3}(#{1,4})\s+(.*)$/)
    if (heading) {
      flushParagraph(paragraph)
      const level = heading[1].length
      const text = inlineText(escapeHtml(heading[2].trim()))
      const id = slugify(text, usedSlugs)
      toc.push({ id, text, level })
      blocks.push({ type: 'heading', level, id, html: renderInline(escapeHtml(heading[2].trim())) })
      i += 1
      continue
    }

    /* 引用块：连续 > 行合并 */
    if (/^\s*>/.test(line)) {
      flushParagraph(paragraph)
      const quote = []
      while (i < lines.length && /^\s*>/.test(lines[i])) {
        quote.push(lines[i].replace(/^\s*>\s?/, ''))
        i += 1
      }
      blocks.push({
        type: 'quote',
        html: quote.map((q) => renderInline(escapeHtml(q))).join('<br>'),
      })
      continue
    }

    /* 列表：同一类型的连续行合并为一个列表 */
    const bullet = line.match(/^\s*([-*+])\s+(.*)$/)
    const ordered = line.match(/^\s*(\d+)[.)]\s+(.*)$/)
    if (bullet || ordered) {
      flushParagraph(paragraph)
      const isOrdered = !!ordered
      const items = []
      while (i < lines.length) {
        const m = isOrdered
          ? lines[i].match(/^\s*\d+[.)]\s+(.*)$/)
          : lines[i].match(/^\s*[-*+]\s+(.*)$/)
        if (!m) break
        items.push(renderInline(escapeHtml(m[1])))
        i += 1
      }
      blocks.push({ type: 'list', ordered: isOrdered, items })
      continue
    }

    paragraph.push(line.trim())
    i += 1
  }
  flushParagraph(paragraph)

  return { blocks, toc }
}
