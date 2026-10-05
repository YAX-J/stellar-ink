/* 助手浮层的「模型选择 + 快速添加模型」自检：node 直接跑，用 vite 的 SSR 加载器加载**真实组件**。
 *
 * 为什么需要它（这个项目真实踩过一次）：
 *
 * ① **`vite build` 不校验模板里的未定义标识符**：打包器把它当全局变量，build 照样绿，
 *    只有用户点开那块 UI 时才炸成 ReferenceError（踩过的是 `STREAM_PATH is not defined`）。
 *    所以这里把浮层**真渲染一次**（展开面板、下拉与表单），渲染期才会暴露的错误当场红；
 *    另加一条**只针对本文件**的静态扫描，把模板里写到的名字逐个对照 `<script setup>` 的声明。
 *    ⚠️ 静态扫描刻意**不覆盖全仓 .vue**：Vue 的 props / slot / 局部作用域规则太多，
 *    一个粗糙的正则扫描在别处只会报假警（试过：37 个文件里 8 个假阳性），那比没有更糟。
 * ② 模型选择的三种状态**必须分开**（少一个都会被误读）：
 *    没配 = 「全局默认（站长配置）」、我配了 = 模型名 + 协议、**取数失败 = 「没读到配置」**。
 *    最后一种显示成「你还没配」会让用户以为自己的配置丢了（store 口径第 3 条）。
 * ③ 它只碰 `chat`：表单只能保存 chat，embedding / rerank 没有任何编辑入口，
 *    并且必须说清「地址填 API 根」「嵌入/重排由站长统一配」这两件事。
 * ④ 「拉取模型」是**可选**入口（本轮新增）：供应商可能根本没有 `/models`、也可能返回畸形，
 *    所以手打模型名必须一直可用；候选列表**只在内存**（不落任何存储）；
 *    **接口还不存在**（后端还没上，404/405/501）只说「拉不到」，绝不能说成「服务坏了」；
 *    拉取失败**不改动表单里已有的值**、不碰已保存配置、也不把用户踢下线。
 *    其中「点选填入」「失败不改已有值」两条**真调一次组件里的处理器**（`debugState.expose`），
 *    因为正则扫源码只能证明代码长得像。
 */
import { readFileSync } from 'node:fs'
import { createServer } from 'vite'
import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { createPinia, setActivePinia } from 'pinia'

const COMPONENT = 'src/components/assistant/AssistantDock.vue'
let failed = 0
function report(ok, label) {
  console.log(`${ok ? '✓' : '✗'} ${label}`)
  if (!ok) failed += 1
}

/* ============================ ① 模板标识符静态自查 ============================ */

/** 模板里合法但不来自 `<script setup>` 的名字（Vue 内置 + 项目自加的全局属性） */
const TEMPLATE_GLOBALS = new Set(['$route', '$router', '$attrs', '$slots', '$emit', '$refs',
  'Number', 'String', 'Boolean', 'Math', 'JSON', 'Object', 'Array', 'Date', 'RegExp', 'parseInt',
  'parseFloat', 'isNaN', 'NaN', 'Infinity', 'undefined', 'null', 'true', 'false', 'typeof', 'in',
  'of', 'new', 'return', 'if', 'else', 'this', 'instanceof'])

