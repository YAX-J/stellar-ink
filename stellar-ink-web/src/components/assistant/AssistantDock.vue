<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useQaStream } from '@/composables/useQaStream'
import { useAgent } from '@/composables/useAgent'
import { verifyAnswer, verifyText } from '@/api/verification'
import QaAnswerBlock from '@/components/ai/QaAnswerBlock.vue'
import { useAuthStore } from '@/stores/auth'
import { useMyModelsStore, GLOBAL_ONLY_NOTE } from '@/stores/myModels'
import { pullFailureMessage, truncatedNote } from '@/utils/providerModels'

/**
 * 星笺助手（右下角浮层）：就全站已发布内容（文章 + 公开技术笔记）提问。
 *
 * ① **两个司职，各自独立**：`问答` 走 RAG（一次检索 + 一次生成，流式、引用先到）；
 *    `深挖` 走只读 Agent（多步检索，更慢也更费额度，非流式）。两者**互不打扰**：
 *    各自一个 composable 实例、各自一份会话历史（切回去还是原来那段对话）。
 *    为什么不混成一条：门槛一样但**代价差一个量级**，混起来会让「为什么这次这么慢」没有答案。
 * ② **它用独立的 `useQaStream()` / `useAgent()` 实例**，不碰 `stores/qa.js` 与 `stores/agent.js`
 *    —— 深读页的两个面板与浮层会同时存在，共用 store 时一边的 delta 会写进另一边的界面。
 * ③ **历史只是语境的参考**：带上去的最近几轮只用来理解「那它呢」指什么，服务端提示词明确
 *    要求不得当事实陈述、不得据它编号引用（与「记忆」同一口径）。
 * ④ **会话存 sessionStorage**（按司职分桶）：关掉标签页就没了，符合「助手不是账号资产」的定位。
 * ⑤ **问句靠右、回答靠左**（`layout="bubbles"`）：两条气泡宽度不同（窄/宽）而不是对称分栏
 *    —— 答案通常比问题长得多，对称会让一边拖成长条。
 * ⑥ **每轮结束自动做一次引用核验**（`/ai/agent/verify`）：它是零模型调用、不占配额的服务端
 *    确定性计算，却能回答「这次给的引用站不站得住」。⚠️ 文案只说「引用核验」，绝不说
 *    「答案已核实」——`ok` 只表示引用能对上原文；`checked=0` 是「没得比」不是「比过了没问题」。
 * ⑦ **状态取自顶层的 ref**（下面直接解构），模板里不要写 `qa.xxx.value`：模板只对**顶层**
 *    ref 自动解包，嵌套在对象里的 ref 得手写 `.value` 才能取到值 —— 那种写法能跑，但下次
 *    有人改动就会静默拿到 ref 对象。
 * ⑧ **「模型选择 + 就地添加模型」这一块的口径**（M12 的浮层入口，另有下面几条）：
 *    - 用**全局配置 store** `stores/myModels.js`，不为它抽 composable：它是全局配置而不是会话状态，
 *      浮层与深读页共用同一份是对的（`useQaStream` / `useAgent` 必须多实例，是因为它们各自持有会话）。
 *    - **只处理 `chat` 一个角色**。不列 `embedding` / `rerank`，也不给它们任何编辑入口：向量索引
 *      只有一份，换嵌入模型检索出来的是**错的**结果（不是差一点）。界面上用 store 现成的
 *      `GLOBAL_ONLY_NOTE` 说清这件事，否则用户会以为「功能不全」而不是「这是刻意的」。
 *    - **Key 只写不读**：服务端列表只回掩码，没有任何接口能读回明文，所以编辑已有配置时
 *      Key 输入框**留空 = 沿用已存密钥**（`myModels.save` 的注释写明了这条）。首次配置必须填。
 *    - **`base_url` 必须填「API 根」**，路径（`/chat/completions` 等）由服务端拼：填成完整端点会拼出
 *      `/rerank/rerank` 这类 404，而报出来的话是「模型名不存在」（项目里踩过），所以表单里明写。
 *    - **切换 = 「把我的对话模型设成它」（持久生效）**，不是「这一条消息用 X」：不给请求体加模型字段
 *      （那要改契约，本轮明确不做）。
 *    - **「没配」与「用全局」是两件事**（store 口径第 2 条）：没配时说「全局默认（站长配置）」，
 *      而不是留空 —— 留空用户不知道现在到底在用谁的模型。
 *    - **取数失败要如实说**（store 口径第 3 条）：说「没读到配置」，**不是**「你还没配」。
 *    - **一切都不许冒泡成「这次问答失败」**：模型选择是辅助信息，取数失败就不显示当前模型名，
 *      提问照常可用（与「辅助信息失败不得损伤主流程」同一条口径）。所以这里全程 `silent: true`、
 *      自己吞掉异常，绝不写进 `qaError` / `deepError`。
 * ⑨ **「从供应商拉取模型列表」是可选入口，不是必经步骤**（四条红线，每条都对应一类真实误读）：
 *    - **手打模型名一直可用**：供应商可能根本没有 `/models` 接口，也可能返回畸形数据。
 *      所以按钮不参与任何校验、不用 `disabled` 挡（字段没填时点得动，就地说明还差什么），
 *      拉到空列表时也照实说「对方返回了空列表，手打照样能保存」。
 *    - **候选列表只在内存里**：`modelPull` 是个 `ref`，关掉浮层就没了，
 *      **绝不落 localStorage / sessionStorage**（供应商随时会加/删模型，缓存只会误导）；
 *      `apiKey` 同样不写任何本地存储 —— 它只在 store 那一次 POST 的请求体里出现。
 *    - **接口还不存在时不许报成「服务坏了」**（后端这一版还没上）：失败文案由
 *      `utils/providerModels.js` 的 `pullFailureMessage()` 给，说「拉不到」+「手打照样能保存」；
 *      错误只写进 `modelPullError`（拉取这件事自己的位置），**绝不写进 `modelError`**
 *      （那是保存/自检的），更不写进 `qaError` / `deepError`，也**不动表单里已填的任何值**。
 *    - **失败也不许影响会话**：这条请求带 `ownErrors`，供应商的 401/403 不会被当成
 *      「本站登录失效」把用户清会话踢下线（正在填的表单不该因为拉列表而丢掉）。
 *    - 候选列表走**正常流**（见下面 `.dock-pull-list` 的注释）：它所在的 `.dock-form` 是
 *      `overflow-y:auto` 的滚动区，absolute 的后代会连同滚动一起被裁掉。
 */
const HISTORY_LIMIT = 6
const STORAGE_KEY = 'stellar-ink:assistant:session'

/** 两个司职。`hint` 要如实说清代价差别：界面不得让人以为深挖和问答一样便宜 */
const MODES = [
  { key: 'answer', label: '问答', hint: '一次检索 + 一次生成，最快，引用先于正文到' },
  { key: 'deep', label: '深挖', hint: '多步检索，更慢、更费额度，可能查得更全' },
]

/** 空态的示例问题：都取自站内真实内容，点一下就直接问（是入口，不是装饰） */
const SUGGESTIONS = [
  '重启后第一个请求为什么慢 5 秒？',
  'JWT 撤销列表为什么要放 Redis？',
  'Nacos 配置里的中文为什么会静默失效？',
]

/** 这一轮只做对话模型：`chat` 是问答与深挖共用的那个角色 */
const CHAT_ROLE = 'chat'

/** 协议取值必须是后端认的（`AiProviderSaveDTO.provider` 的正则只有这两个）；
 *  写成别的值会被参数校验拒掉 —— 所以这里不做「自由文本」，而是固定两项。 */
const PROVIDER_OPTIONS = [
  { key: 'openai_compatible', label: 'OpenAI 兼容' },
  { key: 'fake', label: 'Fake（离线自测）' },
]

const auth = useAuthStore()

/**
 * 模型配置是**全局配置 store**（不是会话状态），浮层直接用就行 —— 不抽 composable。
 * 每次开浮层重新取一次，否则在账号页刚改完配置、回到浮层还显示旧的（浮层是常驻组件）。
 */
const myModels = useMyModelsStore()

