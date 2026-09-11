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
    // dev 把后端 API 前缀反代到网关 :8080，前端用相对路径即可（生产由 nginx 同源反代）
    proxy: {
      '/auth': 'http://localhost:8080',
      '/user': 'http://localhost:8080',
      '/posts': 'http://localhost:8080',
      '/meteors': 'http://localhost:8080',
      '/echos': 'http://localhost:8080',
      '/links': 'http://localhost:8080',
      '/stats': 'http://localhost:8080',
      '/tags': 'http://localhost:8080',
      '/search': 'http://localhost:8080',
    },
  },
})
