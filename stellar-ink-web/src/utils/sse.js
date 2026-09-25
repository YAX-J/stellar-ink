/* ================= SSE 读帧（流式问答用） =================
 * 为什么手写而不用 `EventSource`：**它只支持 GET**，而问答必须 POST（问题有 500 字上限，
 * 塞进 query string 既难看又会进日志）。`fetch` + `ReadableStream` 是唯一能 POST 又流式读的方式。
 *
 * 线格式见 `docs/api/README.md`：一帧是若干 `data:` 行、以**空行**结束。
 * 两个必须做对的地方：
 * - **帧可能被 TCP 分片劈开**：解析必须保留不完整的尾巴，不能假设「一次 read 就是一帧」；
 * - **心跳是注释行**（`: ping`），不是事件，要丢掉 —— 否则界面上会冒出幽灵事件。
 */

/** 一帧解析结果：`type` 来自 JSON 里的 `type` 字段（协议刻意不用 `event:` 名） */
export function parseFrame(raw) {
  const lines = String(raw ?? '').split('\n')
  const dataLines = []
  for (const line of lines) {
    if (!line || line.startsWith(':')) continue
    if (line.startsWith('data:')) dataLines.push(line.slice('data:'.length).trimStart())
  }
  if (!dataLines.length) return null
  let payload
  try {
    payload = JSON.parse(dataLines.join('\n'))
  } catch {
    // 半截 JSON 说明上游或切帧出了问题：如实暴露，别静默丢掉（丢了会表现为「答到一半没了」）
    return { type: 'unknown', payload: null, raw }
  }
  if (!payload || typeof payload !== 'object') return { type: 'unknown', payload: null, raw }
  return { type: String(payload.type || 'unknown'), payload, raw }
}

/**
 * 增量切帧器：喂进任意大小的文本块，吐出完整的帧。
 *
 * 从异步生成器里抽出来是为了**能被直接断言**（前端没有测试运行器，纯逻辑才测得了）：
 * 「一帧被 TCP 分片劈成两半」这种最容易出错的情况，用同步调用就能覆盖，
 * 不必去构造一个假的 ReadableStream。
 */
export class FrameSplitter {
  constructor() {
    this.buffer = ''
  }

  /**
   * 追加一块文本。
   * @param {string} text
   * @returns {Array<object>} 这次能切出来的完整帧（可能为空）
   */
  push(text) {
    this.buffer += String(text ?? '')
    const frames = []
    let index = this.buffer.indexOf('\n\n')
    while (index >= 0) {
      const frame = parseFrame(this.buffer.slice(0, index))
      this.buffer = this.buffer.slice(index + 2)
      if (frame) frames.push(frame)
      index = this.buffer.indexOf('\n\n')
    }
    return frames
  }

  /** 流结束时调用：最后一段没有空行结尾也要交出去，否则「答案的最后一句」会消失。 */
  flush() {
    const frame = parseFrame(this.buffer)
    this.buffer = ''
    return frame
  }
}

/**
 * 把一个 `ReadableStream<Uint8Array>` 变成逐帧的异步迭代器。
 * @param {ReadableStream<Uint8Array>} body
 */
export async function* readFrames(body) {
  const reader = body.getReader()
  const decoder = new TextDecoder('utf-8')
  const splitter = new FrameSplitter()
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      // `stream: true` 让多字节字符被劈开时也能正确解码（中文 3 字节，极容易撞上）
      for (const frame of splitter.push(decoder.decode(value, { stream: true }))) {
        yield frame
      }
    }
    for (const frame of splitter.push(decoder.decode())) {
      yield frame
    }
    const tail = splitter.flush()
    if (tail) yield tail
  } finally {
    // **取消传播的上游一半**：无论正常结束、报错还是调用方提前 break，
    // 都要释放读取锁并让请求结束；配合 Java 侧的 IOException 分支，
    // 就构成「用户一放手，模型就停」。
    try {
      await reader.cancel()
    } catch {
      /* 已经关掉的流再 cancel 会抛：无害 */
    }
  }
}
