<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { AI_CAPABILITIES, AI_ROLES, useAiStore } from '@/stores/ai'
import { emit, TOAST } from '@/utils/bus'
import SectionHead from '@/components/common/SectionHead.vue'

const auth = useAuthStore()
const ai = useAiStore()
const route = useRoute()
const router = useRouter()

/** 当前展开编辑的角色 */
const editing = ref('')
const saving = ref(false)

/**
 * 页签：`providers` 模型配置 / `eval` 评测台。
 * 状态写进 URL（`?tab=eval`）：刷新与分享链接后仍停在同一个页签，
 * 与「我的笔记 · 复核」视图的既有做法一致。
 */
const TAB_PROVIDERS = 'providers'
const TAB_EVAL = 'eval'
const tab = ref(route.query.tab === TAB_EVAL ? TAB_EVAL : TAB_PROVIDERS)

watch(tab, (value) => {
  const query = { ...route.query }
  if (value === TAB_EVAL) query.tab = TAB_EVAL
  else delete query.tab
  router.replace({ query })
  if (value === TAB_EVAL) ensureEvalMeta()
})

/* 表单是「按角色」的：切换角色时把已存配置或预设填进去，避免手抄一遍端点与模型名 */
const form = reactive({
  displayName: '',
  provider: 'openai_compatible',
  baseUrl: '',
  model: '',
  apiKey: '',
  dimension: '',
  timeoutMs: 30000,
  maxTokens: '',
  temperature: '',
})

const editingMeta = computed(() => AI_ROLES.find((role) => role.key === editing.value) || null)
const canSubmit = computed(() => !!editing.value && !!form.displayName.trim()
  && !!form.baseUrl.trim() && !!form.model.trim())

/* ------------------------------ 评测台 ------------------------------ */
const selectedDataset = ref('')
const onlyProblems = ref(true)

const canRunEval = computed(() => !!selectedDataset.value && ai.evalSelected.length > 0
  && !ai.evalRunning)

/**
 * 模型来源的显示：三种取值对应三种完全不同的结论，而表格长得一模一样 ——
 * 「离线桩」下 Dense 两列没有语义，「未用模型」下这两列根本不存在，
 * 都不能让人误以为自己在看真实质量。暖色只留给「用了桩」这一种。
 */
const evalModelLabel = computed(() => {
  const source = ai.evalResult?.models
  if (source === 'panel') return '模型：面板配置的真实模型'
  if (source === 'fake') return '模型：离线桩（向量无语义）'
  if (source === 'none') return '未使用任何模型（仅词法召回）'
  return `模型：${source ?? '未知'}`
})
const evalModelTone = computed(() => (ai.evalResult?.models === 'fake' ? 'warm' : 'cool'))

/** 逐题明细：标注「漏召 / 误拒」两类问题，面板默认只看问题行 */
const caseRows = computed(() => ai.evalCases.map((row) => {
  const relevant = Array.isArray(row.relevantPosts) ? row.relevantPosts : []
  const retrieved = Array.isArray(row.retrievedPosts) ? row.retrievedPosts : []
  const answerable = (row.caseType || 'answerable') === 'answerable'
  const hit = relevant.some((id) => retrieved.includes(id))
  let status = 'hit'
  if (!answerable) status = row.refused ? 'refused-ok' : 'should-refuse'
  else if (row.refused) status = 'false-refusal'
  else if (!hit) status = 'miss'
  return { ...row, status, hit, answerable }
}))

const shownCases = computed(() => (onlyProblems.value
  ? caseRows.value.filter((row) => row.status === 'miss' || row.status === 'false-refusal'
    || row.status === 'should-refuse')
  : caseRows.value))

const problemCount = computed(() => caseRows.value.filter((row) => row.status === 'miss'
  || row.status === 'false-refusal' || row.status === 'should-refuse').length)

const CASE_STATUS_TEXT = {
  hit: '命中',
  miss: '漏召',
  'false-refusal': '误拒',
  'refused-ok': '已拒答（正确）',
  'should-refuse': '该拒未拒',
}

onMounted(() => {
  if (!auth.isAdmin) return
  ai.fetchProviders().catch(() => {})
  // 模型库读不到不该拖垮整页（最常见原因：还没执行 11_ai_model_library.sql），故吞掉错误
  ai.loadModels().catch(() => {})
  if (tab.value === TAB_EVAL) ensureEvalMeta()
})

/** 元数据只取一次；失败时由面板上的「重试」按钮再来一次 */
function ensureEvalMeta() {
  if (ai.evalDatasets.length && ai.evalStrategies.length) {
    if (!selectedDataset.value) selectedDataset.value = ai.evalDatasets[0]?.id || ''
    return
  }
  ai.loadEvalMeta()
    .then(() => {
      if (!selectedDataset.value) selectedDataset.value = ai.evalDatasets[0]?.id || ''
    })
    .catch(() => {})
}

function toggleStrategy(key) {
  const chosen = new Set(ai.evalSelected)
  if (chosen.has(key)) chosen.delete(key)
  else chosen.add(key)
  ai.evalSelected = ai.evalStrategies.filter((item) => chosen.has(item.key)).map((i) => i.key)
}

