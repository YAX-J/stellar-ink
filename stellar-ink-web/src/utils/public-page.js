/** 首次 HTML 的公开数据只使用一次，路由不匹配时丢弃，后续读取仍由 store 请求网关。 */
export function takePublicPage(path) {
  const script = document.getElementById('stellar-public-state')
  if (!script) return null
  script.remove()
  try {
    const state = JSON.parse(script.textContent)
    return state?.path === path ? state : null
  } catch { return null }
}

/** 页面组件挂载成功后移除静态阅读层；模块加载失败时保留阅读内容。 */
export function finishPublicPage() {
  document.getElementById('stellar-public-page')?.remove()
  document.documentElement.removeAttribute('data-public-pending')
}

export function showPublicPageError() {
  const hint = document.getElementById('stellar-public-error')
  if (hint) hint.hidden = false
}
