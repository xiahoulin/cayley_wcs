import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发服务器端口 5184，避开 WMS 的 5173/5174；/api 代理到后端 20021。
export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5184,
    proxy: {
      '/api': {
        target: process.env.CAYLEYWCS_BACKEND || 'http://127.0.0.1:20021',
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/api/, ''),
      },
      '/ws': {
        target: process.env.CAYLEYWCS_BACKEND || 'http://127.0.0.1:20021',
        changeOrigin: true,
        ws: true,
      },
    },
  },
})