const {
  streaming,
  answer,
  question: qaQuestion,
  error: qaError,
  completed,
  refused,
  offline,
  interrupted,
  answerDone,
  askStream,
  abort: qaAbort,
  reset: qaReset,
} = useQaStream()

const {
  running: deepRunning,
  result: deepResult,
  question: deepQuestion,
  error: deepError,
  stopped: deepStopped,
  offline: deepOffline,
  budgetExhausted,
  ask: deepAsk,
  abort: deepAbort,
  reset: deepReset,
} = useAgent()

/**
 * ⚠️ 只为**离线渲染自检**存在的调试入口（`scripts/assistant-dock-selfcheck.mjs`，已进 `npm run check`）。
 *
 * 为什么需要它：浮层默认收起、下拉默认收起，而 SSR 里既没有点击也不跑 `onMounted` ——
 * 不给一个「直接渲染成展开态」的开关，这块新 UI 就只能靠肉眼，而 `vite build`
 * **不校验模板里的未定义标识符**（项目里因此漏过一次 `STREAM_PATH is not defined`）。
 *
 * 为什么它不影响生产：全站只有 `App.vue` 一处使用本组件、且没有绑 `ref`，也从不传这个 prop，
 * 所以它恒为 `null`、一个分支都不会走。
 */
const props = defineProps({ debugState: { type: Object, default: null } })

const open = ref(false)
/** 调试开关（见上面 `debugState` 的说明）：只读一次，默认 null（运行时一个分支都不走） */
const debugState = props.debugState
if (debugState?.open) open.value = true

const draft = ref('')
const inputRef = ref(null)
const mode = ref('answer')
/** 按司职分桶：`{ answer: [轮次], deep: [轮次] }` —— 切模式看到的是各自的对话 */
const boxes = ref({ answer: [], deep: [] })
/** 当前轮（尚未归档）的核验报告；归档时一起写进轮次里 */
const verification = ref(null)
const deepVerification = ref(null)
/** 深挖是同步等待（非流式），所以要把「已经等了多久」显式说出来，否则看起来像卡死 */
const waited = ref(0)
let ticker = null

/** 模型下拉是否展开（默认收起：浮层里这块是辅助信息，不该一进来就占地方） */
const modelOpen = ref(false)
/** 就地添加模型的表单是否展开 */
const modelFormOpen = ref(false)
if (debugState?.modelOpen) modelOpen.value = true
if (debugState?.modelFormOpen) modelFormOpen.value = true
/** 表单字段。`provider` 默认 openai_compatible：空配置报错比猜错协议好 */
const modelForm = ref({ provider: 'openai_compatible', baseUrl: '', model: '', apiKey: '' })
/** 「测试连接」的结论（按 role 存，store 里那份是给账号页用的，这里只是照镜子） */
const modelCheck = ref(null)
/** 就地操作**失败**的原因（保存 / 自检 / 改回全局三种都会写这里） */
const modelError = ref('')
/** 就地操作**不是失败但必须说清**的提示（例如「自检测的是已保存的那份，先保存」） */
const modelNotice = ref('')
/**
 * 从供应商拉来的候选模型 —— **只在内存里**（`{ models, truncated, source }`，见文件头 ⑨）。
 * 不落任何存储：供应商随时会加/删模型，缓存只会让用户对着一个不存在的模型名填半天。
 */
const modelPull = ref(null)
/** 候选列表是否展开：点选一个、重拉、改地址、或再点一次「收起候选」都会收起 */
const modelPullOpen = ref(false)
/** 拉到一半（按钮文案据此变「拉取中…」，也是唯一会禁用它的状态） */
const modelPulling = ref(false)
/** **拉取这件事自己的**失败原因：与 `modelError`（保存/自检）分开，绝不影响它们 */
const modelPullError = ref('')
/** 候选列表的 DOM（拉完后把它滚进可视区：表单本身是限高滚动区） */
const pullListRef = ref(null)

const running = computed(() => streaming.value || deepRunning.value)
const currentTurns = computed(() => boxes.value[mode.value] || [])

/** 我自己的 chat 配置；没有该键 = 这个角色用站长那份全局配置 */
const chatConfig = computed(() => myModels.items[CHAT_ROLE] || null)

/**
 * 当前对话用的是哪个模型。
 *
 * ⚠️ 三种状态必须分开（少一个都会被误读）：
 * ① 取数失败 → **不显示模型名**，只说「没读到配置」——显示成「全局默认」等于替服务端说了句
 *    它没说过的话（用户会以为自己那份配置丢了）；
 * ② 我配了 → 显示模型名（+ 供应商）；
 * ③ 没配 → 「全局默认（站长配置）」，而不是留空。
 */
const modelLabel = computed(() => {
  if (myModels.failed) return '没读到配置'
  const config = chatConfig.value
  if (!config) return '全局默认（站长配置）'
  return config.model || config.displayName || '我配的模型'
})

/** 模型名后面那句供应商：只在真配了模型时才有意义（全局默认不知道站长填了什么） */
const modelProvider = computed(() => {
  if (myModels.failed || !chatConfig.value) return ''
  const key = chatConfig.value.provider
  return (PROVIDER_OPTIONS.find((item) => item.key === key) || {}).label || key || ''
})

/** 校验只做「还差什么」，不靠禁用按钮挡：禁用按钮点不动又不说明原因，看起来像坏了 */
function modelBlockers() {
  const missing = []
  if (!modelForm.value.baseUrl.trim()) missing.push('API 根地址')
  if (!modelForm.value.model.trim()) missing.push('模型名')
  // 已有配置时留空 = 沿用已存密钥；**首次必须填**（后端也会拒，这里提前说清）
  if (!chatConfig.value?.apiKeyConfigured && !modelForm.value.apiKey.trim()) missing.push('API Key')
  return missing
}

/** 展开表单：把已存配置填进去，Key 一律留空（服务端只回掩码，读不回明文） */
function toggleModelForm() {
  modelError.value = ''
  modelNotice.value = ''
  modelCheck.value = null
  if (!modelFormOpen.value) {
    const config = chatConfig.value
    modelForm.value = {
      provider: config?.provider || 'openai_compatible',
      baseUrl: config?.baseUrl || '',
      model: config?.model || '',
      apiKey: '',
    }
    // 上一次开表单时拉来的候选**跟着作废**：地址可能已经被改回去，留着就是误导
    resetPull()
  }
  modelFormOpen.value = !modelFormOpen.value
}

/** 清掉拉取这件事的全部状态（候选、展开态、失败原因）；**不碰** modelForm 里用户填的值 */
function resetPull() {
  modelPull.value = null
  modelPullOpen.value = false
  modelPullError.value = ''
}

/**
 * 「拉取模型」：把供应商的模型列表拉成候选，点一下填进模型名。
 *
 * ⚠️ 它是**可选的辅助入口**（文件头 ⑨）：
 * ① 不用 `disabled` 挡校验：地址没填时点得动，就地说明还差什么（与「保存」同一口径 ——
 *    禁用按钮点不动又不说明原因，看起来就是坏了）；
 * ② 失败**只**写 `modelPullError`，不动 `modelForm` 任何字段、不碰 store 里那份已保存配置、
 *    不抛出去（拉不到不影响保存与提问，更不该冒泡成「这次问答失败」）；
 * ③ 拉之前先把上一份候选清掉：换地址后还显示旧结果会让「source 那行」说谎。
 */
async function pullModels() {
  if (modelPulling.value) return
  const baseUrl = modelForm.value.baseUrl.trim()
  modelPullError.value = ''
  modelPull.value = null
  modelPullOpen.value = false
  if (!baseUrl) {
    // 「还差什么」就地说明，而不是让按钮看起来没反应
    modelPullError.value = '还差：API 根地址（拉列表要拿它去问供应商）'
    return
  }
  modelPulling.value = true
  try {
    modelPull.value = await myModels.fetchProviderModels({
      provider: modelForm.value.provider,
      baseUrl,
      // 留空 = 用服务端已保存的密钥（store 干脆不传这个字段）
      apiKey: modelForm.value.apiKey.trim(),
    })
    modelPullOpen.value = true
    // 表单是限高滚动区：把候选滚进可视区，否则小屏上「点了没反应」（列表在滚动区外面）
    await nextTick()
    pullListRef.value?.scrollIntoView?.({ block: 'nearest' })
  } catch (error) {
    modelPullError.value = pullFailureMessage(error)
  } finally {
    modelPulling.value = false
  }
}

