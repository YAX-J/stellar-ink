/* 把 canvas 位图尺寸设为 CSS 尺寸的 2 倍（HiDPI 细腻 rendering），返回 2d 上下文 */
export function fitCanvas(canvas, scale = 2) {
  const w = Math.max(1, Math.round(canvas.clientWidth * scale))
  const h = Math.max(1, Math.round(canvas.clientHeight * scale))
  if (canvas.width !== w || canvas.height !== h) {
    canvas.width = w
    canvas.height = h
  }
  return canvas.getContext('2d')
}
