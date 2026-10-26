/* ================= 正文代码块的轻量语法高亮 =================
 * 为什么自己写：AGENTS §3 约定「前端新增依赖要克制」，而阅读侧代码块此前只有纯文本
 * （编辑器的 CodeMirror 高亮只在写作时生效，读者看不到）。
 *
 * 为什么单独一个文件：它**只允许**被 MarkdownBody 通过动态 import 加载 ——
 * 首页 / 星图 / 笔记列表页没有代码块，不该为它付下载与解析成本。
 *
 * 覆盖范围：java / js(ts) / sql / yaml / json / bash / html。
 * 其它语言原样输出（仍然转义），不知道就装作懂，比不上色更糟。
 *
 * 安全：先按规则切分**原文**，再对每一段 escapeHtml —— 输出里只有 <span> 与转义后的文本，
 * 与 utils/markdown.js 的「先转义、再插白名单标签」是同一套思路，因此 v-html 依旧安全。
 *
 * 实现取向：不做完整的词法分析。按照「注释 → 字符串 → 关键字 → 数字」的优先级
 * 在当前位置尝试匹配（sticky 正则），命中就整段跳过。这只影响极端写法的配色，
 * 但换来一个 160 行的、没有任何依赖的高亮器 —— 足够读代码，且不会拖进 100KB 的语法表。
 */
import { escapeHtml } from './markdown.js'

const rule = (cls, source, flags = '') => ({ cls, re: new RegExp(source, flags + 'y') })

const JAVA_KEYWORDS = 'abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|'
  + 'default|do|double|else|enum|extends|final|finally|float|for|goto|if|implements|import|'
  + 'instanceof|int|interface|long|native|new|package|private|protected|public|record|return|'
  + 'sealed|short|static|strictfp|super|switch|synchronized|this|throw|throws|transient|try|'
  + 'var|void|volatile|while|yield|permits|null|true|false'

const JS_KEYWORDS = 'async|await|break|case|catch|class|const|continue|debugger|default|delete|do|'
  + 'else|export|extends|finally|for|from|function|get|if|import|in|instanceof|let|new|of|return|'
  + 'set|static|super|switch|this|throw|try|typeof|undefined|var|void|while|with|yield|'
  + 'null|true|false'

const SQL_KEYWORDS = 'select|from|where|and|or|not|null|is|in|between|like|join|left|right|inner|'
  + 'outer|on|group|by|order|having|limit|offset|insert|into|values|update|set|delete|create|'
  + 'table|alter|add|column|index|primary|key|unique|default|drop|truncate|as|distinct|union|all|'
  + 'exists|case|when|then|else|end|asc|desc|if|begin|commit|rollback|engine|charset|collate|'
  + 'varchar|int|bigint|datetime|tinyint|text|auto_increment|comment'

const BASH_KEYWORDS = 'if|then|else|elif|fi|for|in|do|done|while|until|case|esac|function|return|'
  + 'export|local|readonly|declare|source|set|unset|alias|echo|exit|cd|pwd|test|trap|shift'

const BASH_COMMANDS = 'git|npm|pnpm|yarn|npx|node|mvn|gradle|docker|docker-compose|kubectl|java|'
  + 'javac|python|python3|pip|uv|curl|wget|ssh|scp|rsync|grep|rg|sed|awk|cat|ls|cp|mv|rm|mkdir|'
  + 'chmod|chown|systemctl|journalctl|mysql|redis-cli|nginx|tar|zip|unzip|find|xargs|kill|ps'

/* 规则顺序即优先级：注释必须在字符串前（`// don't` 里的撇号不能被当成字符串开头），
 * 字符串必须在关键字前（"class" 是一个值，不是一个关键字）。 */
