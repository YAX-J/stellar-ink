/* ================= 后端 API 客户端（对接网关 :8080） =================
 * - 基础路径：默认相对路径（生产由 nginx 同源反代）；dev 由 Vite 代理到 :8080
 * - token 存 localStorage，请求时带 Authorization 头（无 Bearer 前缀）
 * - 统一响应 { code, msg, data, traceId }：code===0 视为成功，其余抛 ApiError
 */

const BASE = import.meta.env.VITE_API_BASE || ''

const TOKEN_KEY = 'stellar-ink-token'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

/** 业务/鉴权错误：code 为后端错误码或 HTTP 状态码，status 恒为 HTTP 状态码 */
export class ApiError extends Error {
  constructor(code, message, status) {
    super(message || '请求失败')
    this.code = code
    this.status = status
  }
}

/**
 * 发送请求并解包统一响应。
 * @param {string} path 以 / 开头的接口路径，如 /auth/login
 * @param {{ method?: string, body?: object }} [opts]
 */
export async function request(path, { method = 'GET', body } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  const token = getToken()
  if (token) headers.Authorization = token

  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  const text = await res.text()
  let json = null
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    /* 非 JSON 响应（如网关异常页）按 HTTP 状态处理 */
  }

  const code = json && typeof json.code === 'number' ? json.code : undefined
  const ok = res.ok && (code === undefined || code === 0)
  if (!ok) {
    const msg = (json && json.msg) || (res.ok ? '操作失败' : `请求失败（${res.status}）`)
    throw new ApiError(code ?? res.status, msg, res.status)
  }
  return json && json.data !== undefined ? json.data : json
}
