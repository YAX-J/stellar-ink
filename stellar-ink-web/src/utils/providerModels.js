/* 「从供应商拉取模型列表」的纯逻辑（助手浮层那张「快速添加模型」表单的可选入口）。
 *
 * 为什么单独一个文件而不是塞进组件：下面三件事**必须能被自检直接调用**，
 * 正则扫源码只能证明「代码长得像」，证明不了它做对了 ——
 * ① 响应归一化：供应商可能返回畸形数据（缺 `models`、条目没有 id、id 重复），
 *    这里错一步，界面要么崩、要么列出一堆点不动的空条目；
 * ② `truncated` 的文案：那句「只列出前 N 个」里的 N 必须来自**真实条数**，不能写死；
 * ③ 失败文案：**接口还不存在**（后端这一版还没上，404/405/501）必须说「拉不到」，
 *    绝不能说成「服务坏了」—— 拉模型是辅助能力，说成故障会让人以为整站挂了。
 *
 * ⚠️ 这里**不缓存、不持久化、不碰任何存储**：纯函数进、纯函数出。
 * 供应商那边随时会加/删模型，缓存下来的列表只会让用户对着一个不存在的模型名填半天。
 * ⚠️ 这里也**永远不碰 apiKey**：它只在 `stores/myModels.js` 那一次请求体里出现。
 */

/**
 * 归一化 `POST /ai/me/providers/models` 的响应：
 * `{ models: [{ id, created? }], truncated: boolean, source: string }`。
 *
 * 供应商那一侧的数据进到界面之前只经过这一处，所以容错都收在这里：
 * 没有 `models` 数组、条目不是对象、id 是空的、id 重复 —— 一律当没看见，
 * 剩下的照常列出来（**能列几个就列几个**，比整块失败强）。
 *
 * @param {unknown} data 服务端 `data`（可能整个是 null / 字符串）
 * @returns {{ models: { id: string, created: number|string|null }[], truncated: boolean, source: string }}
 */
export function normalizePullResult(data) {
  const models = []
  const seen = new Set()
  const raw = Array.isArray(data?.models) ? data.models : []
  for (const item of raw) {
    // 兼容两种形态：`{ id }` 与直接给一个字符串（有些供应商的列表接口就是这样）
    const value = (typeof item === 'string' ? item : item?.id) ?? ''
    const id = String(value).trim()
    if (!id || seen.has(id)) continue
    seen.add(id)
    models.push({
      id,
      created: typeof item === 'object' && item ? item.created ?? null : null,
    })
  }
  return {
    models,
    truncated: Boolean(data?.truncated),
    source: typeof data?.source === 'string' ? data.source.trim() : '',
  }
}

/** 后端「这个接口还没有」的几种回法：网关没配路由（404）/ 方法不对（405）/ 未实现（501） */
const MISSING_STATUS = new Set([404, 405, 501])

/**
 * 拉取失败时**给人看**的那句话。
 *
 * 三条口径：
 * ① 后端的 `message` 是给人看的可操作提示（同 Java↔Python 的错误契约），**原样照抄**，
 *    别自己改写 —— 改写会丢掉「到底哪一步错了」；
 * ② **接口还不存在**（后端这一版还没上）只说「拉不到」，绝不写成「服务坏了」：
 *    这是可选的辅助能力，说成故障会让人以为整站挂了；
 * ③ 无论哪种失败，文案只讲**这一件事**，并明确带上「手打模型名照样能保存」——
 *    拉不到列表不该让人以为模型配不了了。
 *
 * @param {unknown} error `api/client.js` 抛出的 ApiError（带 status / message）
 * @returns {string}
 */
export function pullFailureMessage(error) {
  const status = Number(error?.status) || 0
  const backend = typeof error?.message === 'string' ? error.message.trim() : ''
  const tail = '手动填模型名照样能保存（这一步不是必须的）。'

  if (MISSING_STATUS.has(status)) {
    return `拉不到模型列表：后端还没有这个接口（HTTP ${status}）。${tail}`
  }
  if (status === 401 || status === 403) {
    // 这两个状态在这里是**供应商**拒绝（密钥无效 / 无权列模型），不是本站会话失效
    //（store 那条请求带 `ownErrors`，已经挡掉「拉列表把用户踢下线」）。
    // 但它也可能是网关拦下的「本站登录过期」，所以两种情况都点到，不替用户下结论。
    const extra = /登录|会话|token/i.test(backend)
      ? '（本站登录可能已过期：其它操作也会要求重新登录）'
      : '（多半是 API Key 或权限不对，检查上面填的那两项）'
    return `拉不到模型列表：${backend || `对方拒绝了这次请求（HTTP ${status}）`}${extra}${tail}`
  }
  if (backend) return `拉不到模型列表：${backend}${tail}`
  return `拉不到模型列表：请求失败（HTTP ${status || '未知'}）。${tail}`
}

/** `truncated` 时那句说明：N 必须来自**真实条数**（不能写死，供应商那边随时会变） */
export function truncatedNote(count) {
  return `供应商只列出前 ${Number(count) || 0} 个（可能还有更多）`
}
