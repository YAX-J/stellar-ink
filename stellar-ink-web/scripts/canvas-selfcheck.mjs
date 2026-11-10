/* 用真实 Vue 生命周期验证画布不在动画帧中测量布局，且减少动效、隐藏和卸载均停帧。 */
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRenderer, h, nextTick, ref, shallowRef } from 'vue'
import { createServer } from 'vite'

const pending = new Map()
let id = 0, layoutReads = 0, paints = 0, contextReads = 0
globalThis.requestAnimationFrame = (callback) => { pending.set(++id, callback); return id }
globalThis.cancelAnimationFrame = (key) => pending.delete(key)
globalThis.document = Object.assign(new EventTarget(), { hidden: false })
const motion = Object.assign(new EventTarget(), { matches: false })
globalThis.matchMedia = () => motion
globalThis.addEventListener = () => {}
globalThis.removeEventListener = () => {}
let resize, intersection
globalThis.ResizeObserver = class {
  constructor(callback) { this.callback = callback; resize = this }
  observe() {}
  disconnect() { this.disconnected = true }
}
globalThis.IntersectionObserver = class {
  constructor(callback) { this.callback = callback; intersection = this }
  observe() {}
  disconnect() { this.disconnected = true }
}
const canvas = {
  width: 300, height: 150,
  get clientWidth() { layoutReads++; return 100 },
  get clientHeight() { layoutReads++; return 50 },
  getContext() { contextReads++; return {} },
}
const renderer = createRenderer({
  createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
  insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode() {}, nextSibling() {},
})
const server = await createServer({ server: { middlewareMode: true }, appType: 'custom', logLevel: 'error' })
try {
  const { useCanvasAnimation } = await server.ssrLoadModule('/src/composables/useCanvasAnimation.js')
  const source = ref(0)
  const app = renderer.createApp({
    setup() {
      useCanvasAnimation(shallowRef(canvas), () => { paints++ }, { sources: () => source.value })
      return () => h('div')
    },
  })
  app.mount({})
  resize.callback([{ contentRect: { width: 100, height: 50 } }])
  assert.equal(canvas.width, 200)
  assert.equal(canvas.height, 100)
  for (const time of [0, 16, 34, 50, 68, 84, 102]) {
    const tasks = [...pending.values()]
    pending.clear()
    tasks.forEach((callback) => callback(time))
  }
  assert.equal(layoutReads, 0)
  assert.equal(contextReads, 1)
  assert.ok(paints <= 5, '30fps限帧必须生效')
  console.log('通过：ResizeObserver适配2倍位图，逐帧零布局读取、零重复取上下文')
  document.hidden = true
  document.dispatchEvent(new Event('visibilitychange'))
  assert.equal(pending.size, 0)
  document.hidden = false
  document.dispatchEvent(new Event('visibilitychange'))
  assert.equal(pending.size, 1)
  intersection.callback([{ isIntersecting: false }])
  assert.equal(pending.size, 0)
  intersection.callback([{ isIntersecting: true }])
  assert.equal(pending.size, 1)
  console.log('通过：标签页隐藏及画布离屏暂停，重新可见后恢复')
  motion.matches = true
  const count = paints
  motion.dispatchEvent(new Event('change'))
  assert.equal(pending.size, 0)
  assert.equal(paints, count + 1)
  source.value++
  await nextTick()
  assert.equal(paints, count + 2)
  assert.equal(pending.size, 0)
  resize.callback([{ contentRect: { width: 150, height: 75 } }])
  assert.equal(canvas.width, 300)
  assert.equal(canvas.height, 150)
  assert.equal(pending.size, 0)
  console.log('通过：减少动效时保持静态，数据与尺寸变化仍可更新')
  app.unmount()
  assert.ok(resize.disconnected && intersection.disconnected)
  assert.equal(pending.size, 0)
  const afterUnmount = paints
  const contextsAfterUnmount = contextReads
  motion.dispatchEvent(new Event('change'))
  document.dispatchEvent(new Event('visibilitychange'))
  resize.callback([{ contentRect: { width: 300, height: 150 } }])
  intersection.callback([{ isIntersecting: true }])
  assert.equal(paints, afterUnmount)
  assert.equal(contextReads, contextsAfterUnmount)
  console.log('通过：组件卸载断开观察器和事件，不残留动画')
  const html = await readFile(new URL('../index.html', import.meta.url), 'utf8')
  const links = [...html.matchAll(/href="(https:\/\/fonts\.googleapis\.cn\/css2[^"\s]+)"/g)].map((match) => match[1])
  assert.equal(links.length, 2)
  assert.equal(links[0], links[1])
  assert.ok(links[0].includes('600..900') && links[0].includes('300..700'))
  assert.ok(html.includes('fonts.gstatic.cn'))
  console.log('通过：中文字体用变量字重，普通与noscript地址一致且保留.cn域名')
} finally {
  await server.close()
}
