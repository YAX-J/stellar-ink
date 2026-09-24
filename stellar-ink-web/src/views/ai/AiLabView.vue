<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { AI_ROLES, PROVIDER_PRESETS, useAiStore } from '@/stores/ai'
import { emit, TOAST } from '@/utils/bus'
import SectionHead from '@/components/common/SectionHead.vue'

const auth = useAuthStore()
const ai = useAiStore()

/** 当前展开编辑的角色 */
const editing = ref('')
const saving = ref(false)

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

onMounted(() => {
  if (auth.isAdmin) ai.fetchProviders().catch(() => {})
})

function applyPreset(preset) {
  form.baseUrl = preset.baseUrl
  form.provider = preset.key === 'fake' ? 'fake' : 'openai_compatible'
  /* 预设只覆盖它擅长的角色：给 embedding 选 DeepSeek 不该把对话模型名塞进来 */
  const suggested = preset.models[editing.value]
  form.model = suggested || form.model
  if (preset.key === 'fake') form.model = form.model || 'fake'
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

        <div class="preset-row">
          <span class="preset-label">快速填入</span>
          <button
            v-for="preset in PROVIDER_PRESETS" :key="preset.key"
            class="chip" type="button" @click="applyPreset(preset)"
          >
            {{ preset.label }}
          </button>
        </div>

        <div class="form-grid">
          <div class="field">
            <label>展示名</label>
            <input v-model="form.displayName" maxlength="64" placeholder="如 DeepSeek Chat">
          </div>
          <div class="field">
            <label>协议</label>
            <select v-model="form.provider">
              <option value="openai_compatible">OpenAI 兼容</option>
              <option value="fake">Fake（离线自测）</option>
            </select>
          </div>
          <div class="field wide">
            <label>接口地址 baseUrl</label>
            <input v-model="form.baseUrl" maxlength="255" placeholder="https://api.deepseek.com/v1">
          </div>
          <div class="field">
            <label>模型名</label>
            <input v-model="form.model" maxlength="128" placeholder="deepseek-chat / BAAI/bge-m3">
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
            <input v-model="form.dimension" type="number" min="1" max="8192" placeholder="bge-m3 为 1024">
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
  </section>
</template>

<style scoped>
.lab-note{font-size:12px; color:var(--ink-faint); line-height:1.9; max-width:72ch; margin-bottom:20px}
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
.preset-row{display:flex; align-items:center; gap:8px; flex-wrap:wrap; margin:12px 0 16px}
.preset-row .chip{cursor:pointer; border:1px solid var(--line)}
.preset-label{font-family:var(--font-mono); font-size:11px; color:var(--ink-faint); letter-spacing:.2em}
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
