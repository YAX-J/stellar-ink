<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useQaEvalStore } from '@/stores/qaEval'
import QaCitationList from './QaCitationList.vue'

const props = defineProps({
  datasets: { type: Array, default: () => [] },
  loading: Boolean,
  metaError: { type: String, default: '' },
  decisionConfigured: Boolean,
})
defineEmits(['reload', 'configure-decision'])
const auth = useAuthStore()
const qa = useQaEvalStore()
const dataset = ref('')
const limit = ref('all')
const note = ref('')
const needsHumanOnly = ref(false)
const selected = computed(() => qa.selectedCase)
const summary = computed(() => qa.run?.summary || {})
const modelSummary = computed(() => qa.run?.modelSummary || {})
const busy = computed(() => qa.running || qa.judging || qa.savingReview || qa.loadingReport)
const visibleCases = computed(() => qa.run?.cases.filter((row) => !needsHumanOnly.value
  || row.modelReview?.needsHumanReview) || [])
watch(visibleCases, (rows) => {
  if (!rows.some((row) => row.caseId === qa.selectedCaseId)) qa.selectedCaseId = rows[0]?.caseId || ''
})
const judgedTotal = computed(() => qa.run?.cases.filter((row) => row.status === 'completed').length || 0)
const selectedDataset = computed(() => props.datasets.find((item) => item.id === dataset.value))
const factAnswer = computed(() => selected.value?.answer?.doneReason === 'stop'
  && selected.value?.answer?.evidenceSufficient === true)
watch(() => auth.user?.id, (id) => {
  qa.resetForUser(id ?? null)
  if (id != null) qa.loadLatest({ onlyIfEmpty: true })
}, { immediate: true })
watch(() => props.datasets, (items) => {
  if (!items.some((item) => item.id === dataset.value)) {
    dataset.value = items.find((item) => item.id === 'acceptance_v1')?.id || items[0]?.id || ''
  }
}, { immediate: true })
watch(() => selected.value?.review?.note, (value) => { note.value = value || '' }, { immediate: true })
watch(() => selected.value?.caseId, () => { note.value = selected.value?.review?.note || '' })
onBeforeUnmount(() => qa.stop())

const percent = (value) => value == null ? '待复核' : `${(value * 100).toFixed(1)}%`
const reviewValue = (value) => value == null ? 'pending' : value ? 'yes' : 'no'
const savedTime = computed(() => qa.run?.reportSavedAt
  ? new Date(qa.run.reportSavedAt).toLocaleString('zh-CN', { hour12: false }) : '')