/** 点选候选：**填入模型名 + 收起候选列表**（选完了就没有理由再占着表单的地方） */
function pickModel(id) {
  modelForm.value.model = String(id)
  modelPullOpen.value = false
}

/**
 * 地址一改，上次拉来的候选就**作废**：它是「另一个地址」的答案。
 * `source` 那行虽然写了上次问的是谁，但没人会去逐字比对 —— 与其等人填错，不如直接清掉。
 */
watch(() => modelForm.value.baseUrl, () => resetPull())

/* ⚠️ 与 `debugState.open` 同一类：**只为离线自检存在**，生产恒为 null（`App.vue` 从不传这个 prop），
   所以下面一个分支都不会走。
   为什么需要它：SSR 里既没有点击、也不跑事件处理器，而「拉取成功 → 候选可点选并填入」、
   「拉取失败不改动表单里已有的值」这两条，**正则扫源码只能证明代码长得像**，证明不了它做对了。
   `pull` / `pullError` / `pulling` 是三个直灌口（SSR 里点不了按钮，靠它们渲染出三态）；
   `expose` 把处理器与它们改的那几个 ref 交出去，让自检真调一次。 */
if (debugState?.pull) {
  modelPull.value = debugState.pull
  modelPullOpen.value = true
}
if (debugState?.pullError) modelPullError.value = debugState.pullError
if (debugState?.pulling) modelPulling.value = true
if (debugState?.expose) {
  debugState.expose({
    pullModels, pickModel, modelForm, modelPull, modelPullOpen, modelPulling, modelPullError,
    modelError, modelNotice,
  })
}

/**
 * 下拉里的「＋ 添加模型」：开表单的同时**收起下拉**。
 *
 * 两者都是正常流里的整块内容（模型行下面是下拉、再下面是表单），同时出现会把面板顶得很长、
 * 而且下拉会被表单挤到看不见的地方；而「像 Harness 一样就地改」的前提是表单能完整看见。
 */
function startAddModel() {
  modelOpen.value = false
  if (!modelFormOpen.value) toggleModelForm()
}

/**
 * 选「全局默认」：删掉我那份，该角色立刻回落站长配置。
 *
 * ⚠️ 这里必须**先确认一次**：删除是真的删（连同加密后的 Key），而 Key 只写不读 ——
 * 服务端只回掩码，误点一下就再也拿不回来，只能重填一遍。
 * 账号页对同一件事也发了 `window.confirm`，两边口径保持一致。
 *
 * ⚠️ 失败原因写进 `modelError`，而它的显示位置**在表单之外**（见模板里的 `.dock-model-status`）：
 * 下拉一收就看不见的错误等于没说 —— 用户只会觉得「点了没反应」。
 */
async function useGlobalModel() {
  modelOpen.value = false
  modelError.value = ''
  modelNotice.value = ''
  if (!chatConfig.value) return
  if (!window.confirm('改回全局配置？你自己那份（含已保存的密钥）会被删除，密钥必须重填才能恢复。')) {
    return
  }
  try {
    await myModels.remove(CHAT_ROLE)
  } catch (error) {
    // 删除失败时 store **不改本地**（那条仍在），这里只如实把原因说出来
    modelError.value = `改回全局失败：${error.message}`
  }
}

/** 选我自己的配置：只是收起下拉。**切换是「设为我的对话模型」，持久生效** —— 不改请求体 */
function useMyModel() {
  modelOpen.value = false
  modelError.value = ''
  modelNotice.value = ''
}

/**
 * 测试连接：打的是**已保存**的那份配置（服务端只接受 role，不接受表单里的草稿）。
 *
 * ⚠️ 它的结论是 `scope: tcp_only` —— 只证明地址连得上，**不代表模型能用**。
 * 界面必须照抄这个限定，否则用户会拿「地址可达」当成「配置没问题」。
 */
async function checkModel() {
  modelError.value = ''
  modelNotice.value = ''
  modelCheck.value = null
  if (!chatConfig.value) {
    // 没保存过时服务端会回 404 语义的错误，这里先说清怎么做，省一次往返。
    // 这是**用法提示**不是失败，所以走 modelNotice（用错误色说一句正常的操作顺序会吓人）。
    modelNotice.value = '先「保存」再测试连接：自检测的是服务端已存的那份配置。'
    return
  }
  try {
    modelCheck.value = await myModels.check(CHAT_ROLE)
  } catch (error) {
    modelError.value = error.message
  }
}

/**
 * 保存：**写成功就就地生效**（store 在 POST 200 后直接改本地，不再多发一次列表请求），
 * 失败**不改本地**、表单保持打开（原因留在按钮上方）——
 * 乐观更新、或者「写成功但刷新失败就报保存失败」，这两种都被踩过。
 */
async function saveModel() {
  // 提交中直接返回：按钮虽然禁用了，但表单回车仍会触发 submit，重复 POST 会白写一次
  if (myModels.savingRole === CHAT_ROLE) return
  const missing = modelBlockers()
  if (missing.length) {
    modelError.value = `还差：${missing.join('、')}`
    return
  }
  modelError.value = ''
  modelNotice.value = ''
  modelCheck.value = null
  try {
    await myModels.save(CHAT_ROLE, {
      displayName: modelForm.value.model.trim(),
      provider: modelForm.value.provider,
      baseUrl: modelForm.value.baseUrl.trim(),
      model: modelForm.value.model.trim(),
      apiKey: modelForm.value.apiKey.trim(),
    })
    modelFormOpen.value = false
    modelForm.value = { provider: 'openai_compatible', baseUrl: '', model: '', apiKey: '' }
  } catch {
    // 原因已在 myModels.formError（后端那句可操作提示原样留着），模板里就近显示
  }
}

function toggleModelMenu() {
  modelOpen.value = !modelOpen.value
  if (modelOpen.value) {
    modelFormOpen.value = false
    // 不在这里清 modelError：上一个动作的失败原因要留着让用户看见（清掉等于没说）
    modelNotice.value = ''
  }
}

/** 开浮层时取一次我的模型配置（只有登录用户才有这份配置）。 */
function loadMyModels() {
  if (!auth.isLoggedIn) return
  // 模型选择是**辅助信息**：取不到就不显示当前模型名，**绝不挡住提问**
  //（store 全程 `silent: true`，不会弹全局 toast；失败只记在 store.failed 里）
  myModels.load().catch(() => {})
}

/** 面板每次展开都重取一次：浮层是常驻组件，不重取就会一直显示账号页改配置之前的旧值 */
watch(open, (value) => {
  if (value) loadMyModels()
})

onMounted(() => {
  loadMyModels()
  try {
    const saved = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || 'null')
    if (Array.isArray(saved)) {
      // 旧格式（只有一条会话的数组）当作文问答那桶，不丢用户已有的对话
      boxes.value = { answer: saved.slice(-HISTORY_LIMIT), deep: [] }
    } else if (saved && typeof saved === 'object') {
      boxes.value = {
        answer: Array.isArray(saved.answer) ? saved.answer.slice(-HISTORY_LIMIT) : [],
        deep: Array.isArray(saved.deep) ? saved.deep.slice(-HISTORY_LIMIT) : [],
      }
    }
  } catch {
    // 存的东西坏了就当没有：助手会话不是重要数据，不值得为它报错
    boxes.value = { answer: [], deep: [] }
  }
})

watch(
  boxes,
  (value) => {
    try {
      sessionStorage.setItem(
        STORAGE_KEY,
        JSON.stringify({
          answer: value.answer.slice(-HISTORY_LIMIT),
          deep: value.deep.slice(-HISTORY_LIMIT),
        }),
      )
    } catch {
      // 隐私模式 / 配额满：不落盘也要能用（当前会话内的多轮不受影响）
    }
  },
  { deep: true },
)