function formatMetric(value) {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'number') return Number.isInteger(value) ? String(value) : value.toFixed(4)
  return String(value)
}

/** 逐题召回情况：把「期望」与「实际」摆在一起，差集一眼可见 */
function missingPosts(row) {
  const relevant = Array.isArray(row.relevantPosts) ? row.relevantPosts : []
  const retrieved = Array.isArray(row.retrievedPosts) ? row.retrievedPosts : []
  return relevant.filter((id) => !retrieved.includes(id))
}

async function runEval() {
  if (!canRunEval.value) return
  try {
    const result = await ai.runEval({
      dataset: selectedDataset.value,
      strategies: ai.selectedStrategyPayload(),
    })
    emit(TOAST, {
      type: 'success',
      message: `评测完成：${result.strategies?.length || 0} 组策略 × ${new Set((result.cases || []).map((c) => c.caseId)).size} 题`,
    })
  } catch {
    /* 错误已进 store.evalError 并由全局 toast 提示，面板保持可重试 */
  }
}

function startEdit(roleKey) {
  editing.value = roleKey
  const meta = AI_ROLES.find((role) => role.key === roleKey)
  const existing = ai.providers[roleKey]
  form.displayName = existing?.displayName || meta.label
  form.provider = existing?.provider || 'openai_compatible'
  form.baseUrl = existing?.baseUrl || ''
  form.model = existing?.model || ''
  form.apiKey = ''            // 明文密钥永不回显：留空即沿用已存密钥
  form.dimension = existing?.dimension ?? ''
  form.timeoutMs = existing?.timeoutMs ?? 30000
  form.maxTokens = existing?.maxTokens ?? ''
  form.temperature = existing?.temperature ?? ''
}

function cancelEdit() {
  editing.value = ''
}

function numberOrNull(value) {
  const raw = String(value ?? '').trim()
  return raw === '' ? null : Number(raw)
}

async function save() {
  if (!canSubmit.value || saving.value) return
  saving.value = true
  try {
    const firstTime = !ai.providers[editing.value]
    if (firstTime && !form.apiKey.trim()) {
      emit(TOAST, { type: 'warn', message: '该角色还没配过，请先填写 API Key' })
      return
    }
    await ai.saveProvider(editing.value, {
      displayName: form.displayName.trim(),
      provider: form.provider,
      baseUrl: form.baseUrl.trim(),
      model: form.model.trim(),
      apiKey: form.apiKey.trim(),   // 空串=沿用已存密钥（后端按此语义处理）
      dimension: numberOrNull(form.dimension),
      timeoutMs: Number(form.timeoutMs) || 30000,
      maxTokens: numberOrNull(form.maxTokens),
      temperature: numberOrNull(form.temperature),
      enabled: true,
    })
    form.apiKey = ''
    emit(TOAST, { type: 'success', message: `${editingMeta.value?.label || '配置'}已保存` })
  } catch {
    /* 全局 toast 已提示，这里保持面板不关闭以便改错 */
  } finally {
    saving.value = false
  }
}

async function check(roleKey) {
  try {
    const result = await ai.checkProvider(roleKey)
    emit(TOAST, {
      type: result.ok ? 'success' : 'warn',
      message: result.ok ? '端点可达' : result.message,
    })
  } catch {
    /* 同上 */
  }
}

async function remove(roleKey) {
  const meta = AI_ROLES.find((role) => role.key === roleKey)
  if (!window.confirm(`确定删除「${meta?.label}」的配置？删除后该能力不可用。`)) return
  try {
    await ai.removeProvider(roleKey)
    if (editing.value === roleKey) editing.value = ''
    emit(TOAST, { type: 'success', message: '配置已删除' })
  } catch {
    /* 同上 */
  }
}

/* ------------------------------ 模型库 ------------------------------ */

/** 角色键 → 下拉框里选中的模型 id（字符串，便于 select 绑定） */
const picked = reactive({})
/** 正在编辑的库条目 id；null = 新增 */
const modelEditing = ref(null)
const modelFormOpen = ref(false)

const modelForm = reactive({
  displayName: '',
  provider: 'openai_compatible',
  baseUrl: '',
  model: '',
  apiKey: '',
  capabilities: { chat: true, embedding: false, rerank: false },
  dimension: '',
  timeoutMs: 30000,
  maxTokens: '',
  temperature: '',
})

const canSubmitModel = computed(() => !!modelForm.displayName.trim()
  && !!modelForm.baseUrl.trim() && !!modelForm.model.trim()
  && Object.values(modelForm.capabilities).some(Boolean))

function capabilityLabel(key) {
  return AI_CAPABILITIES.find((item) => item.key === key)?.label || key
}

/** 库列表里一行「能力」的展示文案 */
function capabilityText(item) {
  return (item.capabilities || []).map(capabilityLabel).join(' / ') || '未标注'
}