function save(field, event) {
  const value = event.target.value === 'pending' ? null : event.target.value === 'yes'
  qa.saveReview(selected.value, field, value, note.value)
}
function status(row) {
  if (qa.judgingCaseId === row.caseId) return '正在模型复核'
  if (qa.inFlightCaseId === row.caseId) return '正在回答'
  if (row.status === 'error') return '执行失败'
  if (row.status !== 'completed') return '待执行'
  if (row.answer?.doneReason === 'length') return '预算用尽'
  return row.answer?.doneReason === 'refused' ? '已拒答' : '已回答'
}
function exportRun() {
  const url = URL.createObjectURL(new Blob([JSON.stringify(qa.run, null, 2)], { type: 'application/json' }))
  const link = document.createElement('a')
  link.href = url
  link.download = `qa-eval-${qa.run.runId}.json`
  link.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <div class="qa-eval-panel">
    <p class="intro">逐题生成实际答案，检查引用原文，再由你复核答案是否正确、引用是否支持结论。</p>
    <div class="saved-report side-card">
      <div><b>最近报告</b><p class="hint">查看已完成的问答、引用和模型复核结果，无需重新调用模型。</p></div>
      <button class="btn" type="button" :disabled="busy" @click="qa.loadLatest()">{{ qa.loadingReport ? '正在读取报告…' : '查看最近报告' }}</button>
      <p v-if="qa.reportError" class="error-text" role="alert">{{ qa.reportError }}</p>
      <p v-else-if="qa.reportLoaded && !qa.hasSavedReport && !qa.run?.reportSavedAt" class="hint">暂无已保存报告，完整执行结束后会保存最近一轮结果。</p>
    </div>
    <p v-if="loading" class="state-text">正在读取数据集…</p>
    <p v-else-if="metaError" class="state-text error-text">
      {{ metaError }} <button class="state-action" type="button" @click="$emit('reload')">重新读取</button>
    </p>
    <div v-else class="setup side-card">
      <div class="setup-fields">
        <div class="field">
          <label for="qa-eval-dataset">数据集</label>
          <select id="qa-eval-dataset" v-model="dataset" :disabled="busy">
            <option v-for="item in datasets" :key="item.id" :value="item.id">{{ item.name }}（{{ item.cases }} 题）</option>
          </select>
        </div>
        <div class="field">
          <label for="qa-eval-limit">本轮题数</label>
          <select id="qa-eval-limit" v-model="limit" :disabled="busy">
            <option value="all">全部 {{ selectedDataset?.cases || '' }} 题</option>
            <option value="3">先跑 3 题</option>
          </select>
        </div>
      </div>
      <div class="actions">
        <button class="btn btn-primary" type="button" :disabled="busy || !dataset" @click="qa.start(dataset, limit === 'all' ? null : 3)">
          {{ qa.running ? '正在逐题执行…' : qa.run ? '重新开始问答验收' : '开始完整问答验收' }}
        </button>
        <button v-if="qa.running" class="btn" type="button" :disabled="qa.stopRequested" @click="qa.stop()">{{ qa.stopRequested ? '等待当前题结束…' : '停止后续题目' }}</button>
        <button v-else-if="qa.hasPending" class="btn" type="button" :disabled="busy" @click="qa.resume()">继续剩余题目</button>
        <span class="hint">使用当前问答配置，会调用生成模型并产生用量。</span>
      </div>
    </div>
    <p v-if="qa.error" class="error-text" role="alert">
      {{ qa.error }} <span v-if="qa.traceId" class="mono">追踪：{{ qa.traceId }}</span>
      <button v-if="qa.run && !busy" class="state-action" type="button" @click="qa.refresh()">重新读取本轮结果</button>
    </p>
    <template v-if="qa.run">
      <p v-if="qa.run.reportError" class="error-text" role="alert">{{ qa.run.reportError }}</p>
      <p v-else-if="savedTime" class="hint">{{ qa.reportView ? '已载入保存的报告' : '本轮报告已保存' }} · {{ savedTime }} · 人工复核可继续保存。仅保留最近保存的一轮，历史报告请导出。</p>
      <p v-if="qa.run.executionAvailable === false" class="hint">本报告保留了完整结果；重新生成或模型复核请新开一轮，下面的人工复核仍可继续。</p>
      <div class="progress-head" aria-live="polite">
        <b>{{ qa.run.datasetLabel }} · 已完成 {{ qa.run.completed }} / {{ qa.run.total }}</b>
        <span>{{ qa.judging ? `正在模型复核 ${qa.judgingCaseId}` : qa.running ? qa.stopRequested ? '当前题完成后停止' : '逐题执行中' : qa.hasPending ? '已停止后续题目' : '本轮执行结束' }}</span>
        <button class="state-action" type="button" :disabled="busy" @click="exportRun">导出本轮结果</button>
      </div>
      <progress :value="qa.run.completed" :max="qa.run.total" aria-label="完整问答评测进度" />
      <section class="model-judge" aria-label="决策模型复核">
        <div class="actions">
          <button class="btn" type="button" :disabled="busy || qa.run.executionAvailable === false || !decisionConfigured || !judgedTotal || modelSummary.reviewed >= judgedTotal" @click="qa.judge()">{{ qa.judging ? '正在逐题模型复核…' : '模型复核未审题目' }}</button>
          <button v-if="qa.judging" class="btn" type="button" :disabled="qa.stopJudgeRequested" @click="qa.stop()">{{ qa.stopJudgeRequested ? '等待本题复核结束…' : '停止后续复核' }}</button>
          <button v-if="!decisionConfigured" class="state-action" type="button" @click="$emit('configure-decision')">配置决策模型</button>
          <span class="hint">已模型复核 {{ modelSummary.reviewed || 0 }} / {{ judgedTotal }} · 需人工关注 {{ modelSummary.needsHuman || 0 }} 题</span>
        </div>
        <p v-if="!decisionConfigured" class="hint">请先配置 decision 角色；复核会调用该模型并产生用量。</p>
        <p v-else class="hint">使用 decision 角色独立判定，概率不足 80% 或不确定时保留待人工。概率尚未标定，模型通过不等于人工验收通过。</p>
        <p class="hint">模型判定答案通过率：{{ percent(modelSummary.answerPassRate) }}（明确判定 {{ modelSummary.conclusive || 0 }} 题） · 引用支持通过率：{{ percent(modelSummary.citationPassRate) }}（明确判定 {{ modelSummary.supportReviewed || 0 }} 题） · 复核用量：{{ modelSummary.totalTokens || 0 }} Token</p>
      </section>
      <div class="summary-strip">
        <div><span>自动检查 · 原文一致</span><b>{{ summary.returnedCitations ? `${summary.sourceMatched} / ${summary.returnedCitations}` : '暂无引用' }}</b></div>
        <div><span>人工复核 · 答案正确率</span><b>{{ percent(summary.answerAccuracy) }}</b><small>已复核 {{ summary.correctReviewed || 0 }} / {{ qa.run.completed }}</small></div>
        <div><span>人工复核 · 引用支持率</span><b>{{ percent(summary.citationSupportRate) }}</b><small>已复核 {{ summary.supportReviewed || 0 }} 道事实作答题</small></div>
        <div><span>已回报用量</span><b>{{ summary.totalTokens || 0 }} Token</b><small>执行失败 {{ summary.errors || 0 }} 题</small></div>
      </div>
      <p class="hint">自动统计：无答案题已拒答 {{ summary.unanswerableRefused || 0 }} / {{ summary.unanswerableCompleted || 0 }}，有答案题误拒 {{ summary.falseRefusals || 0 }}，预算用尽 {{ summary.truncated || 0 }}，引用编号越界 {{ summary.outOfRange || 0 }}。</p>
      <p class="hint">正确率只统计已复核题。原文一致说明片段真实存在，不代表它支持答案。完整执行的最近报告会保存；未完成的临时运行闲置一小时或服务重启后过期。</p>
      <label class="case-filter"><input v-model="needsHumanOnly" type="checkbox">只看模型建议人工关注的题目（{{ modelSummary.needsHuman || 0 }}）</label>
      <div class="case-workspace">
        <div class="case-list" aria-label="完整问答逐题列表">
          <p v-if="!visibleCases.length" class="hint">没有符合筛选条件的题目。</p>
          <button v-for="row in visibleCases" :key="row.caseId" type="button" class="case-item" :class="{ selected: row.caseId === qa.selectedCaseId }" :aria-pressed="row.caseId === qa.selectedCaseId" @click="qa.selectedCaseId = row.caseId">
            <span class="case-meta"><b class="mono">{{ row.caseId }}</b><span>{{ status(row) }}</span></span>
            <span>{{ row.question }}</span>
            <small>{{ row.review.correct == null ? '答案待复核' : row.review.correct ? '复核：正确' : '复核：不正确' }}</small>
            <small v-if="row.modelReview">模型复核：{{ row.modelReview.needsHumanReview ? '需人工关注' : '通过' }}</small>
          </button>
        </div>
        <article v-if="selected" class="case-detail">
          <div class="detail-head"><span class="chip cool">{{ selected.expectedPostIds.length ? '有答案题' : '无答案题' }}</span><span class="mono">{{ selected.caseId }}</span></div>
          <h4>{{ selected.question }}</h4>
          <details class="reference"><summary>查看参考答案</summary><p>{{ selected.expectedAnswer || '文章中未提供所问事实，应明确拒答。' }}</p></details>
          <p v-if="selected.status === 'pending'" class="state-text">本题尚未执行。</p>
          <p v-else-if="selected.status === 'error'" class="error-text">{{ selected.error }} <span class="mono">{{ selected.traceId }}</span></p>
          <template v-if="selected.answer">
            <p class="answer-state">实际结果：{{ status(selected) }} · 返回标记：{{ selected.answer.evidenceSufficient ? '依据充分' : '依据不足' }}</p>
            <p class="hint mono">{{ selected.answer.usage.model || '本题未调用生成模型' }} · {{ selected.answer.usage.totalTokens }} Token{{ selected.answer.usage.usageComplete === false ? "（用量不完整）" : "" }} · {{ selected.answer.usage.latencyMs }} ms</p>
            <p class="actual-answer">{{ selected.answer.answer }}</p>
            <div class="automatic">
              <b>自动检查</b>
              <span>原文一致 {{ selected.automatic.sourceMatched }} / {{ selected.answer.citations.length }}</span>
              <span>编号越界 {{ selected.automatic.outOfRange.length }} 个</span>
              <span v-if="selected.automatic.uncited">{{ selected.answer.doneReason === 'refused' ? '纯拒答未标编号（信息项）' : '正文未标引用编号' }}</span>
            </div>
            <div v-if="selected.answer.citations.length" class="evidence-list">
              <h5>引用原文</h5>
              <QaCitationList :citations="selected.answer.citations" expandable />
            </div>
            <p v-else class="hint">本题没有返回引用。</p>
            <section class="model-judge">
              <h5>模型复核</h5>
              <button class="btn" type="button" :disabled="busy || qa.run.executionAvailable === false || !decisionConfigured" @click="qa.judge(selected.caseId)">{{ selected.modelReview ? '重新模型复核本题' : '模型复核本题' }}</button>
              <template v-if="selected.modelReview">
                <p class="hint mono">{{ selected.modelReview.model }} · {{ selected.modelReview.usage.totalTokens }} Token · {{ selected.modelReview.rubricVersion }}</p>
                <p>{{ selected.modelReview.needsHumanReview ? '模型判定需人工关注' : '模型判定通过' }}</p>
                <p v-for="(decision, key) in selected.modelReview.decisions" :key="key" class="hint">
                  {{ key === 'answerCorrect' ? '答案正确完整' : '引用支持结论' }}：{{ { pass: '倾向通过', fail: '倾向不通过', uncertain: '不确定' }[decision.choice] }}。
                  选项概率：通过 {{ (decision.probabilities.pass * 100).toFixed(1) }}%，不通过 {{ (decision.probabilities.fail * 100).toFixed(1) }}%，不确定 {{ (decision.probabilities.uncertain * 100).toFixed(1) }}%。
                </p>
                <p class="hint">模型结果单独保存，下面的人工复核仍由你确认或修正。</p>
              </template>
            </section>
            <fieldset class="review-fields" :disabled="busy">
              <legend>人工复核</legend>
              <p class="hint">结合参考答案与原文判断；有编号、片段真实，都不能自动证明结论正确。</p>
              <div class="field"><label for="qa-eval-correct">答案是否正确、完整？</label>
                <select id="qa-eval-correct" :value="reviewValue(selected.review.correct)" @change="save('correct', $event)"><option value="pending">待复核</option><option value="yes">正确且完整</option><option value="no">不正确或不完整</option></select>
              </div>
              <div v-if="factAnswer" class="field"><label for="qa-eval-support">正文引用是否支持所有实质结论？</label>
                <select id="qa-eval-support" :value="reviewValue(selected.review.citationsSupported)" @change="save('citationsSupported', $event)"><option value="pending">待复核</option><option value="yes">全部支持</option><option value="no">存在不支持的结论</option></select>
              </div>
              <p v-else class="hint">拒答或未完整结束的回答不计入事实引用支持率，请在答案正确性中复核。</p>
              <div class="field"><label for="qa-eval-review-note">复核备注（可选）</label><textarea id="qa-eval-review-note" v-model="note" maxlength="1000" rows="3" /></div>
              <button class="btn" type="button" @click="qa.saveReview(selected, null, null, note)">{{ qa.savingReview ? '保存中…' : '保存复核备注' }}</button>
            </fieldset>
            <p v-if="busy" class="hint">本轮停止或执行结束后可保存人工复核。</p>
          </template>
        </article>
      </div>
    </template>
  </div>
</template>

<style scoped>
.intro,.hint{color:var(--ink-faint);font-size:12px;line-height:1.9}.intro{margin:0 0 20px;max-width:72ch}
.saved-report{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;margin-bottom:24px}.saved-report b{font-size:14px;font-weight:500}.saved-report .hint{margin:7px 0 0}.saved-report .error-text{flex-basis:100%}.case-filter{display:flex;align-items:center;gap:8px;font-size:12px;color:var(--ink-faint);margin:22px 0 0}.case-filter input{accent-color:var(--primary)}
.model-judge{padding:14px 0;border-block:1px solid var(--line);margin-bottom:18px}.model-judge .actions{margin-top:0}.model-judge h5{margin:0 0 14px}
.mono{font-family:var(--font-mono)}.state-text{margin:18px 0;color:var(--ink-faint);font-size:13px}.error-text{color:var(--rose);font-size:13px;overflow-wrap:anywhere}.state-action{border:0;background:transparent;color:var(--primary);font:inherit;font-size:12px;padding:4px 0;cursor:pointer}.state-action:disabled{opacity:.5;cursor:not-allowed}
.setup{margin-bottom:20px}.setup-fields,.actions{display:flex;gap:18px;align-items:end;flex-wrap:wrap}.setup-fields .field:first-child{flex:1;min-width:220px}.actions{margin-top:20px;align-items:center}
.field select{width:100%;min-height:44px;padding:10px 14px;border:1px solid var(--line);border-radius:var(--r-sm);background:var(--bg-2);color:var(--ink);font:inherit;font-size:13px}.field select:focus{border-color:var(--primary)}.field select:disabled{opacity:.65;cursor:not-allowed}
.progress-head{display:flex;gap:16px;align-items:center;flex-wrap:wrap;margin:28px 0 10px;font-size:12px}.progress-head span{color:var(--ink-faint)}.progress-head button{margin-left:auto}
progress{display:block;width:100%;height:5px;accent-color:var(--primary);margin-bottom:24px}
.summary-strip{display:grid;grid-template-columns:repeat(4,1fr);border-block:1px solid var(--line);padding:18px 0;gap:20px}.summary-strip div{display:flex;flex-direction:column;gap:7px}.summary-strip span,.summary-strip small{font-size:11px;color:var(--ink-faint)}.summary-strip b{font-size:20px;font-weight:500;color:var(--ink)}
.case-workspace{display:grid;grid-template-columns:minmax(230px, .8fr) minmax(0,2fr);gap:28px;margin-top:26px;align-items:start}.case-list{max-height:720px;overflow:auto;border-block:1px solid var(--line)}
.case-item{display:flex;flex-direction:column;gap:9px;width:100%;padding:16px 14px;border:0;border-bottom:1px solid var(--line);border-left:2px solid transparent;background:transparent;text-align:left;color:var(--ink);font:inherit;font-size:12px;line-height:1.7;cursor:pointer}.case-item:hover{background:var(--bg-2)}.case-item.selected{border-left-color:var(--primary);background:var(--bg-2)}.case-item small,.case-meta{font-size:10px;color:var(--ink-faint)}.case-meta{display:flex;justify-content:space-between;gap:12px}.case-meta b{color:var(--primary)}
.detail-head{display:flex;align-items:center;gap:12px;font-size:11px;color:var(--ink-faint)}h4{font-size:17px;line-height:1.8;margin:12px 0 16px;font-weight:500}.reference,.evidence-list details{padding:13px 0;border-bottom:1px solid var(--line)}summary{cursor:pointer;font-size:12px;line-height:1.8;color:var(--ink)}summary span{color:var(--ink-faint);font-size:10px}.reference p,.evidence-list p{font-size:12px;line-height:1.9;white-space:pre-wrap;overflow-wrap:anywhere;color:var(--ink-dim)}.evidence-list a{font-size:11px;color:var(--primary)}h5{font-size:13px;font-weight:500;margin:24px 0 4px}.actual-answer{white-space:pre-wrap;overflow-wrap:anywhere;font-size:14px;line-height:2;color:var(--ink);margin:16px 0 24px}.answer-state{font-size:11px;color:var(--ink-faint);margin-top:24px}.automatic{display:flex;gap:12px;flex-wrap:wrap;font-size:11px;padding:12px 0;border-block:1px solid var(--line);color:var(--ink-faint)}.automatic b{color:var(--teal);font-weight:500}
.review-fields{border:0;border-top:1px solid var(--line);padding:20px 0 0;margin:28px 0 0}.review-fields legend{padding:0 10px 0 0;font-size:13px;color:var(--ink)}.review-fields .field{margin-top:16px}.review-fields .btn{margin-top:14px}.review-fields:disabled{opacity:.65}
@media(max-width:800px){.summary-strip{grid-template-columns:repeat(2,1fr)}.case-workspace{grid-template-columns:1fr}.case-list{max-height:280px}.progress-head button{margin-left:0}}
</style>
