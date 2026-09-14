import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

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
      '/auth': 'http://localhost:8080',
      '/user': 'http://localhost:8080',
      '/posts': 'http://localhost:8080',
      '/notes': 'http://localhost:8080',
      '/meteors': 'http://localhost:8080',
      '/echos': 'http://localhost:8080',
      '/links': 'http://localhost:8080',
      '/stats': 'http://localhost:8080',
      '/tags': 'http://localhost:8080',
      '/search': 'http://localhost:8080',
      // 用户头像等上传文件由 user-service 静态映射提供，经网关同源读取
      '/uploads': 'http://localhost:8080',
    },
  },
})
