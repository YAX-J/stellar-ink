<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { AI_ROLES, useAiStore } from '@/stores/ai'
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
            <span class="chip" :class="ai.evalResult.models === 'fake' ? 'warm' : 'cool'">
              模型：{{ ai.evalResult.models }}
            </span>
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