onUnmounted(() => {
  if (ticker) clearInterval(ticker)
})

/** 带进提示词的历史：只有问答那条链有 history（Agent 端点没有这个字段）；只取有正文的轮 */
const history = computed(() =>
  (boxes.value.answer || [])
    .filter((turn) => turn.answer?.answer)
    .map((turn) => ({ question: turn.question, answer: turn.answer.answer })),
)

function archiveFinishedTurn() {
  if (completed.value && answer.value?.answer && qaQuestion.value) {
    boxes.value.answer = [...boxes.value.answer, {
      question: qaQuestion.value,
      answer: answer.value,
      verification: verification.value,
    }].slice(-HISTORY_LIMIT)
  }
}

function startTicker() {
  waited.value = 0
  if (ticker) clearInterval(ticker)
  ticker = setInterval(() => { waited.value += 1 }, 1000)
}

function stopTicker() {
  if (ticker) clearInterval(ticker)
  ticker = null
}

/**
 * 核验是**辅助信息**：它失败就不显示，绝不冒泡成「这次问答失败」
 * —— 与项目里「辅助信息失败不得损伤主流程」是同一条口径。
 */
async function safeVerify(run) {
  if (!run?.answer) return null
  try {
    return await verifyAnswer({ answer: run.answer, citations: run.citations || [] })
  } catch {
    return null
  }
}

/**
 * @param preset 示例问题（空态的 chip 直接传文案）；不传则取输入框内容。
 *   注意模板里必须写成 `@click="send()"`：写 `@click="send"` 会把事件对象当问题发出去。
 */
async function send(preset) {
  const text = String(preset ?? draft.value).trim()
  if (!text) {
    // 空输入时把焦点给输入框，而不是让按钮看起来"点了没反应"
    inputRef.value?.focus()
    return
  }
  if (running.value || !auth.isLoggedIn) return
  draft.value = ''
  if (mode.value === 'deep') {
    await sendDeep(text)
    return
  }
  // 先把上一轮归档，它的正文才会进这一轮的上下文（`history` 依赖 boxes）
  archiveFinishedTurn()
  qaReset()
  verification.value = null
  try {
    await askStream(text, { history: history.value })
    verification.value = await safeVerify(answer.value)
  } catch {
    // 失败已经在 `qaError` 里如实显示（含 401/429 的专门处理）
  }
}

async function sendDeep(text) {
  deepReset()
  deepVerification.value = null
  startTicker()
  try {
    const result = await deepAsk(text, { depth: 'quick' })
    const run = toAnswerShape(result, { stopped: !result })
    deepVerification.value = await safeVerify(run)
    if (result || deepStopped.value) {
      // 用户自己停的、或预算用尽：**不是失败**，也要留一轮，否则"查过什么"就丢了
      boxes.value.deep = [...boxes.value.deep, {
        question: text,
        answer: run,
        verification: deepVerification.value,
      }].slice(-HISTORY_LIMIT)
    }
  } catch {
    // 真失败：错误显示在 `deepError` 里
  } finally {
    stopTicker()
  }
}

/**
 * 把 Agent 的响应适配成 `QaAnswerBlock` 认的形状。
 *
 * ⚠️ 两条链的字段名**不一样**：Agent 是 `usageModel` / `latencyMs`，而答案块读的是
 * `usage.model` / `usage.latencyMs`。不转换的话「离线自测」标记会**静默漏判**
 * （`fake` 模型会被当成真实模型展示），所以这层适配必须留着。
 */
function toAnswerShape(result, { stopped = false } = {}) {
  if (!result) {
    return {
      answer: stopped ? '已停止等待。多步检索的中间结果留在调用账里，可以重试。' : '',
      citations: [],
      doneReason: 'stopped',
      usage: { model: '', latencyMs: 0 },
      steps: [],
      toolCalls: 0,
    }
  }
  return {
    answer: result.answer,
    citations: result.citations || [],
    doneReason: result.doneReason,
    // 有正文才算"证据足够"：只有引用没有正文是"预算用尽"的形态，不是拒答
    evidenceSufficient: Boolean(result.answer),
    usage: { model: result.usageModel || '', latencyMs: result.latencyMs || 0 },
    steps: result.steps || [],
    toolCalls: result.toolCalls || 0,
  }
}

/** 深挖的"预算用尽"不是失败：要如实说明「查到了这些，但没能在预算内收敛」 */
const deepNotice = computed(() => {
  if (mode.value !== 'deep') return ''
  if (deepRunning.value) return `正在多步检索… 已用 ${waited.value}s（深挖比问答慢，且会多花额度）`
  if (budgetExhausted.value) return '预算用尽：查到了下面的引用，但没能在预算内收敛成完整回答。'
  if (deepStopped.value) {
    return '已停止等待。服务端只在步与步之间检查中断，所以是「不再等它」而不是「已经取消」。'
  }
  if (deepError.value) return deepError.value
  return ''
})

function clearAll() {
  boxes.value = { answer: [], deep: [] }
  verification.value = null
  deepVerification.value = null
  qaReset()
  deepReset()
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // 同上：清不掉也不影响当前会话
  }
}
</script>