const RULES = {
  java: [
    rule('hl-com', String.raw`//[^\n]*`),
    rule('hl-com', String.raw`/\*[\s\S]*?\*/`),
    rule('hl-str', String.raw`"(?:[^"\\\n]|\\.)*"`),
    rule('hl-str', String.raw`'(?:[^'\\\n]|\\.)*'`),
    rule('hl-ann', String.raw`@[A-Za-z_][\w.]*`),
    rule('hl-num', String.raw`\b\d[\w.]*`),
    rule('hl-key', String.raw`\b(?:${JAVA_KEYWORDS})\b`),
  ],
  js: [
    rule('hl-com', String.raw`//[^\n]*`),
    rule('hl-com', String.raw`/\*[\s\S]*?\*/`),
    rule('hl-str', String.raw`"(?:[^"\\\n]|\\.)*"`),
    rule('hl-str', String.raw`'(?:[^'\\\n]|\\.)*'`),
    rule('hl-str', String.raw`\x60(?:[^\x60\\]|\\.)*\x60`),
    rule('hl-num', String.raw`\b\d[\w.]*`),
    rule('hl-key', String.raw`\b(?:${JS_KEYWORDS})\b`),
  ],
  sql: [
    rule('hl-com', String.raw`--[^\n]*`),
    rule('hl-com', String.raw`#[^\n]*`),
    rule('hl-com', String.raw`/\*[\s\S]*?\*/`),
    rule('hl-str', String.raw`'(?:[^'\\]|\\.)*'`),
    rule('hl-str', String.raw`"(?:[^"\\]|\\.)*"`),
    rule('hl-num', String.raw`\b\d[\w.]*`),
    rule('hl-key', String.raw`\b(?:${SQL_KEYWORDS})\b`, 'i'),
  ],
  yaml: [
    rule('hl-com', String.raw`#[^\n]*`),
    rule('hl-attr', String.raw`[A-Za-z_$][\w.$-]*(?=\s*:)`),
    rule('hl-str', String.raw`"(?:[^"\\\n]|\\.)*"`),
    rule('hl-str', String.raw`'(?:[^'\n])*'`),
    rule('hl-var', String.raw`\$\{[^}]*\}|\$[\w@#?*!-]+`),
    rule('hl-key', String.raw`\b(?:true|false|null|yes|no|on|off)\b`, 'i'),
    rule('hl-num', String.raw`\b\d+(?:\.\d+)?`),
  ],
  json: [
    rule('hl-attr', String.raw`"(?:[^"\\\n]|\\.)*"(?=\s*:)`),
    rule('hl-str', String.raw`"(?:[^"\\\n]|\\.)*"`),
    rule('hl-num', String.raw`-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?`),
    rule('hl-key', String.raw`\b(?:true|false|null)\b`),
  ],
  bash: [
    rule('hl-com', String.raw`#[^\n]*`),
    rule('hl-str', String.raw`"(?:[^"\\]|\\.)*"`),
    rule('hl-str', String.raw`'(?:[^'\\]|\\.)*'`),
    rule('hl-var', String.raw`\$\{[^}]*\}|\$[\w@#?*!-]+`),
    rule('hl-fn', String.raw`\b(?:${BASH_COMMANDS})\b`),
    rule('hl-key', String.raw`\b(?:${BASH_KEYWORDS})\b`),
    rule('hl-num', String.raw`\b\d[\w.]*`),
  ],
  html: [
    rule('hl-com', String.raw`<!--[\s\S]*?-->`),
    rule('hl-key', String.raw`</?[A-Za-z][\w:-]*`),
    rule('hl-key', String.raw`/?>`),
    rule('hl-attr', String.raw`[A-Za-z_:][\w:.-]*(?=\s*=)`),
    rule('hl-str', String.raw`"[^"]*"`),
    rule('hl-str', String.raw`'[^']*'`),
  ],
}

const ALIASES = {
  js: 'js', jsx: 'js', ts: 'js', tsx: 'js', javascript: 'js', typescript: 'js', mjs: 'js',
  java: 'java', kt: 'java', kotlin: 'java', gradle: 'java',
  sql: 'sql', mysql: 'sql',
  yaml: 'yaml', yml: 'yaml',
  json: 'json', jsonc: 'json',
  bash: 'bash', sh: 'bash', shell: 'bash', zsh: 'bash', powershell: 'bash', ps1: 'bash',
  html: 'html', xml: 'html', vue: 'html', svg: 'html',
}

/** 语言名归一：返回 null 表示「不处理」，调用方原样转义输出 */
function rulesFor(lang) {
  const key = String(lang || '').trim().toLowerCase()
  const family = ALIASES[key]
  return family ? RULES[family] : null
}

/**
 * 高亮一段代码，返回**可直接 v-html** 的 HTML。
 *
 * @param {string} code 原始代码（未转义）
 * @param {string} lang 围栏里标注的语言
 */
export function highlight(code, lang) {
  const text = String(code ?? '')
  const rules = rulesFor(lang)
  if (!rules) {
    return escapeHtml(text)
  }
  let out = ''
  let i = 0
  while (i < text.length) {
    let hit = null
    for (const { cls, re } of rules) {
      re.lastIndex = i
      const match = re.exec(text)
      if (match && match[0].length > 0) {
        hit = { cls, value: match[0] }
        break
      }
    }
    if (hit) {
      out += `<span class="${hit.cls}">${escapeHtml(hit.value)}</span>`
      i += hit.value.length
    } else {
      out += escapeHtml(text[i])
      i += 1
    }
  }
  return out
}
