import { onMounted, onUnmounted, watch } from 'vue'
import { fitCanvas } from '@/utils/canvas'

/** 尺寸变化时适配位图，动画帧不读布局；离屏／隐藏暂停，减少动效时只画静态帧。 */
export function useCanvasAnimation(canvas, draw, { sources, fps = 30 } = {}) {
  let context, resizeObserver, visibilityObserver, motion
  let frame = 0
  let mounted = false
  let visible = true
  let previous = -Infinity
  const paint = (time = 0) => {
    if (!mounted || !context || !canvas.value || document.hidden || !visible) return
    draw(context, canvas.value.width, canvas.value.height, time)
  }
  const stop = () => { cancelAnimationFrame(frame); frame = 0 }
  const tick = (time) => {
    frame = 0
    if (!mounted || document.hidden || !visible || motion.matches) return
    if (time - previous >= 1000 / fps) { previous = time; paint(time) }
    frame = requestAnimationFrame(tick)
  }
  const resume = () => {
    stop()
    if (!mounted || !context || document.hidden || !visible) return
    previous = -Infinity
    paint(motion.matches ? 0 : performance.now())
    if (!motion.matches) frame = requestAnimationFrame(tick)
  }
  const resize = (size) => {
    if (!mounted || !canvas.value) return
    context = fitCanvas(canvas.value, 2, size)
    resume()
  }
  const fallbackResize = () => resize()
  if (sources) watch(sources, resume, { deep: true, flush: 'post' })
  onMounted(() => {
    mounted = true
    motion = matchMedia('(prefers-reduced-motion: reduce)')
    motion.addEventListener('change', resume)
    document.addEventListener('visibilitychange', resume)
    if (typeof ResizeObserver !== 'undefined') {
      resizeObserver = new ResizeObserver(([entry]) => {
        if (entry && entry.contentRect.width && entry.contentRect.height) resize(entry.contentRect)
      })
      resizeObserver.observe(canvas.value)
    } else {
      fallbackResize()
      addEventListener('resize', fallbackResize)
    }
    if (typeof IntersectionObserver !== 'undefined') {
      visibilityObserver = new IntersectionObserver(([entry]) => { visible = entry.isIntersecting; resume() })
      visibilityObserver.observe(canvas.value)
    }
  })
  onUnmounted(() => {
    mounted = false
    stop()
    resizeObserver?.disconnect()
    visibilityObserver?.disconnect()
    motion?.removeEventListener('change', resume)
    document.removeEventListener('visibilitychange', resume)
    removeEventListener('resize', fallbackResize)
  })
}