<template>
  <div class="dock">
    <!-- 入口：右下角圆形图标。不占一级导航（那 6 项是内容维度），也不留文字挡内容 -->
    <button
      v-if="!open"
      class="dock-bubble" type="button" aria-label="打开星笺助手" title="星笺助手"
      @click="open = true"
    >
      <span aria-hidden="true">✦</span>
    </button>

    <section v-else class="dock-panel" aria-label="星笺助手">
      <header class="dock-head">
        <h3>星笺助手</h3>
        <span class="dock-spacer"></span>
        <button
          v-if="currentTurns.length || answer || deepResult"
          class="dock-icon" type="button" @click="clearAll"
        >清空</button>
        <button class="dock-icon" type="button" aria-label="收起助手" @click="open = false">收起</button>
      </header>

      <!-- 司职切换：两个入口的代价差一个量级，必须显式（两边的会话也靠它互不打扰） -->
      <div v-if="auth.isLoggedIn" class="dock-modes" role="tablist" aria-label="选择助手模式">
        <button
          v-for="item in MODES" :key="item.key"
          class="dock-mode" :class="{ on: mode === item.key }"
          type="button" role="tab" :aria-selected="mode === item.key" :title="item.hint"
          @click="mode = item.key"
        >{{ item.label }}</button>
        <span class="dock-mode-hint">{{ MODES.find((m) => m.key === mode)?.hint }}</span>
      </div>

      <!-- 模型：只做`chat`一个角色 —— 问答与深挖都用它，所以放在司职切换之下、正文之上。
           它跟着下拉与表单一起在**正常流**里占位置：面板高度变了也只是把正文压矮一点，
           不会撑破面板的 max-height，也不会把正文顶掉。
           ⚠️ 浮层是常驻组件（收起时只是 v-else 不渲染面板），所以**每次展开面板都重取一次**
           配置（见 `watch(open)`），否则在账号页刚改完再打开浮层会显示旧值。 -->
      <div v-if="auth.isLoggedIn" class="dock-model">
        <span class="dock-model-label">模型</span>
        <button
          class="dock-model-pick" type="button"
          :aria-expanded="modelOpen" aria-haspopup="true"
          :title="chatConfig ? '当前用我自己的对话模型，点这里切换或修改' : '当前用站长的全局模型，点这里换成我自己的'"
          @click="toggleModelMenu"
        >
          <span class="dock-model-name" :class="{ warn: myModels.failed }">{{ modelLabel }}</span>
          <span v-if="modelProvider" class="dock-model-provider">{{ modelProvider }}</span>
          <span v-if="chatConfig" class="dock-model-chip">我配的</span>
          <span class="dock-caret" aria-hidden="true">▾</span>
        </button>
      </div>

      <!-- 就地表单：376px 宽要放得下，所以标签与输入同宽、按钮一行两个 -->
      <form
        v-if="auth.isLoggedIn && modelFormOpen" class="dock-form"
        @submit.prevent="saveModel"
      >
        <div class="dock-form-grid">
          <label class="dock-field">
            <span>供应商 / 协议</span>
            <select v-model="modelForm.provider">
              <option v-for="item in PROVIDER_OPTIONS" :key="item.key" :value="item.key">
                {{ item.label }}
              </option>
            </select>
          </label>
          <!-- 模型名 + 「拉取模型」。
               ⚠️ 这一行是 `wide`（占满整行）：拉取按钮要和输入框并排，半行放不下 ——
               挤窄了输入框，红线①「手打模型名仍然可用」就名存实亡（模型 id 通常十几个字符）。
               ⚠️ 按钮必须写 `type="button"`：表单里不写 type 的按钮默认是 submit，点一下会直接保存。 -->
          <label class="dock-field wide">
            <span>模型名</span>
            <span class="dock-field-row">
              <input v-model="modelForm.model" maxlength="128" placeholder="照服务方文档填，如 qwen-plus">
              <button
                class="dock-btn pull" type="button"
                :disabled="modelPulling"
                title="从供应商拉一份模型列表来挑（不是必须的：手打模型名一样能保存）"
                @click="pullModels"
              >{{ modelPulling ? '拉取中…' : '拉取模型' }}</button>
            </span>
          </label>
          <label class="dock-field wide">
            <span>API 根地址 base_url</span>
            <input
              v-model="modelForm.baseUrl" maxlength="255"
              placeholder="https://<host>/v1"
            >
          </label>
          <label class="dock-field wide">
            <span>API Key{{ chatConfig?.apiKeyConfigured ? '（留空 = 沿用已存密钥）' : '' }}</span>
            <input
              v-model="modelForm.apiKey" type="password" autocomplete="off" maxlength="512"
              :placeholder="chatConfig?.apiKeyConfigured ? (chatConfig.apiKeyMask || '留空沿用已存') : '首次配置必须填写'"
            >
          </label>
        </div>

        <!-- 拉取模型的三态（成功 / 拉取中 / 失败）+ 候选列表。
             ⚠️ 全部在**正常流**里，且**紧跟在模型名那一行下面**：这块内容的爹 `.dock-form`
             是 `overflow-y:auto` 的滚动区，absolute 的后代会跟着滚动一起被裁掉（正是模型下拉
             上一轮踩的那个坑）。列表自己再限一次高，免得几十个模型把表单撑成一整屏。
             ⚠️ 文本只讲**拉取这一件事**：失败也绝不牵扯保存与提问（它们不受影响），
             更不写进 modelError / qaError —— 那两处一出现，用户就会以为「功能坏了」。 -->
        <div v-if="modelPulling || modelPullError || modelPull" class="dock-pull">
          <p v-if="modelPulling" class="dock-pull-line">
            正在向供应商要模型列表…（这一步可以跳过，手打模型名一样能保存）
          </p>
          <p v-else-if="modelPullError" class="dock-pull-line bad">{{ modelPullError }}</p>
          <template v-else>
            <div class="dock-pull-head">
              <p class="dock-pull-line">
                拉到 {{ modelPull.models.length }} 个模型<template v-if="modelPull.truncated">（{{ truncatedNote(modelPull.models.length) }}）</template>
              </p>
              <button
                v-if="modelPull.models.length"
                class="dock-pull-toggle" type="button"
                :aria-expanded="modelPullOpen"
                @click="modelPullOpen = !modelPullOpen"
              >{{ modelPullOpen ? '收起候选' : '再看候选' }}</button>
            </div>
            <!-- source = 服务端**实际请求**的 base_url（不含密钥），让人核对地址填得对不对 -->
            <p v-if="modelPull.source" class="dock-pull-line dim">
              实际请求 <code>{{ modelPull.source }}</code> —— 核对一下地址填得对不对
            </p>
            <p v-if="!modelPull.models.length" class="dock-pull-line dim">
              对方返回了空列表（有些供应商没有 <code>/models</code> 接口，或这个 Key 无权列模型），
              手打模型名照样能保存。
            </p>
            <ul v-if="modelPullOpen && modelPull.models.length" ref="pullListRef" class="dock-pull-list" aria-label="候选模型">
              <li v-for="item in modelPull.models" :key="item.id">
                <button class="dock-pull-item" type="button" @click="pickModel(item.id)">{{ item.id }}</button>
              </li>
            </ul>
          </template>
        </div>

        <!-- 两条必须说清的事，各占一句：
             ① 地址填到 API 根为止（填成完整端点会拼出 /rerank/rerank 这类 404，
                而报出来的话是「模型名不存在」—— 项目里踩过）；
             ② 只有 chat 能个人配置，嵌入/重排由站长统一配（换模型检索结果是**错的**，不是差一点）。 -->
        <p class="dock-form-note">
          地址只填到 <code>/v1</code> 这类 <b>API 根</b>，不要带 <code>/chat/completions</code> ——
          路径由服务端拼，填成完整端点会拼出重复路径而报「模型名不存在」。
        </p>
        <p class="dock-form-note">{{ GLOBAL_ONLY_NOTE }}</p>

        <p v-if="modelCheck" class="dock-check" :class="{ bad: !modelCheck.ok }">
          {{ modelCheck.ok ? '✓' : '✕' }} {{ modelCheck.message }}
          <span class="dock-check-scope">（自检只到 TCP：{{ modelCheck.scope }}，不代表模型与密钥可用）</span>
        </p>
        <p v-if="modelError" class="dock-err inline">{{ modelError }}</p>
        <p v-else-if="myModels.formError" class="dock-err inline">{{ myModels.formError }}</p>
        <p v-else-if="modelNotice" class="dock-notice inline">{{ modelNotice }}</p>

        <div class="dock-form-actions">
          <button
            class="dock-btn" type="button"
            :disabled="myModels.savingRole === CHAT_ROLE"
            @click="checkModel"
          >{{ myModels.savingRole === CHAT_ROLE ? '自检中…' : '测试连接' }}</button>
          <button
            class="dock-btn primary" type="submit"
            :disabled="myModels.savingRole === CHAT_ROLE"
          >{{ myModels.savingRole === CHAT_ROLE ? '保存中…' : '保存' }}</button>
          <button class="dock-btn ghost" type="button" @click="toggleModelForm">取消</button>
        </div>
        <p class="dock-form-note">
          「测试连接」测的是服务端「已保存」的那份配置，所以先保存再测试。
        </p>
      </form>

      <div v-if="auth.isLoggedIn && modelOpen" class="dock-model-menu">
        <button
          class="dock-menu-item" :class="{ on: !chatConfig }" type="button"
          @click="useGlobalModel"
        >
          <span>全局默认（站长配置）</span>
          <span v-if="!chatConfig" class="dock-menu-mark" aria-hidden="true">✓</span>
        </button>
        <!-- 有「我配的」那条才列出来；选中它只是收起下拉 —— 它本来就是当前生效的那份 -->
        <button
          v-if="chatConfig" class="dock-menu-item on" type="button" @click="useMyModel"
        >
          <span class="dock-menu-main">
            <b>{{ chatConfig.model || chatConfig.displayName }}</b>
            <em>{{ chatConfig.baseUrl }}<template v-if="chatConfig.apiKeyMask"> · {{ chatConfig.apiKeyMask }}</template></em>
          </span>
          <span class="dock-menu-mark" aria-hidden="true">✓</span>
        </button>
        <p v-if="myModels.failed" class="dock-menu-note">
          没读到配置：这份列表可能不完整，不代表你没配过。
        </p>
        <!-- 一句话说清「切换」到底是切什么：它是**设为我的对话模型（持久生效）**，
             不是「这一条消息用 X」—— 后者要改请求契约，本轮明确不做。 -->
        <p class="dock-menu-hint">
          {{ chatConfig ? '当前：我配的这份（持久生效，之后每轮都用它）' : '当前：站长的全局配置' }}
        </p>
        <button class="dock-menu-add" type="button" @click="startAddModel">＋ 添加模型</button>
      </div>

      <!-- 就地操作的结果：**放在表单之外**，这样「改回全局失败」这类原因在下拉收起后仍然看得见
           （收起来才显示的错误等于没说，用户只会觉得「点了没反应」） -->
      <p v-if="auth.isLoggedIn && !modelFormOpen && (modelError || modelNotice)"
        class="dock-model-status" :class="{ bad: modelError }"
      >{{ modelError || modelNotice }}</p>

      <div class="dock-body">
        <p v-if="!auth.isLoggedIn" class="dock-login">
          提问需要登录（答案要花算力，也要能按人计费）。
          <RouterLink class="dock-link" :to="{ name: 'login', query: { redirect: $route.fullPath } }">
            去登录
          </RouterLink>
        </p>

        <template v-else>
          <!-- 空态：星形锚点 + 可直接点的示例问题（一片灰字会让面板显得很空） -->
          <div
            v-if="!currentTurns.length && !answer && !streaming && !deepResult && !deepRunning"
            class="dock-empty"
          >
            <div class="dock-star" aria-hidden="true">✦</div>
            <p class="dock-poem">夜空里有许多坐标</p>
            <p class="dock-sub">答案只依据站内文章与技术笔记，并给出引用</p>
            <div class="dock-chips">
              <button
                v-for="item in SUGGESTIONS" :key="item"
                class="dock-chip" type="button" @click="send(item)"
              >{{ item }}</button>
            </div>
          </div>

          <!-- 历史轮（当前司职那一桶）：只读展示，引用仍可点回原文 -->
          <article v-for="(turn, index) in currentTurns" :key="`${mode}-${index}`" class="dock-turn">
            <QaAnswerBlock
              layout="bubbles"
              compact-citations
              :question="turn.question"
              :answer="turn.answer"
              :offline="turn.answer?.usage?.model === 'fake'"
            />
            <p v-if="turn.answer?.steps?.length" class="dock-steps">
              深挖 · {{ turn.answer.steps.length }} 步 · {{ turn.answer.toolCalls }} 次工具调用
            </p>
            <p
              v-if="turn.verification" class="dock-verify"
              :class="turn.verification.verdict"
            >{{ verifyText(turn.verification) }}</p>
          </article>

          <!-- 当前轮（问答）：三态提示与流式光标都在这里 -->
          <article v-if="answer || streaming" class="dock-turn">
            <QaAnswerBlock
              layout="bubbles"
              compact-citations
              :question="qaQuestion"
              :answer="answer"
              :streaming="streaming"
              :refused="refused"
              :offline="offline"
              :interrupted="interrupted"
              :answer-done="answerDone"
              empty-hint="这次没有引用可给：站内确实没有相关段落。"
            />
            <p v-if="verification" class="dock-verify" :class="verification.verdict">
              {{ verifyText(verification) }}
            </p>
          </article>

          <!-- 当前轮（深挖）：非流式，等的时候由下面的 deepNotice 说明进度 -->
          <article v-if="deepResult || deepRunning" class="dock-turn">
            <QaAnswerBlock
              layout="bubbles"
              compact-citations
              :question="deepQuestion"
              :answer="toAnswerShape(deepResult)"
              :streaming="deepRunning"
              :offline="deepOffline"
              empty-hint="深挖没有查到可引用的段落。"
            />
            <p v-if="deepVerification" class="dock-verify" :class="deepVerification.verdict">
              {{ verifyText(deepVerification) }}
            </p>
          </article>

          <p v-if="deepNotice" class="dock-notice">{{ deepNotice }}</p>
          <p v-if="qaError" class="dock-err">{{ qaError }}</p>
        </template>
      </div>

      <footer v-if="auth.isLoggedIn" class="dock-foot">
        <input
          ref="inputRef" v-model="draft" maxlength="500" type="text"
          :placeholder="mode === 'deep' ? '深挖：多步检索，更慢也更费额度…' : '写下一个问题…'"
          @keydown.enter="send()"
        >
        <button v-if="!running" class="btn btn-primary dock-send" type="button" @click="send()">
          {{ mode === 'deep' ? '深挖' : '提问' }}
        </button>
        <button
          v-else class="btn btn-ghost dock-send" type="button"
          @click="mode === 'deep' ? deepAbort() : qaAbort()"
        >停止</button>
      </footer>
    </section>
  </div>
