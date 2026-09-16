import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const gatewayProxy = {
  target: 'http://localhost:8080',
  /* /notes、/links、/search 同时也是前端 history 路由。
   * 浏览器直接刷新时 Accept 包含 text/html，应交还 Vite 返回 SPA；
   * fetch 默认接受任意响应类型，仍按 API 请求代理到网关。 */
  bypass(req) {
    if (req.method === 'GET' && req.headers.accept?.includes('text/html')) return '/index.html'
  },
}

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // dev 把后端 API 前缀反代到网关 :8080，前端用相对路径即可（生产由 nginx 同源反代）。
    // ⚠️ 后端新增路由前缀时，这里必须同步添加：漏加不会报错，
    // 而是被 Vite 当作前端路由回退到 index.html，接口静默失败（曾漏过 /notes）。
    proxy: {
      '/auth': gatewayProxy,
      '/user': gatewayProxy,
      '/posts': gatewayProxy,
      '/notes': gatewayProxy,
      '/meteors': gatewayProxy,
      '/echos': gatewayProxy,
      '/links': gatewayProxy,
      '/stats': gatewayProxy,
      '/tags': gatewayProxy,
      '/search': gatewayProxy,
      // 用户头像等上传文件由 user-service 静态映射提供，经网关同源读取
      '/uploads': gatewayProxy,
    },
  },
})