/** 「正被哪些角色使用」：空数组时不该显示成空白，要说「还没人用」 */
function boundRolesText(item) {
  const roles = item.boundRoles || []
  if (!roles.length) return '还没被任何角色使用'
  return `正被 ${roles.map((key) => AI_ROLES.find((r) => r.key === key)?.label || key).join('、')} 使用`
}

function resetModelForm() {
  modelEditing.value = null
  modelForm.displayName = ''
  modelForm.provider = 'openai_compatible'
  modelForm.baseUrl = ''
  modelForm.model = ''
  modelForm.apiKey = ''      // 明文密钥永不回显
  modelForm.capabilities = { chat: true, embedding: false, rerank: false }
  modelForm.dimension = ''
  modelForm.timeoutMs = 30000
  modelForm.maxTokens = ''
  modelForm.temperature = ''
}

function startAddModel() {
  resetModelForm()
  modelFormOpen.value = true
}

function startEditModel(item) {
  modelEditing.value = item.id
  modelForm.displayName = item.displayName || ''
  modelForm.provider = item.provider || 'openai_compatible'
  modelForm.baseUrl = item.baseUrl || ''
  modelForm.model = item.model || ''
  modelForm.apiKey = ''
  const caps = item.capabilities || []
  modelForm.capabilities = {
    chat: caps.includes('chat'),
    embedding: caps.includes('embedding'),
    rerank: caps.includes('rerank'),
  }
  modelForm.dimension = item.dimension ?? ''
  modelForm.timeoutMs = item.timeoutMs ?? 30000
  modelForm.maxTokens = item.maxTokens ?? ''
  modelForm.temperature = item.temperature ?? ''
  modelFormOpen.value = true
}

async function submitModel() {
  if (!canSubmitModel.value) return
  const capabilities = AI_CAPABILITIES
    .filter((item) => modelForm.capabilities[item.key])
    .map((item) => item.key)
  try {
    await ai.saveModel({
      id: modelEditing.value,
      displayName: modelForm.displayName.trim(),
      provider: modelForm.provider,
      baseUrl: modelForm.baseUrl.trim(),
      model: modelForm.model.trim(),
      apiKey: modelForm.apiKey.trim(),   // 空串 = 沿用已存密钥
      capabilities,
      dimension: numberOrNull(modelForm.dimension),
      timeoutMs: Number(modelForm.timeoutMs) || 30000,
      maxTokens: numberOrNull(modelForm.maxTokens),
      temperature: numberOrNull(modelForm.temperature),
      enabled: true,
    })
    emit(TOAST, {
      type: 'success',
      message: modelEditing.value ? '模型已更新，用它的角色也一起同步了' : '模型已加入模型库',
    })
    modelFormOpen.value = false
    resetModelForm()
  } catch {
    /* 全局 toast 已提示；表单保持打开以便改错 */
  }
}

async function removeModelRow(item) {
  const used = (item.boundRoles || []).length > 0
  const question = used
    ? `「${item.displayName}」${boundRolesText(item)}。删除只会解除绑定，这些角色当前生效的配置保持不动 —— 确定删？`
    : `确定从模型库删除「${item.displayName}」？`
  if (!window.confirm(question)) return
  try {
    await ai.removeModel(item.id, used)
    emit(TOAST, { type: 'success', message: '已从模型库删除' })
  } catch {
    /* 同上 */
  }
}

async function checkModelRow(item) {
  try {
    const result = await ai.checkModel(item.id)
    emit(TOAST, {
      type: result.ok ? 'success' : 'warn',
      message: result.ok ? `${item.displayName} 端点可达` : result.message,
    })
  } catch {
    /* 同上 */
  }
}

/** 下拉框当前值：用户选过就是他的选择，否则显示角色当前绑定的那条 */
function pickValue(item) {
  return picked[item.key] ?? String(item.config?.modelId ?? '')
}

function canApply(item) {
  const chosen = picked[item.key]
  return !!chosen && chosen !== String(item.config?.modelId ?? '') && !item.binding
}

async function applyModel(item) {
  if (!canApply(item)) return
  try {
    await ai.bindModel(item.key, Number(picked[item.key]))
    picked[item.key] = ''
    emit(TOAST, { type: 'success', message: `「${item.label}」已改用所选模型` })
  } catch {
    /* 能力不匹配等错误已由全局 toast 提示（后端给的是可照做的话） */
  }
}
</script>