</template>

<style scoped>
/* 浮层挂件：z-index 高于顶栏(50)与其下拉菜单(60)，低于 toast(200) */
.dock{position:fixed; right:22px; bottom:22px; z-index:70; display:flex; flex-direction:column;
  align-items:flex-end}
/* 收起态：44px 圆形图标（毛玻璃与顶栏下拉同族） */
.dock-bubble{width:44px; height:44px; display:grid; place-items:center; cursor:pointer; font:inherit;
  padding:0; border:1px solid var(--line); border-radius:var(--r-pill);
  background:color-mix(in srgb, var(--bg-2) 86%, transparent); backdrop-filter:blur(18px);
  color:var(--primary); font-size:17px;
  box-shadow:0 12px 34px color-mix(in srgb, var(--bg) 70%, transparent);
  transition:transform .3s var(--ease-spring), border-color .25s var(--ease-standard)}
.dock-bubble:hover{transform:translateY(-3px); border-color:var(--primary)}
/* 展开态：与 Toast / 顶栏下拉同一套浮件语言（半透明 + 毛玻璃 + --r-md）。 */
/* 展开态：与 Toast / 顶栏下拉同一套浮件语言（半透明 + 毛玻璃 + --r-md）。
   ⚠️ 面板是 `overflow:hidden`，所以**任何 absolute 的后代只要跑出面板就没了**：
   模型下拉曾经因此整块不可见（见下面 `.dock-model-menu` 的注释）。
   `position:relative` 留着是为了让这类错误仍然以「面板为基准」，而不是跑成视口定位 ——
   但正确做法是别在这里用 absolute。 */
.dock-panel{position:relative; width:376px; max-width:calc(100vw - 44px); max-height:calc(100vh - 110px);
  display:flex; flex-direction:column; overflow:hidden;
  border:1px solid var(--line); border-radius:var(--r-md);
  background:color-mix(in srgb, var(--bg-2) 88%, transparent); backdrop-filter:blur(18px);
  box-shadow:0 18px 44px color-mix(in srgb, var(--bg) 78%, transparent);
  animation:dock-in .32s var(--ease-spring)}
@keyframes dock-in{from{opacity:0; transform:translateY(12px) scale(.98)} to{opacity:1; transform:none}}
/* 顶部主色光带：星笺的"信号"感 */
.dock-panel::before{content:''; display:block; height:2px;
  background:linear-gradient(90deg, transparent, var(--primary), transparent); opacity:.7}
.dock-head{display:flex; align-items:center; gap:10px; padding:9px 14px 7px}
.dock-head h3{font-family:var(--font-serif); font-weight:900; font-size:14px; margin:0}
.dock-spacer{flex:1}
.dock-icon{border:1px solid var(--line); background:transparent; color:var(--ink-faint); cursor:pointer;
  font:inherit; font-size:11px; line-height:1; padding:5px 8px; border-radius:var(--r-sm);
  transition:color .25s var(--ease-standard), border-color .25s var(--ease-standard)}
.dock-icon:hover{color:var(--ink); border-color:var(--primary)}
/* 司职切换：两个胶囊 + 一行如实说明代价 */
.dock-modes{display:flex; align-items:center; gap:6px; padding:0 14px 8px; flex-wrap:wrap}
.dock-mode{font:inherit; font-size:11.5px; cursor:pointer; padding:4px 12px;
  border:1px solid var(--line); border-radius:var(--r-pill); background:transparent;
  color:var(--ink-faint); transition:color .25s var(--ease-standard),
    border-color .25s var(--ease-standard), background .25s var(--ease-standard)}
.dock-mode:hover{color:var(--ink); border-color:var(--primary)}
.dock-mode.on{color:var(--primary); border-color:var(--primary); background:var(--primary-soft)}
.dock-mode-hint{font-size:10.5px; color:var(--ink-faint); line-height:1.6}
/* 模型行：与司职切换同族（同一行内边距、同一套胶囊与描边）。
   它是**辅助信息**，所以视觉权重低于司职：名字用 --ink-dim，只有选中态才上主色。
   在 hero 区之外，可能撑破 max-height 的场景都靠「整行仍在正常流里」兜底：
   下拉与表单展开时正文变矮，而不是溢出面板。 */
