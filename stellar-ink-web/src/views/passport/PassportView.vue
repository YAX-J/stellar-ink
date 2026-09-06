<script setup>
import { useSettingsStore } from '@/stores/settings'
import SectionHead from '@/components/common/SectionHead.vue'

const settings = useSettingsStore()

async function copyRss() {
  try {
    await navigator.clipboard.writeText(`${location.origin}/rss.xml`)
  } catch {
    /* 剪贴板不可用时静默 */
  }
}
</script>

<template>
  <section class="page">
    <div class="kicker reveal">STELLAR PASSPORT · 不是关于页，是一本星籍</div>
    <SectionHead title="星籍档案" more="签发机关：深夜书房" />

    <div class="passport">
      <div class="pp-card reveal" style="--d:.08s">
        <div class="stamps">
          <div class="stamp">142 星<br>已点亮</div>
          <div class="stamp g">连续<br>21 夜</div>
        </div>
        <div class="pp-head">
          <div class="avatar">星</div>
          <div>
            <b style="font-size:20px">{{ settings.penName }}</b>
            <div style="font-family:var(--font-mono);font-size:11px;color:var(--ink-faint);margin-top:5px">STELLAR PASSPORT</div>
          </div>
        </div>
        <div class="pp-rows">
          <div><span>星籍编号</span><b>NO.ST-2024-0307</b></div>
          <div><span>注册星历</span><b>2024.03.07 · 第 1 夜</b></div>
          <div><span>当前坐标</span><b>中国 · 某座有便利店的城市</b></div>
          <div><span>职业轨道</span><b>白天 HR · 深夜写作者</b></div>
          <div><span>信仰</span><b>缓慢、诚实、长句子</b></div>
        </div>
      </div>

      <div class="pp-card reveal" style="--d:.14s">
        <p class="pp-motto">“在算法的洪流里，做一个<em>缓慢的人</em>。写长长的句子，爱具体的人。”</p>
        <div class="now-grid">
          <div class="now-cell"><small>♪ 正在循环</small><b>坂本龍一《async》</b><div class="bar"><i style="width:62%"></i></div></div>
          <div class="now-cell"><small>✉ 正在慢读</small><b>《夜航西飞》第三遍</b><div class="bar"><i style="width:45%"></i></div></div>
          <div class="now-cell"><small>☕ 今日摄入</small><b>3 杯美式 · 超标</b><div class="bar"><i style="width:88%"></i></div></div>
          <div class="now-cell"><small>☾ 本周写作</small><b>4 夜 · 6,820 字</b><div class="bar"><i style="width:74%"></i></div></div>
        </div>
      </div>
    </div>

    <SectionHead title="站点大事记" more="一部小型星际移民史" />
    <div class="his-list reveal" style="--d:.08s">
      <div class="his-item"><small>2024.03</small><p>星笺 v1 上线，纯静态 HTML，第一颗星《搬家：物品的告别式》点亮。</p></div>
      <div class="his-item"><small>2024.11</small><p>第 50 颗星点亮，第一次收到陌生读者的长邮件，决定一直写下去。</p></div>
      <div class="his-item"><small>2025.08</small><p>星图归档上线，正式弃用列表式归档 —— 文章从此有了坐标。</p></div>
      <div class="his-item"><small>2026.02</small><p>星尘突破 150K 字，相当于写完一本不太厚的书。</p></div>
      <div class="his-item"><small>2026.09</small><p>v3「留白写作舱」发布：情绪环境光、专注模式、漂流瓶回声。</p></div>
    </div>

    <SectionHead title="信标" more="向这些频率发信号，会收到回复" />
    <div class="beacon-row reveal" style="--d:.08s">
      <a class="beacon" href="mailto:hi@stellar.ink">📮 hi@stellar.ink</a>
      <button class="beacon" @click="copyRss">✦ RSS 订阅</button>
      <a class="beacon" href="https://github.com" target="_blank" rel="noreferrer">⬡ GitHub</a>
      <button class="beacon">☄ 即刻 @拾星人</button>
    </div>
  </section>
</template>

<style scoped>
.passport{display:grid; grid-template-columns:1fr 1.2fr; gap:18px}
.pp-card{border:1px solid var(--line); border-radius:var(--r-lg);
  background:linear-gradient(140deg,var(--bg-2),var(--bg-3)); padding:32px;
  position:relative; overflow:hidden}
.pp-card::after{content:''; position:absolute; right:-60px; bottom:-60px; width:200px; height:200px;
  border-radius:50%; border:1px dashed var(--primary); opacity:.4}
.pp-head{display:flex; gap:18px; align-items:center; margin-bottom:24px}
.pp-rows{display:flex; flex-direction:column; gap:12px; font-size:13px}
.pp-rows div{display:flex; justify-content:space-between; border-bottom:1px dashed var(--line); padding-bottom:10px}
.pp-rows span{color:var(--ink-faint); font-family:var(--font-mono); font-size:11px; letter-spacing:.1em}
.pp-rows b{font-weight:500}
.stamps{position:absolute; top:24px; right:24px; display:flex; flex-direction:column; gap:10px}
.stamp{width:58px; height:58px; border:2px dashed var(--rose); border-radius:50%; display:grid;
  place-items:center; font-size:9px; color:var(--rose); transform:rotate(14deg);
  letter-spacing:.1em; text-align:center; line-height:1.5; opacity:.75}
.stamp.g{border-color:var(--teal); color:var(--teal); transform:rotate(-10deg)}
.pp-motto{font-family:var(--font-serif); font-size:clamp(19px,2.4vw,26px); font-weight:600;
  line-height:2; margin-bottom:26px}
.pp-motto em{font-style:normal; color:var(--amber)}
.now-grid{display:grid; grid-template-columns:1fr 1fr; gap:12px}
.now-cell{border:1px solid var(--line); border-radius:var(--r-md); background:var(--surface); padding:16px}
.now-cell small{font-family:var(--font-mono); font-size:10px; letter-spacing:.2em;
  color:var(--ink-faint); display:block; margin-bottom:8px}
.now-cell b{font-size:14px; font-weight:500; line-height:1.6}
.now-cell .bar{height:4px; border-radius:2px; background:var(--surface-2); margin-top:12px; overflow:hidden}
.now-cell .bar i{display:block; height:100%; border-radius:2px;
  background:linear-gradient(90deg,var(--teal),var(--primary))}
.his-list{position:relative; padding-left:26px; border-left:1px solid var(--line);
  display:flex; flex-direction:column; gap:22px}
.his-item{position:relative}
.his-item::before{content:''; position:absolute; left:-31px; top:6px; width:9px; height:9px;
  border-radius:50%; background:var(--primary); box-shadow:0 0 10px var(--primary)}
.his-item small{font-family:var(--font-mono); font-size:11px; color:var(--amber); letter-spacing:.15em}
.his-item p{font-size:14px; color:var(--ink-dim); margin-top:5px; line-height:1.8}
.beacon-row{display:flex; gap:12px; flex-wrap:wrap}
.beacon{border:1px solid var(--line); border-radius:99px; padding:11px 22px; font-size:13px;
  color:var(--ink-dim); cursor:pointer; background:var(--surface);
  transition:all .3s var(--ease-spring); font-family:var(--font-body); text-decoration:none}
.beacon:hover{transform:translateY(-3px); border-color:var(--amber); color:var(--amber)}

@media (max-width:980px){
  .passport{grid-template-columns:1fr}
}
@media (max-width:720px){
  .now-grid{grid-template-columns:1fr}
}
</style>
