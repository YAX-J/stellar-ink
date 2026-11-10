import { reactive } from 'vue'

// 路由模块无法载入时仍提供可操作的页面；失败路径供重试与完整刷新使用。
export const navigation = reactive({ error: false, path: '/' })