.dock-model{position:relative; display:flex; align-items:center; gap:8px; padding:0 14px 8px}
.dock-model-label{font-size:10.5px; letter-spacing:.15em; color:var(--ink-faint); flex:none}
.dock-model-pick{display:flex; align-items:center; gap:6px; min-width:0; max-width:100%;
  font:inherit; font-size:11.5px; cursor:pointer; padding:4px 10px 4px 12px;
  border:1px solid var(--line); border-radius:var(--r-pill); background:transparent;
  color:var(--ink-dim); transition:color .25s var(--ease-standard),
    border-color .25s var(--ease-standard), background .25s var(--ease-standard)}
.dock-model-pick:hover{color:var(--ink); border-color:var(--primary); background:var(--primary-soft)}
/* 取数失败：如实说「没读到配置」，用暖色提示，但不改变它的可点性（点开还有「＋ 添加模型」） */
.dock-model-name{white-space:nowrap; overflow:hidden; text-overflow:ellipsis}
.dock-model-name.warn{color:var(--amber)}
.dock-model-provider{font-size:10.5px; color:var(--ink-faint); flex:none}
/* 「我配的 / 用全局」两态必须一眼可辨（store 口径第 2 条） */
.dock-model-chip{font-size:10px; padding:1px 7px; border-radius:var(--r-pill);
  background:var(--primary-soft); color:var(--primary); flex:none}
.dock-caret{color:var(--ink-faint); font-size:9px; flex:none}
/* 下拉：**正常流**，绝不是 absolute。这条注释是拿一次真实事故换来的：
   它曾经写成 `position:absolute; top:100%; left:14px; right:14px`，而它在模板里是
   `.dock-model` 那一行的**兄弟**、不是子元素 —— 于是定位基准是面板（面板带 relative），
   `top:100%` 落到**面板底部之外**，整块被面板的 `overflow:hidden` 裁掉。
   `modelOpen` 其实是正常翻转的（点击生效了），但用户什么都看不见，反馈就是「下拉点不了」。
   ⚠️ 正常流下它自然出现在「模型」那一行下面，永远不会被裁；代价是菜单占位会压矮正文
   —— 这个代价明确可接受（比点不到好得多）。想再把它做成浮层的话，正确做法是改 DOM
   （把菜单挂进 `.dock-model` 里当子元素）并同步改自检里那条断言，**不要**只把 absolute 加回来。
   `max-height` + `overflow-y:auto` 是防它太长顶破面板的 `max-height`。 */
.dock-model-menu{margin:0 14px 8px; max-height:240px; overflow-y:auto; scrollbar-width:thin;
  display:flex; flex-direction:column; gap:2px; padding:6px;
  border:1px solid var(--line); border-radius:var(--r-sm);
  background:color-mix(in srgb, var(--bg-3) 96%, transparent);
  box-shadow:0 18px 40px color-mix(in srgb, var(--bg) 72%, transparent);
  animation:dock-menu-in .2s var(--ease-standard)}
@keyframes dock-menu-in{from{opacity:0; transform:translateY(-4px)} to{opacity:1; transform:none}}
.dock-menu-item{display:flex; align-items:center; gap:8px; width:100%; text-align:left;
  font:inherit; font-size:12px; cursor:pointer; padding:8px 10px; border:none;
  border-radius:var(--r-sm); background:transparent; color:var(--ink-dim);
  transition:background .2s var(--ease-standard), color .2s var(--ease-standard)}
.dock-menu-item:hover{background:var(--surface-2); color:var(--ink)}
.dock-menu-item.on{color:var(--primary); background:var(--primary-soft)}
.dock-menu-item > span:first-child{flex:1; min-width:0}
.dock-menu-main{display:flex; flex-direction:column; gap:2px; min-width:0}
.dock-menu-main b{font-weight:500; overflow:hidden; text-overflow:ellipsis; white-space:nowrap}
/* 端点与掩码用等宽：它们是元数据，不是句子 */
.dock-menu-main em{font-family:var(--font-mono); font-style:normal; font-size:10px;
  color:var(--ink-faint); overflow:hidden; text-overflow:ellipsis; white-space:nowrap}
.dock-menu-mark{color:var(--primary); flex:none}
.dock-menu-note{margin:2px 0; font-size:10.5px; color:var(--amber); line-height:1.6}
.dock-menu-hint{margin:2px 0; font-size:10.5px; color:var(--ink-faint); line-height:1.6}
.dock-menu-add{font:inherit; font-size:11.5px; cursor:pointer; margin-top:2px; padding:7px 10px;
  border:1px dashed var(--line); border-radius:var(--r-sm); background:transparent;
  color:var(--primary); transition:border-color .2s var(--ease-standard),
    background .2s var(--ease-standard)}
.dock-menu-add:hover{border-color:var(--primary); background:var(--primary-soft)}
/* 就地表单：字段观感对齐 components.css 的 .field（同一套 --line / --r-sm / --bg-2） */
.dock-form{display:flex; flex-direction:column; gap:6px; margin:0 14px 10px; padding:10px 12px;
  border:1px solid var(--line); border-radius:var(--r-md); background:var(--surface);
  animation:dock-form-in .2s var(--ease-standard); max-height:340px; overflow-y:auto;
  scrollbar-width:thin}
@keyframes dock-form-in{from{opacity:0; transform:translateY(-4px)} to{opacity:1; transform:none}}
.dock-form-grid{display:grid; grid-template-columns:1fr 1fr; gap:8px}
.dock-field{display:flex; flex-direction:column; gap:4px; min-width:0}
.dock-field.wide{grid-column:1 / -1}
.dock-field > span{font-size:10px; letter-spacing:.12em; color:var(--ink-faint)}
.dock-field input, .dock-field select{width:100%; height:32px; min-width:0; font:inherit; font-size:12px;
  padding:0 9px; border:1px solid var(--line); border-radius:var(--r-sm);
  background:var(--bg-2); color:var(--ink); outline:none;
  transition:border-color .2s var(--ease-standard)}
.dock-field input:focus, .dock-field select:focus{border-color:var(--primary)}
/* 模型名那一行：输入框 + 「拉取模型」并排（输入框必须 flex:1 + min-width:0，否则长模型名会把按钮挤出去）。
   ⚠️ 选择器写成 `.dock-field > .dock-field-row` 而不是 `.dock-field-row`：后者比
   `.dock-field > span` 弱，会继承标签行的 `font-size:10px` 与 `letter-spacing:.12em`
   —— 输入框里的模型名会被莫名拉开字距（`font` 简写不含 letter-spacing，`:inherit` 也拦不住）。 */
.dock-field > .dock-field-row{display:flex; align-items:center; gap:6px; min-width:0;
  font-size:inherit; letter-spacing:normal}
.dock-field-row input{flex:1; min-width:0}
.dock-btn.pull{flex:none; white-space:nowrap}
/* 拉取模型的三态 + 候选列表。三态全部在**正常流**里（见模板里的注释：爹是滚动区，absolute 会被裁掉），
   文案一行一句、用等宽字体标出 source —— 与 `.dock-menu-main em`（端点/掩码）同一套语言。 */
.dock-pull{display:flex; flex-direction:column; gap:4px; margin-top:2px}
.dock-pull-head{display:flex; align-items:baseline; gap:8px}
.dock-pull-line{flex:1; min-width:0; margin:0; font-size:10.5px; color:var(--ink-dim); line-height:1.7}
.dock-pull-line.bad{color:var(--amber)}
.dock-pull-line.dim{color:var(--ink-faint)}
.dock-pull-line code{font-family:var(--font-mono); font-size:10px; color:var(--ink-dim); word-break:break-all}
/* 「收起候选 / 再看候选」：与下拉里的「＋ 添加模型」同一族（虚线上边 + 主色文字），只是更小 */
.dock-pull-toggle{flex:none; font:inherit; font-size:10.5px; cursor:pointer; padding:2px 8px;
  border:1px dashed var(--line); border-radius:var(--r-pill); background:transparent;
  color:var(--primary); transition:border-color .2s var(--ease-standard),
    background .2s var(--ease-standard)}
