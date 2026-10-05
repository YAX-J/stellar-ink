import { defineStore } from 'pinia'
import { useQaStream } from '@/composables/useQaStream'

/**
 * 星海问答（深读页的「问星笺」）：就全站已发布内容（文章 + 公开技术笔记）提问，
 * 答案必须带引用（或明确拒答）。
 *
 * **本 store 只是 `useQaStream()` 的一层薄封装**：状态机搬进 composable 是为了让
 * 助手浮层（S3）能用**独立实例**——浮层与深读页面板会同时存在，而 store 是单一全局实例，
 * 两边共用一个 `answer` 时，一边的 `delta` 会写进另一边的界面。
 * 保留这个 store 是为了不动深读页与既有自检脚本的调用方式。
 *
 * 与 `stores/ai.js` 仍然分开：那是**站长的调试台**（模型配置、评测台，全 ADMIN），
 * 这是**读者功能**。混在一起会让权限判断散落在两处，也容易让读者页面 import 到管理端的东西。
 *
 * 默认走**流式**（`POST /ai/qa/stream`）：引用由检索决定，比正文先到，所以能先渲染引用再等答案。
 * 一次性回答（`POST /ai/qa`）保留为降级路径，两条路复用同一套 Python 编排，不会给出不同结论。
 */
export const useQaStore = defineStore('qa', () => {
  const qa = useQaStream()
  return { ...qa }
})
