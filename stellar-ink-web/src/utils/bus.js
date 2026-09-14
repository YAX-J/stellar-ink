/* ================= 极简事件总线 =================
 * 用途：让非组件模块（api/client.js、router 守卫）也能把提示交给 UI，
 * 而无需 import 任何 Pinia store —— 避免 store ↔ client 的循环依赖。
 * 约定事件名统一加 `stellar:` 前缀，只传普通对象，不传组件实例。
 */

const target = new EventTarget()

/**
 * 发送一个事件。
 * @param {string} name 事件名（建议 `stellar:` 前缀）
 * @param {object} [detail] 事件负载
 */
export function emit(name, detail = {}) {
  target.dispatchEvent(new CustomEvent(name, { detail }))
}

/**
 * 订阅事件，返回取消订阅函数。
 * @param {string} name 事件名
 * @param {(detail: object) => void} handler 处理函数
 * @returns {() => void} 取消订阅
 */
export function on(name, handler) {
  const listener = (event) => handler(event.detail)
  target.addEventListener(name, listener)
  return () => target.removeEventListener(name, listener)
}

/* 事件名常量：只在这里定义，避免各处手写字符串拼错 */
export const TOAST = 'stellar:toast'
export const SESSION_EXPIRED = 'stellar:session-expired'