.dock-pull-toggle:hover{border-color:var(--primary); background:var(--primary-soft)}
/* 候选列表：结构照现有下拉（容器 --line/--r-sm/--bg-2，行 --r-pill 胶囊），
   所以只换尺寸、不引入新的样式语言。限高 + 自己滚动是防「几十个模型把表单撑成一整屏」。 */
.dock-pull-list{list-style:none; margin:0; padding:5px; display:flex; flex-direction:column; gap:2px;
  max-height:150px; overflow-y:auto; scrollbar-width:thin;
  border:1px solid var(--line); border-radius:var(--r-sm);
  background:color-mix(in srgb, var(--bg-2) 92%, transparent)}
/* 模型 id 是**元数据**（要和供应商文档逐字对齐），所以走等宽 —— 浮层里 `.dock-menu-main em`
   与 source 那行已经是这个用法。`border:1px solid transparent` 是为了 hover 时不出位移。 */
.dock-pull-item{display:block; width:100%; text-align:left; font:inherit; cursor:pointer;
  font-family:var(--font-mono); font-size:11.5px; line-height:1.5; padding:6px 10px;
  border:1px solid transparent; border-radius:var(--r-pill); background:transparent; color:var(--ink-dim);
  overflow:hidden; text-overflow:ellipsis; white-space:nowrap;
  transition:color .2s var(--ease-standard), border-color .2s var(--ease-standard),
    background .2s var(--ease-standard)}
.dock-pull-item:hover{color:var(--ink); border-color:var(--primary); background:var(--primary-soft)}
.dock-form-note{margin:2px 0 0; font-size:10.5px; color:var(--ink-faint); line-height:1.7}
.dock-form-note code{font-family:var(--font-mono); font-size:10px; color:var(--ink-dim)}
.dock-check{margin:2px 0 0; font-size:10.5px; color:var(--teal); line-height:1.7}
.dock-check.bad{color:var(--amber)}
/* 结论范围（tcp_only）必须跟着结论一起出现，不能只给一个「可用」 */
.dock-check-scope{color:var(--ink-faint)}
.dock-form-actions{display:flex; align-items:center; gap:6px; margin-top:4px}
/* 表单里的按钮是胶囊（与司职、模型选择同一套语言），尺寸按浮层收小 */
.dock-btn{font:inherit; font-size:11.5px; cursor:pointer; padding:5px 12px;
  border:1px solid var(--line); border-radius:var(--r-pill); background:transparent;
  color:var(--ink-dim); transition:color .2s var(--ease-standard),
    border-color .2s var(--ease-standard), background .2s var(--ease-standard)}
.dock-btn:hover{color:var(--ink); border-color:var(--primary)}
/* 填充主色上的文字必须用 --on-primary：夜色/暮色的 --primary 偏亮，白字只有 3.3:1 / 2.2:1 */
.dock-btn.primary{background:var(--primary); border-color:var(--primary); color:var(--on-primary)}
.dock-btn.primary:hover{filter:brightness(1.08)}
.dock-btn.ghost{color:var(--ink-faint)}
/* 禁用态要有可见差异：禁用按钮与可点按钮长得一样、点了又没反应，就是「按钮是假的」 */
.dock-btn:disabled{cursor:not-allowed; opacity:.45; filter:saturate(.5)}
.dock-btn:disabled:hover{color:var(--ink-dim); border-color:var(--line); filter:saturate(.5)}
.dock-err.inline{margin:2px 0 0}
/* 表单收起来时，操作结果（尤其是失败原因）仍要有地方显示 */
.dock-model-status{margin:0 14px 8px; font-size:10.5px; line-height:1.7; color:var(--ink-faint)}
.dock-model-status.bad{color:var(--amber)}
.dock-notice.inline{margin:2px 0 0}
/* 正文区固定高度：空态与对话中一样高，切换时不跳；小屏再由面板 max-height 兜底 */
.dock-body{height:420px; padding:2px 14px 14px; overflow-y:auto;
  display:flex; flex-direction:column; scrollbar-width:thin}
.dock-body::-webkit-scrollbar{width:6px}
.dock-body::-webkit-scrollbar-thumb{background:var(--line); border-radius:var(--r-pill)}
/* 轮次：留白 + 极淡分隔线（舒展密度，对齐深读页的阅读节奏） */
.dock-turn + .dock-turn{margin-top:20px; padding-top:16px; border-top:1px solid var(--line)}
.dock-steps{margin:8px 0 0; font-size:10.5px; color:var(--ink-faint); line-height:1.7}
/* 核验是辅助信息：平时低调，查出问题才用暖色 —— 但绝不写成"答案已核实" */
.dock-verify{margin:6px 0 0; font-size:10.5px; color:var(--ink-faint); line-height:1.7}
.dock-verify.warn{color:var(--amber)}
.dock-login{margin:auto 0; font-size:12px; color:var(--ink-faint); line-height:1.9; text-align:center}
.dock-link{color:var(--primary)}
.dock-notice{margin:12px 0 0; font-size:11px; color:var(--amber); line-height:1.8}
.dock-err{margin:12px 0 0; font-size:12px; color:var(--rose); line-height:1.8}

/* 空态：星形锚点 + 示例问题 */
.dock-empty{flex:1; display:flex; flex-direction:column; justify-content:center; align-items:center;
  text-align:center; gap:6px}
.dock-star{font-size:22px; color:var(--primary); opacity:.85; line-height:1}
.dock-poem{font-family:var(--font-serif); font-size:15px; color:var(--ink); margin:8px 0 2px}
.dock-sub{font-size:11px; color:var(--ink-faint); margin:0 0 14px; line-height:1.8}
.dock-chips{display:flex; flex-direction:column; gap:7px; width:100%}
.dock-chip{font:inherit; text-align:left; cursor:pointer; font-size:12px; line-height:1.6;
  padding:9px 12px; border-radius:var(--r-sm); border:1px solid var(--line);
  background:var(--surface); color:var(--ink-dim);
  transition:color .25s var(--ease-standard), border-color .25s var(--ease-standard),
    background .25s var(--ease-standard)}
.dock-chip:hover{color:var(--ink); border-color:var(--primary); background:var(--primary-soft)}

/* 输入区：聊天式胶囊。生成中不禁用输入框（可以边等边写下一条），只把动作换成"停止" */
.dock-foot{display:flex; gap:8px; padding:10px 12px; border-top:1px solid var(--line)}
.dock-foot input{flex:1; min-width:0; height:40px; background:var(--bg-2); border:1px solid var(--line);
  border-radius:var(--r-pill); color:var(--ink); font:inherit; font-size:13px; padding:0 16px;
  transition:border-color .28s var(--ease-standard)}
.dock-foot input:focus{outline:none; border-color:var(--primary)}
.dock-send{height:40px; padding:0 16px; border-radius:var(--r-pill); white-space:nowrap; font-size:13px}
/* 表单自身也有上限：正常情况它大约 300px（模型名那一行现在占满整行，比原来高一行），
   小屏再收一点。上限只影响「表单自己滚不滚」——
   面板是 flex 列 + overflow:hidden，正文 `.dock-body` 是 `flex-shrink:1` 的滚动容器
   （其 min-height 解析为 0），所以表单变高时是**正文变矮**，不会把 foot 挤出面板。 */
@media (max-width: 560px) {
  .dock{right:10px; left:10px; bottom:10px; align-items:stretch}
  .dock-panel{width:auto; max-height:calc(100vh - 90px)}
  .dock-body{height:min(420px, 52vh)}
  .dock-form{max-height:200px}
  .dock-bubble{align-self:flex-end}
}
@media (prefers-reduced-motion: reduce) {
  .dock-panel{animation:none} .dock-bubble{transition:none}
  /* 新加的下拉/表单展开也必须停：全局基线是「reduce 时关掉全部动画与过渡」 */
  .dock-model-menu{animation:none} .dock-form{animation:none}
  .dock-model-pick, .dock-menu-item, .dock-menu-add, .dock-btn, .dock-field input,
  .dock-field select, .dock-pull-item, .dock-pull-toggle{transition:none}
}
</style>
