<script setup>
import { computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import { usePostStore } from '@/stores/posts'
import SectionHead from '@/components/common/SectionHead.vue'

const router = useRouter()
const settings = useSettingsStore()
const postStore = usePostStore()

const themes = [
  { key: 'night', name: '永夜', desc: '深夜书房 · 默认', sw: 'sw-night' },
  { key: 'dusk', name: '暮光', desc: '黄昏紫调', sw: 'sw-dusk' },
  { key: 'dawn', name: '破晓', desc: '纸感浅色', sw: 'sw-dawn' },
]

const RING_C = 2 * Math.PI * 64
const goalOffset = computed(() => RING_C * (1 - settings.dailyGoal / 2000))

watch(() => [settings.penName, settings.signature], () => settings.persist())

function exportMarkdown() {
  const md = postStore.posts
    .map((p) => `# ${p.title}\n\n- 日期：${p.date}\n- 字数：${p.words}\n- 标签：${p.tags.map((t) => '#' + t).join(' ')}\n`)
    .join('\n')
  download('stellar-ink-export.md', md)
}
function generateReport() {
  const total = postStore.posts.reduce((a, p) => a + p.words, 0)
  const md = [
    '# 年度星座报告\n',
    `- 文章数：${postStore.posts.length} 篇`,
    `- 累计星尘：${total.toLocaleString()} 字`,
    '- 连续写作：21 夜',
    '- 最亮的星：在算法的洪流里，做一个缓慢的人\n',
  ].join('\n')
  download('stellar-ink-report.md', md)
}
function download(name, text) {
  const blob = new Blob([text], { type: 'text/markdown;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = name
  a.click()
  URL.revokeObjectURL(a.href)
}
async function copyRss() {
  try {
    await navigator.clipboard.writeText(`${location.origin}/rss.xml`)
  } catch {
    /* 剪贴板不可用时静默 */
  }
}
function wipeAll() {
  if (confirm('确定要熄灭整片星图吗？本地演示数据与设置将被清空。')) {
    localStorage.removeItem('stellar-ink-settings')
    location.reload()
  }
}
</script>

<template>
  <section class="page">
    <div class="kicker reveal">COMMAND BRIDGE · 舰桥控制台</div>
    <SectionHead title="舰桥" more="所有改动即时生效" />
    <div class="bridge">
      <div class="panel p-theme reveal" style="--d:.06s">
        <h3>时间主题 · 让博客跟随你的昼夜</h3>
        <div class="theme-row">
          <button
            v-for="t in themes" :key="t.key"
            class="theme-swatch" :class="{ on: settings.theme === t.key }" @click="settings.setTheme(t.key)"
          >
            <div class="swatch-sky" :class="t.sw"></div>
            <b>{{ t.name }}</b>
            <small>{{ t.desc }}</small>
          </button>
        </div>
      </div>

      <div class="panel p-preview reveal" style="--d:.12s">
        <h3>实时预览</h3>
        <div class="preview-mock">
          <div class="pm-bar"><i></i><i></i><i></i></div>
          <div class="pm-body">
            <div class="pm-title">在算法的洪流里，做一个缓慢的人</div>
            <div class="pm-lines"><div></div><div></div><div></div></div>
            <span class="pm-chip">✦ 随笔</span>
          </div>
        </div>
      </div>

      <div class="panel p-id reveal" style="--d:.18s">
        <h3>身份舱</h3>
        <div class="id-row">
          <div class="avatar">星</div>
          <div>
            <b style="font-size:17px">{{ settings.penName }}</b>
            <div style="font-family:var(--font-mono);font-size:11px;color:var(--ink-faint);margin-top:4px">@stellar_ink · 第 128 夜</div>
          </div>
        </div>
        <div class="field">
          <label>笔名</label>
          <input v-model="settings.penName">
        </div>
        <div class="field">
          <label>星图签名</label>
          <textarea v-model="settings.signature" rows="2"></textarea>
        </div>
      </div>

      <div class="panel p-goal reveal" style="--d:.24s">
        <h3>每日星尘目标</h3>
        <div class="goal-ring">
          <svg width="150" height="150" viewBox="0 0 150 150">
            <circle cx="75" cy="75" r="64" fill="none" stroke="var(--surface-2)" stroke-width="10" />
            <circle cx="75" cy="75" r="64" fill="none" stroke="var(--amber)" stroke-width="10"
              stroke-linecap="round" stroke-dasharray="402" :stroke-dashoffset="goalOffset" />
          </svg>
          <div class="goal-num"><b>{{ settings.dailyGoal }}</b><small>字 / 天</small></div>
        </div>
        <input v-model.number="settings.dailyGoal" type="range" min="100" max="2000" step="50" @change="settings.persist()">
      </div>

      <div class="panel p-data reveal" style="--d:.3s">
        <h3>数据舱</h3>
        <div class="data-row"><span>导出全部星尘（Markdown）</span><button class="btn btn-ghost" @click="exportMarkdown">导出</button></div>
        <div class="data-row"><span>生成年度星座报告</span><button class="btn btn-ghost" @click="generateReport">生成</button></div>
        <div class="data-row"><span>RSS 订阅源</span><button class="btn btn-ghost" @click="copyRss">复制</button></div>
        <div class="data-row danger"><span>熄灭整片星图（删除博客）</span><button class="btn btn-ghost danger" @click="wipeAll">慎用</button></div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.bridge{display:grid; grid-template-columns:repeat(12,1fr); gap:18px}
.panel{border:1px solid var(--line); border-radius:var(--r-lg); background:var(--surface);
  padding:clamp(22px,3vw,34px); transition:border-color .3s}
.panel:hover{border-color:color-mix(in srgb, var(--primary) 45%, var(--line))}
.panel h3{font-family:var(--font-mono); font-size:11px; letter-spacing:.3em; color:var(--ink-faint);
  text-transform:uppercase; margin-bottom:22px; display:flex; align-items:center; gap:10px}
.panel h3::before{content:''; width:7px; height:7px; border-radius:50%; background:var(--primary)}
.p-theme{grid-column:span 7}
.p-preview{grid-column:span 5}
.p-id{grid-column:span 5}
.p-goal{grid-column:span 3}
.p-data{grid-column:span 4}
.theme-row{display:flex; gap:14px; flex-wrap:wrap}
.theme-swatch{
  flex:1; min-width:120px; border:2px solid var(--line); border-radius:var(--r-md);
  padding:16px; cursor:pointer; transition:all .3s var(--ease-spring); background:transparent;
  color:var(--ink-dim); font-family:var(--font-body); text-align:left;
}
.theme-swatch:hover{transform:translateY(-3px)}
.theme-swatch.on{border-color:var(--primary); box-shadow:0 8px 26px var(--primary-soft)}
.swatch-sky{height:64px; border-radius:10px; margin-bottom:12px; position:relative; overflow:hidden}
.sw-night{background:linear-gradient(160deg,#0A0A12,#1B1B33)}
.sw-dusk{background:linear-gradient(160deg,#2B1B40,#B65C7A)}
.sw-dawn{background:linear-gradient(160deg,#FBF3E4,#F0C98A)}
.swatch-sky::after{content:''; position:absolute; width:14px; height:14px; border-radius:50%;
  top:12px; right:12px; background:var(--amber); box-shadow:0 0 14px var(--amber)}
.theme-swatch b{display:block; font-size:14px; color:var(--ink); margin-bottom:2px}
.theme-swatch small{font-size:11px}

/* 预览窗 */
.preview-mock{border:1px solid var(--line); border-radius:var(--r-md); overflow:hidden;
  background:var(--bg-2); transition:background .6s}
.pm-bar{display:flex; gap:6px; padding:12px 14px; border-bottom:1px solid var(--line)}
.pm-bar i{width:9px; height:9px; border-radius:50%; background:var(--ink-faint); opacity:.5}
.pm-body{padding:18px}
.pm-title{font-family:var(--font-serif); font-weight:900; font-size:17px; margin-bottom:10px}
.pm-lines div{height:8px; border-radius:4px; background:var(--surface-2); margin-bottom:8px}
.pm-lines div:nth-child(2){width:82%}
.pm-lines div:nth-child(3){width:64%}
.pm-chip{display:inline-block; margin-top:12px; font-size:10px; padding:4px 10px;
  border-radius:99px; background:var(--primary-soft); color:var(--primary)}

.id-row{display:flex; gap:18px; align-items:center; margin-bottom:22px}

/* 目标环 */
.goal-ring{display:grid; place-items:center; position:relative; margin:6px auto 14px; width:150px; height:150px}
.goal-ring svg{transform:rotate(-90deg)}
.goal-num{position:absolute; text-align:center}
.goal-num b{font-family:var(--font-display); font-size:30px; font-weight:700}
.goal-num small{display:block; font-size:10px; color:var(--ink-faint); letter-spacing:.2em}

/* 数据 */
.data-row{display:flex; justify-content:space-between; align-items:center; padding:13px 0;
  border-bottom:1px dashed var(--line); font-size:13px; color:var(--ink-dim); gap:10px}
.data-row:last-child{border:none}
.data-row .btn{height:34px; padding:0 16px; font-size:12px}
.danger{color:var(--rose) !important}

@media (max-width:980px){
  .p-theme,.p-preview,.p-id,.p-goal,.p-data{grid-column:span 12}
}
</style>