/** 从 `<script setup>` 里收集声明、import 进来的名字，以及 `defineProps` 声明的字段 */
function declaredNames(script) {
  const names = new Set()
  const push = (raw) => {
    const name = raw.split(':').pop().replace(/=.*/, '').replace(/\s+as\s+.*/, '').trim()
    if (/^[A-Za-z_$][\w$]*$/.test(name)) names.add(name)
  }
  for (const m of script.matchAll(/\b(?:const|let|var|function|class)\s+([A-Za-z_$][\w$]*)/g)) names.add(m[1])
  /* `const { a, b: c } = ...`：props / store 的解构也算声明 */
  for (const m of script.matchAll(/\b(?:const|let|var)\s*\{([^}]*)\}\s*=/g)) m[1].split(',').forEach(push)
  /* 函数参数解构：`function f({ a, b })` */
  for (const m of script.matchAll(/\(?\s*\{([^{}()]*)\}\s*\)?\s*(?:=>|\{)/g)) m[1].split(',').forEach(push)
  for (const m of script.matchAll(/import\s+\{([^}]*)\}\s+from/g)) m[1].split(',').forEach(push)
  for (const m of script.matchAll(/import\s+([A-Za-z_$][\w$]*)\s*(?:,|from)/g)) names.add(m[1])
  /* `defineProps({ show: {...}, title: {...} })` 的字段在模板里是直接用名字引用的 */
  for (const m of script.matchAll(/defineProps\s*\(\s*\{([\s\S]*?)\n\s*\}\s*\)/g)) {
    for (const field of m[1].matchAll(/^\s*([A-Za-z_$][\w$]*)\s*:/gm)) names.add(field[1])
    for (const field of m[1].matchAll(/^\s*([A-Za-z_$][\w$]*)\s*[,)]/gm)) names.add(field[1])
  }
  return names
}