<template>
  <section class="page">
    <SectionHead
      title="AI 实验室" kicker="AI LAB · 模型与检索的控制台"
      more="选择模型、填写密钥、验证连通性"
    />

    <p v-if="!auth.isAdmin" class="state-text">
      这里是站长的调试台：模型配置涉及密钥与额度，只对 ADMIN 开放。
      <RouterLink class="state-action" to="/account">去看看我的账号</RouterLink>
    </p>

    <template v-else>
      <div class="lab-tabs">
        <button
          class="lab-tab" :class="{ on: tab === TAB_PROVIDERS }"
          type="button" @click="tab = TAB_PROVIDERS"
        >
          模型配置
        </button>
        <button
          class="lab-tab" :class="{ on: tab === TAB_EVAL }"
          type="button" @click="tab = TAB_EVAL"
        >
          评测台
        </button>
      </div>

      <template v-if="tab === TAB_PROVIDERS">
      <p class="lab-note reveal">
        配置保存后立即生效，无需重启服务。API Key 落库前会加密（主密钥只在服务器环境变量里），
        面板此后只显示掩码 —— 忘了只能重填一次。
      </p>

      <p v-if="ai.loading && !ai.initialized" class="state-text">正在读取模型配置…</p>
      <p v-else-if="ai.error && !ai.initialized" class="state-text error-text">
        {{ ai.error }} <button class="state-action" @click="ai.fetchProviders()">重新读取</button>
      </p>

      <div class="role-grid reveal" style="--d:.08s">
        <div v-for="item in ai.list" :key="item.key" class="role-card">
          <div class="role-head">
            <b>{{ item.label }}</b>
            <span class="chip" :class="item.config ? 'cool' : 'warm'">
              {{ item.config ? '已配置' : '未配置' }}
            </span>
          </div>
          <p class="role-hint">{{ item.hint }}</p>

          <dl v-if="item.config" class="role-meta">
            <div><dt>模型</dt><dd>{{ item.config.model }}</dd></div>
            <div><dt>密钥</dt><dd>{{ item.config.apiKeyMask || '—' }}</dd></div>
            <div v-if="item.config.dimension"><dt>维度</dt><dd>{{ item.config.dimension }}</dd></div>
          </dl>

          <p v-if="item.check" class="check-line" :class="{ bad: !item.check.ok }">
            {{ item.check.ok ? '✓' : '✕' }} {{ item.check.message }}
          </p>

          <!-- 从模型库里选：能力匹配的才会出现在这里（后端绑定时还会再校验一次） -->
          <div v-if="item.options.length" class="role-pick">
            <select
              class="role-select"
              :value="pickValue(item)"
              @change="picked[item.key] = $event.target.value"
            >
              <option value="">— 从模型库里选一个 —</option>
              <option
                v-for="option in item.options" :key="option.id"
                :value="String(option.id)" :disabled="!option.enabled"
              >
                {{ option.displayName }} · {{ option.model }}{{ option.enabled ? '' : '（已停用）' }}
              </option>
            </select>
            <button class="btn btn-ghost" :disabled="!canApply(item)" @click="applyModel(item)">
              {{ item.binding ? '应用中…' : '应用' }}
            </button>
          </div>
          <p v-else-if="ai.modelsLoaded" class="form-hint tight">
            模型库里还没有可用于「{{ item.label }}」的模型 ——
            在下方「模型库」里加一个，并勾上「{{ capabilityLabel(item.capability) }}」能力。
          </p>

          <p v-if="item.boundModel" class="bound-line">
            已绑定模型库条目：<b>{{ item.boundModel.displayName }}</b>
          </p>

          <div class="role-actions">
            <button class="btn btn-ghost" @click="startEdit(item.key)">
              {{ item.config ? '修改' : '配置' }}
            </button>
            <button
              v-if="item.config" class="btn btn-ghost"
              :disabled="item.checking" @click="check(item.key)"
            >
              {{ item.checking ? '自检中…' : '测试连通' }}
            </button>
            <button v-if="item.config" class="btn btn-ghost danger" @click="remove(item.key)">删除</button>
          </div>
        </div>
      </div>

      <!-- ============================ 模型库 ============================
           为什么要有它：角色配置表按角色唯一（一个角色一行），「再加一个 chat 模型」会把原来那行
           覆盖掉 —— 两个模型之间没法切换，换模型还得把 Key 重填一遍。
           模型库把「模型」与「角色用哪个」拆开：这里负责加，角色卡片上的下拉框负责选。 -->
      <section class="side-card model-lib reveal" style="--d:.12s">
        <div class="lib-head">
          <h5>模型库</h5>
          <span class="form-hint tight">
            {{ ai.models.length }} 个模型 · 角色下拉框只列出能力匹配的那些
          </span>
          <button class="btn btn-ghost" @click="startAddModel">＋ 新增模型</button>
        </div>

        <p v-if="ai.modelsError" class="state-text error-text">
          模型库读不出来：{{ ai.modelsError }}
          <br>
          两种常见原因：① 数据库还没执行 <code>deploy/sql/11_ai_model_library.sql</code>；
          ② 服务还是旧 jar（新接口要重启 ai-service 后才有）。
          <button class="state-action" @click="ai.loadModels().catch(() => {})">重新读取</button>
        </p>
        <p v-else-if="ai.modelsLoading && !ai.modelsLoaded" class="state-text">正在读取模型库…</p>
        <p v-else-if="!ai.models.length" class="state-text">
          还没有模型。点「＋ 新增模型」把端点、模型名与 Key 存进来，之后各角色就能用下拉框选它。
        </p>

        <div v-else class="model-list">
          <div v-for="item in ai.models" :key="item.id" class="model-row">
            <div class="model-main">
              <b>{{ item.displayName }}</b>
              <span class="model-sub">{{ item.model }}</span>
              <span class="model-sub">{{ item.baseUrl }}</span>
            </div>
            <div class="model-tags">
              <span v-for="cap in item.capabilities" :key="cap" class="chip cool">
                {{ capabilityLabel(cap) }}
              </span>
              <span v-if="!item.enabled" class="chip warm">已停用</span>
              <span class="model-mask">{{ item.apiKeyMask || '未配置密钥' }}</span>
            </div>
            <p class="model-bound">{{ boundRolesText(item) }}</p>
            <p v-if="item.lastCheckMessage" class="check-line" :class="{ bad: item.lastCheckStatus === 'failed' }">
              {{ item.lastCheckStatus === 'ok' ? '✓' : '✕' }} {{ item.lastCheckMessage }}
            </p>
            <div class="role-actions">
              <button
                class="btn btn-ghost" :disabled="ai.checkingModel === item.id"
                @click="checkModelRow(item)"
              >
                {{ ai.checkingModel === item.id ? '自检中…' : '测试连通' }}
              </button>
              <button class="btn btn-ghost" @click="startEditModel(item)">修改</button>
              <button class="btn btn-ghost danger" @click="removeModelRow(item)">删除</button>
            </div>
          </div>
        </div>

        <div v-if="modelFormOpen" class="lib-form">
          <h5>
            {{ modelEditing ? '修改模型' : '新增模型' }}
            <span class="form-hint tight">同端点下同名模型只能有一条</span>
          </h5>
          <div class="form-grid">
            <div class="field">
              <label>展示名</label>
              <input v-model="modelForm.displayName" maxlength="64" placeholder="如「主力对话模型」">
            </div>
            <div class="field">
              <label>协议</label>
              <select v-model="modelForm.provider">
                <option value="openai_compatible">OpenAI 兼容</option>
                <option value="fake">Fake（离线自测，不调用任何服务）</option>
              </select>
            </div>
            <div class="field wide">
              <label>接口地址 baseUrl</label>
              <input v-model="modelForm.baseUrl" maxlength="255" placeholder="你的 OpenAI 兼容端点，形如 https://<host>/v1">
            </div>
            <div class="field">
              <label>模型名</label>
              <input v-model="modelForm.model" maxlength="128" placeholder="照服务方的模型列表填">
            </div>
            <div class="field">
              <label>API Key{{ modelEditing ? '（留空=沿用已存）' : '' }}</label>
              <input
                v-model="modelForm.apiKey" type="password" autocomplete="off" maxlength="512"
                placeholder="sk-..."
              >
            </div>
            <div class="field wide">
              <label>能力（决定它能被哪些角色选到）</label>
              <div class="cap-row">
                <label v-for="cap in AI_CAPABILITIES" :key="cap.key" class="cap-item">
                  <input v-model="modelForm.capabilities[cap.key]" type="checkbox">
                  {{ cap.label }}
                </label>
              </div>
            </div>
            <div class="field">
              <label>向量维度（嵌入模型必填）</label>
              <input v-model="modelForm.dimension" type="number" min="1" max="65536" placeholder="照嵌入模型的维度填">
            </div>
            <div class="field">
              <label>超时（毫秒）</label>
              <input v-model="modelForm.timeoutMs" type="number" min="100" max="600000">
            </div>
            <div class="field">
              <label>maxTokens（可空）</label>
              <input v-model="modelForm.maxTokens" type="number" min="1" placeholder="留空用模型默认">
            </div>
            <div class="field">
              <label>温度（可空）</label>
              <input v-model="modelForm.temperature" type="number" step="0.1" min="0" max="2" placeholder="留空用模型默认">
            </div>
          </div>
          <div class="role-actions">
            <button class="btn btn-primary" :disabled="!canSubmitModel || ai.savingModel" @click="submitModel">
              {{ ai.savingModel ? '保存中…' : '保存到模型库' }}
            </button>
            <button class="btn btn-ghost" @click="modelFormOpen = false; resetModelForm()">取消</button>
          </div>
        </div>
      </section>

      <div v-if="editingMeta" class="side-card edit-panel reveal">
        <h5>{{ editingMeta.label }} · {{ editingMeta.hint }}</h5>

        <!-- 刻意**不预填任何厂商**：端点与模型名全部由使用者填。
             曾经有一排「快速填入 DeepSeek / 硅基流动」的按钮，那等于把默认供应商写进代码 ——
             用自建网关、公司代理或别的厂商时，它不只是没用，还会诱导人填错。 -->
        <p class="form-hint">
          端点与模型名请照你所用服务的文档填；面板不预设任何厂商。
        </p>

        <div class="form-grid">
          <div class="field">
            <label>展示名</label>
            <input v-model="form.displayName" maxlength="64" placeholder="给它起个名字，如「主力对话模型」">
          </div>
          <div class="field">
            <label>协议</label>
            <select v-model="form.provider">
              <option value="openai_compatible">OpenAI 兼容</option>
              <option value="fake">Fake（离线自测，不调用任何服务）</option>
            </select>
          </div>
          <div class="field wide">
            <label>接口地址 baseUrl</label>
            <input v-model="form.baseUrl" maxlength="255" placeholder="你的 OpenAI 兼容端点，形如 https://<host>/v1">
          </div>
          <div class="field">
            <label>模型名</label>
            <input v-model="form.model" maxlength="128" placeholder="照服务方的模型列表填">
          </div>
          <div class="field">
            <label>API Key{{ ai.providers[editing] ? '（留空=沿用已存）' : '' }}</label>
            <input
              v-model="form.apiKey" type="password" autocomplete="off" maxlength="512"
              :placeholder="ai.providers[editing]?.apiKeyMask || 'sk-...'"
            >
          </div>
          <div class="field">
            <label>向量维度{{ editing === 'embedding' ? '（必填）' : '（可空）' }}</label>
            <input v-model="form.dimension" type="number" min="1" max="8192" placeholder="照嵌入模型的维度填">
          </div>
          <div class="field">
            <label>超时（毫秒）</label>
            <input v-model="form.timeoutMs" type="number" min="1000" max="300000">
          </div>
          <div class="field">
            <label>maxTokens（可空）</label>
            <input v-model="form.maxTokens" type="number" min="1" placeholder="留空用模型默认">
          </div>
          <div class="field">
            <label>temperature（可空）</label>
            <input v-model="form.temperature" type="number" step="0.1" min="0" max="2" placeholder="留空用模型默认">
          </div>
        </div>

        <div class="form-actions">
          <button class="btn btn-primary" :disabled="!canSubmit || saving" @click="save">
            {{ saving ? '保存中…' : '保存配置' }}
          </button>
          <button class="btn btn-ghost" @click="cancelEdit">取消</button>
          <span class="form-hint">换嵌入模型时，维度要与已建索引一致，否则需要重建集合</span>
        </div>
      </div>
      </template>

      <template v-else>
        <p class="lab-note reveal">
          用黄金集跑一轮检索评测：同一批题目、同一套语料，只换检索开关，看指标怎么变。
          这是唯一能回答「多花的那一路召回到底有没有用」的方式。
        </p>

        <p v-if="ai.evalMetaLoading" class="state-text">正在读取数据集与策略…</p>
        <p v-else-if="ai.evalError && !ai.evalResult" class="state-text error-text">
          {{ ai.evalError }}
          <button class="state-action" @click="ai.loadEvalMeta()">重新读取</button>
        </p>

        <div v-else class="side-card eval-setup reveal">
          <div class="eval-row">
            <div class="field">
              <label>数据集</label>
              <select v-model="selectedDataset">
                <option v-for="item in ai.evalDatasets" :key="item.id" :value="item.id">
                  {{ item.name }}（{{ item.cases }} 题，无答案 {{ item.unanswerableCases }}）
                </option>
              </select>
            </div>
            <div class="field grow">
              <label>被测策略（勾选后一起跑，指标并排对比）</label>
              <div class="strategy-row">
                <button
                  v-for="item in ai.evalStrategies" :key="item.key"
                  class="chip" :class="{ cool: ai.evalSelected.includes(item.key) }"
                  type="button" :title="item.description || ''" @click="toggleStrategy(item.key)"
                >
                  {{ item.key }}
                </button>
              </div>
            </div>
          </div>

          <div class="eval-actions">
            <button class="btn btn-primary" :disabled="!canRunEval" @click="runEval">
              {{ ai.evalRunning ? '跑评测中…' : '跑一轮评测' }}
            </button>
            <span class="form-hint">
              已选 {{ ai.evalSelected.length }} / {{ ai.evalStrategies.length }} 组；
              不放心的先少勾几组，跑一次几十毫秒。
            </span>
          </div>

          <p v-if="ai.evalRunning" class="state-text">
            正在跑：{{ selectedDataset }} × {{ ai.evalSelected.length }} 组策略，请稍候…
          </p>
        </div>

        <template v-if="ai.evalResult">
          <div class="eval-meta reveal">
            <span class="chip cool">{{ ai.evalResult.dataset }}</span>
            <span class="meta-item">语料 {{ ai.evalResult.corpusSource }}</span>
            <span class="meta-item">{{ ai.evalResult.corpusPosts }} 篇 / {{ ai.evalResult.corpusChunks }} 块</span>
            <span class="meta-item">耗时 {{ ai.evalResult.elapsedMs }}ms</span>
            <span class="chip" :class="evalModelTone">{{ evalModelLabel }}</span>
          </div>

          <ul v-if="(ai.evalResult.notes || []).length" class="note-list reveal">
            <li v-for="(note, index) in ai.evalResult.notes" :key="index">{{ note }}</li>
          </ul>

          <div class="table-wrap reveal">
            <table class="eval-table">
              <thead>
                <tr>
                  <th>策略</th>
                  <th v-for="column in ai.evalColumns" :key="column.key">{{ column.label }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in ai.evalResult.strategies" :key="item.key">
                  <td class="strategy-cell">
                    <b>{{ item.key }}</b>
                    <span>{{ item.description }}</span>
                  </td>
                  <td
                    v-for="column in ai.evalColumns" :key="column.key"
                    class="num"
                  >
                    {{ formatMetric(ai.evalResult.perStrategy?.[item.key]?.[column.key]) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="case-head reveal">
            <h5>逐题明细</h5>
            <select v-model="ai.evalCaseStrategy" class="case-select">
              <option v-for="item in ai.evalResult.strategies" :key="item.key" :value="item.key">
                {{ item.key }}
              </option>
            </select>
            <label class="case-toggle">
              <input v-model="onlyProblems" type="checkbox">
              只看问题题（{{ problemCount }}）
            </label>
          </div>

          <p v-if="!shownCases.length" class="state-text">
            {{ onlyProblems ? '这一组策略没有漏召或误拒的题。' : '没有逐题明细。' }}
          </p>

          <div v-else class="table-wrap reveal">
            <table class="eval-table case-table">
              <thead>
                <tr>
                  <th>题号</th>
                  <th>类型</th>
                  <th>结论</th>
                  <th>问题</th>
                  <th>漏掉的文章</th>
                  <th>命中</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in shownCases" :key="row.caseId">
                  <td class="mono">{{ row.caseId }}</td>
                  <td>{{ row.answerable ? '有答案' : '无答案' }}</td>
                  <td>
                    <span class="chip" :class="row.status === 'hit' || row.status === 'refused-ok' ? 'cool' : 'warm'">
                      {{ CASE_STATUS_TEXT[row.status] || row.status }}
                    </span>
                  </td>
                  <td class="question-cell">{{ row.question }}</td>
                  <td class="mono">{{ missingPosts(row).join(', ') || '—' }}</td>
                  <td class="mono">{{ (row.retrievedPosts || []).slice(0, 5).join(', ') }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </template>
    </template>
  </section>
</template>

<style scoped>
.lab-note{font-size:12px; color:var(--ink-faint); line-height:1.9; max-width:72ch; margin-bottom:20px}
/* 页签：与「我的笔记」视图切换同一套观感（下划线用主色，不引入新组件库） */
.lab-tabs{display:flex; gap:18px; border-bottom:1px solid var(--line); margin-bottom:22px}
.lab-tab{border:0; background:transparent; color:var(--ink-faint); font:inherit; font-size:13px;
  padding:10px 2px; cursor:pointer; border-bottom:2px solid transparent;
  transition:color .3s var(--ease-soft), border-color .3s var(--ease-soft)}
.lab-tab:hover{color:var(--ink)}
.lab-tab.on{color:var(--ink); border-bottom-color:var(--primary)}
/* 评测台 */
.eval-setup{margin-bottom:18px}
.eval-row{display:flex; gap:16px; flex-wrap:wrap; align-items:flex-end}
.eval-row .grow{flex:1 1 320px}
.eval-row select{width:100%; background:var(--bg-2); border:1px solid var(--line);
  border-radius:var(--r-sm); color:var(--ink); padding:10px 14px; font:inherit; font-size:13px}
.eval-row select:focus{outline:none; border-color:var(--primary)}
.strategy-row{display:flex; gap:8px; flex-wrap:wrap; padding-top:4px}
.strategy-row .chip{cursor:pointer; border:1px solid var(--line)}
.eval-actions{display:flex; align-items:center; gap:12px; flex-wrap:wrap; margin-top:16px}
.eval-meta{display:flex; align-items:center; gap:10px; flex-wrap:wrap; margin:6px 0 12px}
.meta-item{font-family:var(--font-mono); font-size:11px; color:var(--ink-faint)}
/* notes 用暖色：这是「别把 Fake 的数字当结论」的提示，不能被当成脚注忽略 */
.note-list{margin:0 0 18px; padding:12px 16px; border-left:2px solid var(--amber);
  background:var(--surface); border-radius:0 var(--r-sm) var(--r-sm) 0;
  list-style:none; display:flex; flex-direction:column; gap:6px}
.note-list li{font-size:12px; color:var(--ink-dim); line-height:1.8}
.table-wrap{overflow-x:auto; border:1px solid var(--line); border-radius:var(--r-md);
  background:var(--surface); margin-bottom:26px}
.eval-table{width:100%; border-collapse:collapse; font-size:12px}
.eval-table th,.eval-table td{padding:10px 12px; text-align:left; border-bottom:1px solid var(--line);
  white-space:nowrap}
.eval-table thead th{font-family:var(--font-mono); font-size:11px; color:var(--ink-faint);
  letter-spacing:.06em; font-weight:400}
.eval-table tbody tr:last-child td{border-bottom:0}
.eval-table .num{font-family:var(--font-mono); text-align:right; color:var(--ink-dim)}
.strategy-cell{display:flex; flex-direction:column; gap:2px}
.strategy-cell b{font-weight:500}
.strategy-cell span{font-size:11px; color:var(--ink-faint); white-space:normal}
.case-head{display:flex; align-items:center; gap:12px; flex-wrap:wrap; margin-bottom:12px}
.case-head h5{font-size:14px; font-weight:500; margin:0}
.case-select{background:var(--bg-2); border:1px solid var(--line); border-radius:var(--r-sm);
  color:var(--ink); padding:6px 10px; font:inherit; font-size:12px}
.case-toggle{display:flex; align-items:center; gap:6px; font-size:12px; color:var(--ink-faint); cursor:pointer}
.case-table .question-cell{white-space:normal; min-width:260px; color:var(--ink-dim)}
.mono{font-family:var(--font-mono); color:var(--ink-faint)}
.role-grid{display:grid; grid-template-columns:repeat(auto-fill,minmax(260px,1fr)); gap:14px; margin-bottom:26px}
.role-card{border:1px solid var(--line); border-radius:var(--r-md); background:var(--surface);
  padding:18px; display:flex; flex-direction:column; gap:8px}
.role-head{display:flex; align-items:center; justify-content:space-between; gap:8px}
.role-head b{font-size:15px; font-weight:500}
.role-hint{font-size:12px; color:var(--ink-faint); line-height:1.7}
.role-meta{display:flex; flex-direction:column; gap:4px; margin:2px 0 0}
.role-meta div{display:flex; gap:8px; font-size:11px}
.role-meta dt{color:var(--ink-faint); min-width:32px}
.role-meta dd{font-family:var(--font-mono); color:var(--ink-dim); word-break:break-all}
.check-line{font-size:11px; color:var(--teal)}
.check-line.bad{color:var(--amber)}
.role-actions{display:flex; gap:8px; flex-wrap:wrap; margin-top:auto; padding-top:10px}
.role-actions .btn{padding:6px 12px; font-size:12px}
.role-actions .danger{color:var(--rose); border-color:var(--line)}
.form-hint{font-size:11px; line-height:1.8; color:var(--ink-faint); margin:12px 0 16px}
.form-hint.tight{margin:0}
/* 模型库与角色卡片上的下拉选择 */
.role-pick{display:flex; gap:8px; align-items:center; margin-top:2px}
.role-select{
  flex:1; min-width:0; background:var(--bg-2); border:1px solid var(--line); border-radius:var(--r-sm);
  color:var(--ink); padding:6px 10px; font:inherit; font-size:12px
}
.role-select:focus{outline:none; border-color:var(--primary)}
.role-pick .btn{padding:6px 12px; font-size:12px; white-space:nowrap}
.bound-line{font-size:11px; color:var(--teal); line-height:1.7}
.bound-line b{font-weight:500}
.model-lib{margin-bottom:26px}
.lib-head{display:flex; align-items:baseline; gap:10px; flex-wrap:wrap}
.lib-head h5{font-size:15px; font-weight:500; margin:0}
.lib-head .btn{margin-left:auto; padding:6px 12px; font-size:12px}
.model-list{display:flex; flex-direction:column; gap:12px; margin-top:14px}
.model-row{
  border:1px solid var(--line); border-radius:var(--r-sm); background:var(--bg-2);
  padding:12px 14px; display:flex; flex-direction:column; gap:6px
}
.model-main{display:flex; align-items:baseline; gap:10px; flex-wrap:wrap}
.model-main b{font-size:13px; font-weight:500}
.model-sub{font-family:var(--font-mono); font-size:11px; color:var(--ink-faint); word-break:break-all}
.model-tags{display:flex; align-items:center; gap:6px; flex-wrap:wrap}
.model-tags .chip{font-size:10px; padding:2px 8px}
.model-mask{font-family:var(--font-mono); font-size:11px; color:var(--ink-faint)}
.model-bound{font-size:11px; color:var(--ink-faint)}
.model-row .role-actions{padding-top:4px; margin-top:0}
.lib-form{margin-top:18px; border-top:1px solid var(--line); padding-top:16px}
.lib-form h5{font-size:14px; font-weight:500; margin:0 0 4px}
.cap-row{display:flex; gap:14px; flex-wrap:wrap; padding:8px 0}
.cap-item{display:flex; align-items:center; gap:6px; font-size:12px; color:var(--ink-dim); cursor:pointer}
.form-grid{display:grid; grid-template-columns:repeat(auto-fill,minmax(220px,1fr)); gap:0 14px}
.form-grid .wide{grid-column:1/-1}
.form-grid select{
  width:100%; background:var(--bg-2); border:1px solid var(--line); border-radius:var(--r-sm);
  color:var(--ink); padding:10px 14px; font:inherit; font-size:13px; transition:border-color .3s var(--ease-soft)
}
.form-grid select:focus{outline:none; border-color:var(--primary)}
.form-actions{display:flex; align-items:center; gap:12px; flex-wrap:wrap; margin-top:6px}
.form-hint{font-size:11px; color:var(--ink-faint)}
.state-text{color:var(--ink-faint); font-size:13px; line-height:1.8}
.state-action{border:0; background:transparent; color:var(--primary); cursor:pointer; font:inherit}
.error-text{color:var(--rose)}
</style>
