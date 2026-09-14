/* ================= 后端 API 客户端（对接网关 :8080） =================
 * - 基础路径：默认相对路径（生产由 nginx 同源反代）；dev 由 Vite 代理到 :8080
 * - token 存 localStorage，请求时带 Authorization 头（无 Bearer 前缀）
 * - 统一响应 { code, msg, data, traceId }：code===0 视为成功，其余抛 ApiError
 * - 错误分两层：HTTP 状态（网关/Nginx 层）+ 业务 code（服务端 ErrorCode 层），
 *   两者都保留在 ApiError 上，视图可按 code/status 做差异化提示
 */

import { emit, TOAST, SESSION_EXPIRED } from '@/utils/bus'

const BASE = import.meta.env.VITE_API_BASE || ''

const TOKEN_KEY = 'stellar-ink-token'

/** 登出等「预期内的失效」场景置位，避免再弹一次会话过期提示 */
let toastSuppressed = false

export function getToken() {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

/**
 * 业务/鉴权错误。
 * - code：服务端 ErrorCode 或 HTTP 状态码（业务错误与网络错误统一在此）
 * - status：HTTP 状态码，网络层失败为 0
 * - traceId：后端响应携带的链路 ID，报错时展示给用户便于排障
 */
export class ApiError extends Error {
  constructor(code, message, status, traceId = '') {
    super(message || '请求失败')
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.traceId = traceId
  }
}

/** 是否属于「会话失效」：网关层 HTTP 401，或服务端 NotLoginException 回包 code=401
 * （网关放行所有 GET，读接口的未登录由服务端兜底，返回的是 HTTP 200 + code 401，
 *  因此只看 status 会漏判，必须 code/status 联合判断） */
export function isAuthError(error) {
  return error instanceof ApiError && (error.code === 401 || error.status === 401)
}

/** 被限流（Nginx 边缘限流 429 或服务端限流）：提示文案要区别于普通失败 */
export function isRateLimited(error) {
  return error instanceof ApiError && (error.status === 429 || error.code === 429)
}

/** 把 HTTP 状态映射成对用户有意义的中文文案，后端 msg 为空或泛化时才使用 */
function httpMessage(status, fallback) {
  if (status === 0) return fallback || '网络连接失败，请检查网络后重试'
  if (status === 401) return '登录状态已失效，请重新登录'
  if (status === 403) return '权限不足：该操作需要更高的角色'
  if (status === 404) return fallback || '要找的内容不存在，可能已被删除'
  if (status === 408) return '请求超时，请稍后重试'
  if (status === 429) return '操作太频繁了，请稍候片刻再试'
  if (status >= 500) return '星笺暂时无法响应，请稍后重试'
  return fallback || `请求失败（${status}）`
}

/**
 * 发送请求并解包统一响应。
 * @param {string} path 以 / 开头的接口路径，如 /auth/login
 * @param {{ method?: string, body?: object, form?: FormData, query?: object, timeout?: number, silent?: boolean }} [opts]
 *   body 走 JSON；form 走 multipart（上传文件用，两者互斥，form 优先）。
 *   silent=true 时不弹全局 toast（由调用方自行展示局部错误）
 */
export async function request(path, { method = 'GET', body, form, query, timeout = 15000, silent = false } = {}) {
  /* multipart 的 Content-Type 必须由浏览器自己生成（带 boundary），手写会导致后端解析失败 */
  const headers = form ? {} : { 'Content-Type': 'application/json' }
  const token = getToken()
  if (token) headers.Authorization = token

  const url = new URL(BASE + path, window.location.origin)
  if (query) {
    Object.entries(query).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        url.searchParams.set(key, value)
      }
    })
  }

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeout)
  let res
  try {
    res = await fetch(url, {
      method,
      headers,
      body: form || (body === undefined ? undefined : JSON.stringify(body)),
      signal: controller.signal,
    })
  } catch (error) {
    clearTimeout(timer)
    const apiError = error.name === 'AbortError'
      ? new ApiError(408, httpMessage(408), 408)
      : new ApiError(0, httpMessage(0), 0)
    notify(apiError, silent)
    throw apiError
  }
  clearTimeout(timer)

  const text = await res.text()
  let json = null
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    /* 非 JSON 响应（如网关异常页 / Nginx 429 页）按 HTTP 状态处理 */
  }

  const code = json && typeof json.code === 'number' ? json.code : undefined
  const traceId = (json && json.traceId) || res.headers.get('X-Trace-Id') || ''
  const ok = res.ok && (code === undefined || code === 0)
  if (!ok) {
    /* 后端 msg 是业务文案（如「原密码不正确」），优先使用；泛化时再退回状态码文案 */
    const backendMsg = json && json.msg
    const status = res.status
    const message = status === 401 || status === 403 || status === 429 || status >= 500
      ? httpMessage(status, backendMsg)
      : backendMsg || httpMessage(status)
    const apiError = new ApiError(code ?? status, message, status, traceId)
    notify(apiError, silent)
    throw apiError
  }
  return json && json.data !== undefined ? json.data : json
}

/** 统一的错误外送：会话失效发总线事件，其余弹全局 toast（silent 时跳过） */
function notify(error, silent) {
  if (isAuthError(error)) {
    if (!toastSuppressed) emit(SESSION_EXPIRED, { error })
    return
  }
  if (silent) return
  emit(TOAST, {
    type: error.status === 403 ? 'warn' : 'error',
    message: error.message,
    traceId: error.traceId,
  })
}

/** 登出前调用：抑制随后的 401 提示（登出本来就是主动行为） */
export function suppressErrorToast() {
  toastSuppressed = true
  setTimeout(() => { toastSuppressed = false }, 0)
}