/** 模板里出现的候选标识符（字符串字面量、属性名、箭头函数参数、`v-for`/`v-slot` 局部名不算） */
function templateIdentifiers(template, localNames) {
  const expressions = []
  for (const m of template.matchAll(/(?::|@|v-if=|v-else-if=|v-for=|v-model=|v-show=)"([^"]*)"/g)) {
    expressions.push(m[1])
  }
  for (const m of template.matchAll(/\{\{([^}]*)\}\}/g)) expressions.push(m[1])
  /* 模板内联的箭头函数（如 `MODES.find((m) => m.key === mode)`）：参数是局部名。
     按 `名字 =>` 收集，比去配对括号稳（嵌套括号那版正则会漏掉内层参数）。 */
  /* 模板内联的箭头函数（如 `MODES.find((m) => m.key === mode)`）：参数是局部名。
     按 `名字 =>` 与 `(名字, 名字) =>` 两种写法收集，比去配对括号稳（嵌套括号那版正则会漏掉内层参数）。 */
  const arrowParams = []
  for (const m of template.matchAll(/([A-Za-z_$][\w$]*)\s*=>/g)) arrowParams.push(m[1])
  for (const m of template.matchAll(/\(([^()]*)\)\s*=>/g)) {
    for (const piece of m[1].split(',')) {
      const name = piece.trim()
      if (/^[A-Za-z_$][\w$]*$/.test(name)) arrowParams.push(name)
    }
  }

  const locals = new Set(localNames)
  arrowParams.forEach((name) => locals.add(name))
  /* `v-for="(turn, index) in turns"` 与 `v-slot="{ item }"` 是模板自己声明的 */
  for (const m of template.matchAll(/v-for="\(?([^)"]*?)\)?\s+in\s/g)) {
    m[1].split(',').forEach((name) => locals.add(name.trim()))
  }
  for (const m of template.matchAll(/#[A-Za-z]*="\{([^}]*)\}"/g)) {
    m[1].split(',').forEach((name) => locals.add(name.split(':').pop().trim()))
  }

  const found = new Set()
  for (const expression of expressions) {
    const cleaned = expression
      .replace(/'[^']*'/g, ' ')                 // 字符串里的话不是标识符
      .replace(/"[^"]*"/g, ' ')
      .replace(/`[^`]*`/g, ' ')
      .replace(/\?\./g, '.')                    // 可选链先归一，否则 `a?.b` 会被当成两个名字
      .replace(/\(\s*([^()]*?)\s*\)\s*=>/g, ' ') // 箭头函数的参数（如 `.find((m) => ...)`）
      .replace(/\.\s*[A-Za-z_$][\w$]*/g, ' ')    // 属性访问的右半边
    for (const m of cleaned.matchAll(/(^|[^.\w$])([A-Za-z_$][\w$]*)/g)) {
      const name = m[2]
      if (locals.has(name) || TEMPLATE_GLOBALS.has(name)) continue
      found.add(name)
    }
  }
  return found
}

const source = readFileSync(COMPONENT, 'utf8')
const scriptStart = source.indexOf('<script setup>')
const script = source.slice(scriptStart, source.indexOf('</script>', scriptStart))
const template = source.slice(source.indexOf('<template>', scriptStart), source.lastIndexOf('</template>'))
const declared = declaredNames(script)
const missing = [...templateIdentifiers(template, declared)].filter((n) => !declared.has(n)).sort()
if (missing.length) console.error(`✗ ${COMPONENT} 模板里未声明：${missing.join(', ')}`)
report(missing.length === 0,
  `${COMPONENT} 模板里用到的标识符都在 <script setup> 里有声明（vite build 不查这个）`)

/* ============================ ② 浮层真渲染一次 ============================ */

globalThis.window = { location: { origin: 'http://localhost:5173' } }
const storage = new Map()
globalThis.localStorage = {
  getItem: (key) => (storage.has(key) ? storage.get(key) : null),
  setItem: (key, value) => storage.set(key, String(value)),
  removeItem: (key) => storage.delete(key),
}
/** sessionStorage 也要能查（红线：候选列表与 apiKey 绝不落任何本地存储） */
const session = new Map()
globalThis.sessionStorage = {
  getItem: (key) => (session.has(key) ? session.get(key) : null),
  setItem: (key, value) => session.set(key, String(value)),
  removeItem: (key) => session.delete(key),
}

let nextList = { status: 200, payload: { code: 0, data: [] } }
/** 下一次 `POST /ai/me/providers/models` 的响应（按场景改它）*/
let nextPull = { status: 200, payload: { code: 0, data: { models: [], truncated: false, source: '' } } }
/** 拉取接口收到的请求（断言路径与请求体的形状：路径与字段是定死的契约，不许改） */
const pullCalls = []
/** 挂住的那次拉取的放行开关（见 fetch 里的 `hang` 分支） */
let releasePull = null
globalThis.fetch = async (url, init = {}) => {
  const path = String(url).replace(/^https?:\/\/[^/]+/, '')
  const method = (init.method || 'GET').toUpperCase()
  const reply = (status, payload) => ({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    text: async () => JSON.stringify(payload),
  })
  if (method === 'GET' && path.startsWith('/ai/me/providers')) {
    return reply(nextList.status, nextList.payload)
  }
  if (method === 'POST' && path === '/ai/me/providers/models') {
    pullCalls.push({ path, body: init.body ? JSON.parse(init.body) : null })
    /* `hang` 场景：请求挂住不回，用来断言「拉取中」这个中间态（随后由 releasePull 放行，
       否则 `api/client.js` 那个 20s 超时定时器会让进程多活 20 秒） */
    if (nextPull.hang) {
      return new Promise((resolve) => {
        releasePull = () => resolve(reply(nextPull.status, nextPull.payload))
      })
    }
    return reply(nextPull.status, nextPull.payload)
  }
  /* 写接口一律不覆盖：本自检只关心**渲染出来的东西**与「有没有走 store」，
     写路径的口径由 scripts/my-models-selfcheck.mjs 盯（两边不重复断言同一件事） */
  return reply(404, { code: 404, msg: `自检未覆盖的接口：${path}` })
}

const server = await createServer({
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'error',
})
const dock = await server.ssrLoadModule('/src/components/assistant/AssistantDock.vue')
const { useMyModelsStore } = await server.ssrLoadModule('/src/stores/myModels.js')
const { normalizePullResult, pullFailureMessage } = await server.ssrLoadModule('/src/utils/providerModels.js')
const { on, SESSION_EXPIRED } = await server.ssrLoadModule('/src/utils/bus.js')
/** 用来做「会话失效」的正对照：普通请求的 401 必须仍然发事件 */
const { request } = await server.ssrLoadModule('/src/api/client.js')

/** 会话失效事件计数器：拉模型失败**绝不能**让用户被清会话踢回登录页 */
let sessionExpired = 0
on(SESSION_EXPIRED, () => { sessionExpired += 1 })

/** 只看「模型那一行」的文本：整页里还有注释与 placeholder（如 `qwen-plus`），
 *  拿整页做子串断言会得到假阳性 —— 自检自己说谎比没有自检更糟。 */
const modelRow = (html) => (html.match(/<button class="dock-model-pick"[\s\S]*?<\/button>/) || [''])[0]

/** 渲染展开态（默认是收起的，SSR 里点不了那颗星 —— 所以组件留了 `debugState` 这个调试入口） */
async function render(mine, debugState = { open: true, modelOpen: true, modelFormOpen: true }) {
  const app = createSSRApp({ render: () => h(dock.default, { debugState }) })
  const pinia = createPinia()
  app.use(pinia)
  setActivePinia(pinia)
  // ⚠️ Pinia store 在进程里是单例：组件取的是 app 的 pinia，不把这份状态塞进去，
  //    断言的就是另一份 items（自检第一版因此全红，而组件其实是对的）
  pinia._s.set('myModels', mine)
  app.config.globalProperties.$route = { fullPath: '/runway' }
  app.component('RouterLink', { setup: (_p, { slots }) => () => h('a', slots.default?.()) })
  return renderToString(app)
}

/**
 * 渲染表单态（下拉收起，免得两处文字互相干扰），并把组件交出来的调试 API 收回来。
 *
 * ⚠️ 为什么要 `expose`：SSR 里既没有点击、也不跑事件处理器，而「点选候选 → 填入模型名」
 * 与「拉取失败不改动表单里已有的值」这两条，**正则扫源码只能证明代码长得像**。
 * 收回来的就是组件里那几个真的 `ref` 与真的函数，调它们与用户点击走的是同一条代码。
 */
async function renderWithApi(mine, debugState = {}) {
  let api = null
  const html = await render(mine, {
    open: true, modelOpen: false, modelFormOpen: true, ...debugState,
    expose: (value) => { api = value },
  })
  return { html, api }
}

setActivePinia(createPinia())
const mine = useMyModelsStore()
// 每次渲染都新建 pinia，登录态要落到 localStorage 里（auth store 的初始化读它）
storage.set('stellar-ink-token', 'selfcheck-token')

// —— 没配：说「全局默认（站长配置）」，而不是留空 ——
nextList = { status: 200, payload: { code: 0, data: [] } }
await mine.load()
let html = await render(mine)
report(modelRow(html).includes('全局默认（站长配置）'), '没配时显示「全局默认（站长配置）」（不是留空）')
report(!html.includes('dock-model-chip'), '没配时不出现「我配的」标记')
report(html.includes('＋ 添加模型'), '下拉里有「＋ 添加模型」入口')

// —— 配了 chat：显示模型名 + 协议，且下拉里能看到掩码 ——
const chatConfig = {
  id: 3, role: 'chat', displayName: '我自己的 qwen-plus', provider: 'openai_compatible',
  baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus',
  apiKeyConfigured: true, apiKeyMask: 'sk-…9f3a', enabled: true,
}
nextList = { status: 200, payload: { code: 0, data: [chatConfig] } }
await mine.load()
html = await render(mine)
report(modelRow(html).includes('qwen-plus'), '配了时显示模型名')
report(modelRow(html).includes('OpenAI 兼容'), '配了时显示协议（供应商）')
report(html.includes('dock-model-chip'), '配了时有「我配的 / 用全局」的区分标记')
report(html.includes('全局默认（站长配置）'), '下拉里仍然留着「全局默认」那一项（可以切回去）')
report(html.includes('sk-…9f3a'), '下拉里显示的是掩码（明文永远读不回来）')
report(/<select[^>]*>[\s\S]*?openai_compatible[\s\S]*?<\/select>/.test(html),
  '供应商是固定两项的下拉（取值必须是后端 DTO 认的 openai_compatible / fake）')
report(html.includes('type="password"'), 'API Key 用密码框输入（不让人在屏幕上明文留一手）')
/* 下拉里每一行都得是**能点的真按钮**：这正是那次「点不了」事故的另一半 ——
 * DOM 在、样式也在，但用户点不到。这里同时钉住 type="button"（表单里不写 type 的按钮是 submit）。 */
report(/<button[^>]*class="dock-menu-item[^"]*"[^>]*type="button"/.test(html),
  '下拉里每一行都是能点的真按钮（`type="button"`，不是一行文字也不是提交按钮）')

// —— 表单里必须写清的三件事 ——
report(html.includes('不要带') && html.includes('/chat/completions'),
  '表单说清「base_url 填 API 根、不要带 /chat/completions」')
report(html.includes('向量索引只有一份'), '表单说清「嵌入/重排由站长统一配」的理由')
report(html.includes('留空 = 沿用已存密钥'), '已有配置时 Key 说清「留空 = 沿用已存密钥」')
report(!html.includes('/ai/admin/providers'), '浮层不碰管理端那份全局配置的接口')
report(!/v-model="modelForm\.(dimension|capabilities|role)"/.test(source),
  '表单里只有 chat 的四个字段，**没有** embedding / rerank 那些入口（配了也不生效）')
report((source.match(/myModels\.save\(/g) || []).length === 1
  && source.includes("myModels.save(CHAT_ROLE"), '保存只打 chat 一个角色')

// —— 取数失败：说「没读到配置」，不许说成「你还没配」——
nextList = { status: 503, payload: { code: 503, msg: '网关连不上' } }
await mine.load()
html = await render(mine)
report(modelRow(html).includes('没读到配置'), '取数失败时说「没读到配置」')
report(!modelRow(html).includes('qwen-plus'), '取数失败时不显示任何模型名（旧的也不行）')
report(!modelRow(html).includes('全局默认'), '取数失败时不说成「全局默认」（那是替服务端说话）')
report(html.includes('不代表你没配过'), '下拉里也如实说「没读到」而不是「没有」')

// —— 「改回全局」必须先确认：删除会连同加密后的 Key 一起删，而 Key 只写不读 ——
// 这条只能静态查（SSR 里点不到那个按钮）：`window.confirm` 必须出现在 `myModels.remove` **之前**，
// 且被否定时直接 return（否则误点一下就再也拿不回密钥）。
const useGlobal = source.slice(source.indexOf('async function useGlobalModel'))
  .slice(0, source.slice(source.indexOf('async function useGlobalModel')).indexOf('\n}'))
report(useGlobal.includes('window.confirm')
  && useGlobal.indexOf('window.confirm') < useGlobal.indexOf('myModels.remove(')
  && /confirm\([^)]*\)\)\s*\{\s*\n\s*return/.test(useGlobal),
  '「改回全局」先确认再删（误点会连密钥一起删掉，而密钥读不回来）')

// —— 操作结果的显示位置：表单收起来时也必须看得见（收起来才显示的错误等于没说）——
nextList = { status: 200, payload: { code: 0, data: [] } }
await mine.load()
html = await render(mine, { open: true, modelOpen: false, modelFormOpen: false })
report(html.includes('dock-model-menu') === false, '下拉收起后菜单 DOM 确实没了（调试开关生效）')

/* —— 回归护栏：模型下拉必须在**正常流**里 ——
 * 防的是真实事故（用户反馈「模型下拉点不了」）：`.dock-model-menu` 在模板里是 `.dock-model`
 * 那一行的**兄弟**、不是子元素，一旦写成 `position:absolute`，定位基准就是面板（面板带 relative），
 * `top:100%` 于是落到**面板底部之外**，被面板的 `overflow:hidden` 整块裁掉 ——
 * `modelOpen` 其实正常翻转了（点击生效），但用户什么都看不见，感知就是「点不了」。
 * ⚠️ 这是**写法约束、不是永久真理**：哪天真的需要它做浮层，正确做法是把菜单挂进 `.dock-model`
 * 里当子元素（并同步改这条断言），而不是只把 absolute 加回来。 */
const menuRule = (source.match(/\.dock-model-menu\s*\{[^}]*\}/) || [''])[0]
report(
  menuRule.length > 0
    && !/position\s*:\s*absolute/.test(menuRule)
    && !/\btop\s*:\s*100%/.test(menuRule)
    && !/pointer-events\s*:\s*none/.test(menuRule),
  '模型下拉在正常流里（不许回到 position:absolute：它会被面板 overflow:hidden 裁掉，表现为「点不了」）',
)
report(
  /max-height\s*:\s*\d+px/.test(menuRule) && /overflow-y\s*:\s*auto/.test(menuRule),
  '模型下拉自己限高并可滚动（过长不会顶破面板的 max-height）',
)
report(
  !/\.dock-form\s*\{[^}]*position\s*:\s*absolute/.test(source),
  '就地表单也是正常流（同一个「被 overflow 裁掉」的风险点，一并盯住）',
)
report(/<p v-if="auth\.isLoggedIn && !modelFormOpen && \(modelError \|\| modelNotice\)"/.test(source)
  && source.includes('class="dock-model-status"'),
  '失败原因另有一个**表单之外**的显示位置（`dock-model-status`，表单收起时仍在）')

/* ============================ ③ 拉取模型（可选入口） ============================ */

// —— 纯逻辑：畸形响应能列就列，不整块失败（供应商可能返回畸形，红线①）——
const messy = normalizePullResult({
  models: [
    { id: 'qwen-plus' },
    { id: '  qwen-plus  ' },   // 去空白后与上一条同名 → 只留一条
    { id: '' },                // 空 id → 丢掉
    { name: 'no-id' },         // 没有 id → 丢掉
    'deepseek-chat',           // 直接给字符串的形态
    null,
    { id: 'gpt-4o-mini', created: 1712345678 },
  ],
  truncated: true,
  source: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
})
report(messy.models.map((item) => item.id).join(',') === 'qwen-plus,deepseek-chat,gpt-4o-mini',
  '畸形响应能列就列：去重 + 丢掉空 id，剩下的照常给出（不是整块失败）')
report(messy.truncated === true && messy.source.startsWith('https://'), 'truncated 与 source 原样带出来')
report(normalizePullResult(null).models.length === 0 && normalizePullResult(undefined).truncated === false,
  '响应整个是 null / undefined 也不抛错（供应商返回畸形）')

// —— 失败文案：**接口还不存在 ≠ 服务坏了**（后端这一版还没上，这条现在就能验）——
const notFound = pullFailureMessage({ status: 404, message: '要找的内容不存在，可能已被删除' })
report(notFound.includes('拉不到') && !/服务|坏了|无法响应/.test(notFound),
  '接口 404（后端还没上）只说「拉不到」，绝不写成「服务坏了」')
report(pullFailureMessage({ status: 405, message: '' }).includes('拉不到')
  && pullFailureMessage({ status: 501, message: '' }).includes('拉不到'),
  '405 / 501 同样按「这个接口还没有」处理（不同网关/服务的回法不一样）')
report(/手打|手动填/.test(notFound), '失败文案带上「手动填模型名照样能保存」（按钮不是必经步骤）')
const badKey = pullFailureMessage({ status: 401, message: 'API Key 无效或已过期' })
report(badKey.includes('API Key 无效或已过期') && !badKey.includes('登录状态已失效'),
  '供应商 401 照抄后端那句可读文案，不被改写成「登录状态已失效」')
report(pullFailureMessage({ status: 502, message: '上游供应商连不上：dns 解析失败' }).includes('上游供应商连不上'),
  '502 也用后端那句（默认映射会把它换成「星笺暂时无法响应」，等于把原因吃掉）')

// —— 拉取成功 → 候选可点选并填入（**真调一次组件里的处理器**）——
const PULL_SOURCE = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
pullCalls.length = 0
nextPull = {
  status: 200,
  payload: { code: 0, data: { models: [{ id: 'qwen-plus' }, { id: 'deepseek-chat' }], truncated: false, source: PULL_SOURCE } },
}
const okRun = await renderWithApi(mine)
// 表单里先放一份「手打的」已有值：点选必须覆盖它，失败时则必须原样保留
okRun.api.modelForm.value = {
  provider: 'openai_compatible', baseUrl: PULL_SOURCE, model: '手打的值', apiKey: 'sk-live-x',
}
await okRun.api.pullModels()
report(okRun.api.modelPull.value?.models.length === 2 && okRun.api.modelPullOpen.value === true,
  '拉取成功后候选进状态并展开（2 个）')
report(pullCalls.at(-1)?.path === '/ai/me/providers/models', '打的就是定死的那个路径（路径不许改）')
report(JSON.stringify(pullCalls.at(-1)?.body)
  === JSON.stringify({ provider: 'openai_compatible', baseUrl: PULL_SOURCE, role: 'chat', apiKey: 'sk-live-x' }),
  '请求体是 { provider, baseUrl, apiKey?, role: "chat" }（字段不许改）')
report(pullCalls.filter((call) => call.path !== '/ai/me/providers/models').length === 0,
  '拉取只打这一个接口（不顺手写任何东西）')
okRun.api.pickModel('deepseek-chat')
report(okRun.api.modelForm.value.model === 'deepseek-chat', '点选候选 → 模型名被填入')
report(okRun.api.modelPullOpen.value === false, '点选候选 → 候选列表收起')

// —— 渲染：候选 / truncated / source / 空列表 四态（SSR 里点不了按钮，用 debugState 直灌）——
const pulled = await renderWithApi(mine, {
  pull: { models: [{ id: 'qwen-plus' }, { id: 'deepseek-chat' }, { id: 'gpt-4o-mini' }], truncated: true, source: PULL_SOURCE },
})
const pullButton = (pulled.html.match(/<button[^>]*class="dock-btn pull"[^>]*>[\s\S]*?<\/button>/) || [''])[0]
report(pullButton.includes('拉取模型') && pullButton.includes('type="button"'),
  '「拉取模型」按钮在模型名旁边，且 `type="button"`（表单里不写 type 会变成提交按钮 → 点一下直接保存）')
// ⚠️ 渲染出来的标签会带 scoped 的 `data-v-xxxx` 属性，所以判据写成 `[^>]*>` 而不是把标签写死
report(/<button class="dock-pull-item" type="button"[^>]*>qwen-plus<\/button>/.test(pulled.html),
  '候选是能点的按钮，文本就是模型 id')
report(pulled.html.includes('只列出前 3 个'), 'truncated 时说明「只列出前 N 个」，且 N 来自真实条数')
report(pulled.html.includes(PULL_SOURCE), '显示 source（服务端实际请求的 base_url）让用户核对地址对不对')
const plain = await renderWithApi(mine, { pull: { models: [{ id: 'qwen-plus' }], truncated: false, source: PULL_SOURCE } })
report(!plain.html.includes('只列出前'), '没被截断时**不说**「只列出前 N 个」（文案不能无脑挂上）')
const empty = await renderWithApi(mine, { pull: { models: [], truncated: false, source: '' } })
report(empty.html.includes('空列表') && empty.html.includes('手打模型名照样能保存'),
  '对方返回空列表时照实说，并提醒手打照样能保存（红线①：按钮不是必经步骤）')
const busy = await renderWithApi(mine, { pulling: true })
report(busy.html.includes('拉取中…') && busy.html.includes('正在向供应商要模型列表'),
  '「拉取中」两处文案都在（按钮 + 一行说明），不是静默等待')
report(busy.html.includes('这一步可以跳过'), '拉取中也提醒「这一步可以跳过」（是辅助能力，不是必经步骤）')
const pullListRule = (source.match(/\.dock-pull-list\s*\{[^}]*\}/) || [''])[0]
report(pullListRule.length > 0 && !/position\s*:\s*absolute/.test(pullListRule),
  '候选列表在**正常流**里（表单是 overflow-y:auto 的滚动区，absolute 会连同滚动一起被裁掉）')

// —— 拉取失败：现有值不变、不抛错、不碰已保存配置、也不踢下线 ——
const itemsBefore = JSON.stringify(mine.items)
const failRun = await renderWithApi(mine)
failRun.api.modelForm.value = {
  provider: 'openai_compatible', baseUrl: PULL_SOURCE, model: '手打的值', apiKey: 'sk-keep',
}
sessionExpired = 0
nextPull = { status: 404, payload: { code: 404, msg: '要找的内容不存在，可能已被删除' } }
let pullThrew = false
try {
  await failRun.api.pullModels()
} catch {
  pullThrew = true
}
report(!pullThrew, '拉取失败**不抛错**（调用方不用 try，主流程一点不受影响）')
report(JSON.stringify(failRun.api.modelForm.value)
  === JSON.stringify({ provider: 'openai_compatible', baseUrl: PULL_SOURCE, model: '手打的值', apiKey: 'sk-keep' }),
  '拉取失败**不改动表单里已有的任何值**（手打的那份还在）')
report(failRun.api.modelPull.value === null && failRun.api.modelPullOpen.value === false,
  '拉取失败不留下半份候选/展开态（避免「这是哪个地址的答案」说不清）')
report(failRun.api.modelPullError.value.includes('拉不到') && !/服务|坏了/.test(failRun.api.modelPullError.value),
  '404 的提示只说「拉不到」')
report(failRun.api.modelError.value === '' && failRun.api.modelNotice.value === '',
  '失败只写进「拉取」自己的位置，不冒充保存/自检的错误（也不占用法提示位）')
report(JSON.stringify(mine.items) === itemsBefore, '拉取失败不碰任何已保存配置')

// 供应商 401：照抄后端文案，且**不发**会话失效（拉列表不该把正在填表的用户踢下线）
nextPull = { status: 401, payload: { code: 401, msg: 'API Key 无效或已过期' } }
await failRun.api.pullModels()
report(failRun.api.modelPullError.value.includes('API Key 无效或已过期'), '供应商 401 的文案原样照抄')
report(sessionExpired === 0, '供应商 401 **不发** SESSION_EXPIRED（否则用户被清会话踢回登录页）')
// 正对照：证明计数器真的在工作 —— 否则上面那条断言永远是绿的（假绿比没有更糟）
nextList = { status: 401, payload: { code: 401, msg: '未登录' } }
try {
  await request('/ai/me/providers', { silent: true })
} catch {
  /* 预期抛错：要的只是它发事件 */
}
report(sessionExpired === 1, '正对照：普通请求的 401 照旧发 SESSION_EXPIRED（上面那条不是假绿）')

// —— 中间态：请求真的挂住时，状态就是「进行中」（不是靠文案自证）——
nextPull = { hang: true, status: 200, payload: { code: 0, data: { models: [], truncated: false, source: '' } } }
const hangRun = await renderWithApi(mine)
hangRun.api.modelForm.value = { provider: 'openai_compatible', baseUrl: PULL_SOURCE, model: '', apiKey: '' }
const hanging = hangRun.api.pullModels()
await new Promise((resolve) => { setTimeout(resolve, 0) })
report(hangRun.api.modelPulling.value === true, '拉取挂住时状态是「进行中」（按钮据此显示「拉取中…」）')
releasePull()
await hanging
report(hangRun.api.modelPulling.value === false, '拉取结束后「进行中」清掉（失败/成功都一样）')

// —— 地址没填：点得动 + 就地说明缺什么（不做成一颗点不动的死按钮）——
nextPull = { status: 200, payload: { code: 0, data: { models: [], truncated: false, source: '' } } }
pullCalls.length = 0
const blankRun = await renderWithApi(mine)
await blankRun.api.pullModels()
report(blankRun.api.modelPullError.value.includes('API 根地址') && pullCalls.length === 0,
  '地址没填时点得动、就地说明「还差：API 根地址」，且不发请求（与「保存」同一口径）')

// —— 不缓存 / 不落盘：候选与 apiKey 都不进任何本地存储 ——
report(Object.keys(mine.$state).every((key) => !/pull|modelList|candidates/i.test(key)),
  'store 里没有为「拉来的模型列表」留任何 state 字段（它随时会变，缓存只会误导）')
const stored = [...storage.values(), ...session.values()].map((value) => String(value)).join('\n')
report(!stored.includes('qwen-plus') && !stored.includes('sk-live-x'),
  '候选列表与 apiKey 都没写进 localStorage / sessionStorage（只有助手会话与 token 在那儿）')

await server.close()
if (failed > 0) {
  console.error(`\n助手浮层模型选择自检失败 ${failed} 条`)
  process.exit(1)
}
console.log('\n助手浮层模型选择自检全部通过')
