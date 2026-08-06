import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      resolvers: [ElementPlusResolver()],
    }),
    Components({
      resolvers: [ElementPlusResolver()],
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api/lint': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        timeout: 300000,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq, req) => {
            if (req.url?.includes('/stream')) {
              proxyReq.setHeader('Connection', 'keep-alive')
              proxyReq.setHeader('Cache-Control', 'no-cache')
              proxyReq.setHeader('X-Accel-Buffering', 'no')
            }
          })
          proxy.on('proxyRes', (proxyRes, req) => {
            if (req.url?.includes('/stream')) {
              proxyRes.headers['X-Accel-Buffering'] = 'no'
              proxyRes.headers['Cache-Control'] = 'no-cache'
              proxyRes.headers['Connection'] = 'keep-alive'
            }
          })
        },
      },
      '/api/ingest': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        timeout: 300000,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq, req) => {
            if (req.url?.includes('/stream')) {
              proxyReq.setHeader('Connection', 'keep-alive')
              proxyReq.setHeader('Cache-Control', 'no-cache')
              proxyReq.setHeader('X-Accel-Buffering', 'no')
            }
          })
          proxy.on('proxyRes', (proxyRes, req) => {
            if (req.url?.includes('/stream')) {
              proxyRes.headers['X-Accel-Buffering'] = 'no'
              proxyRes.headers['Cache-Control'] = 'no-cache'
              proxyRes.headers['Connection'] = 'keep-alive'
            }
          })
        },
      },
      '/api/query': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        timeout: 600000,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq, req) => {
            if (req.url?.includes('/stream')) {
              proxyReq.setHeader('Connection', 'keep-alive')
              proxyReq.setHeader('Cache-Control', 'no-cache')
              proxyReq.setHeader('X-Accel-Buffering', 'no')
            }
          })
          proxy.on('proxyRes', (proxyRes, req) => {
            if (req.url?.includes('/stream')) {
              proxyRes.headers['X-Accel-Buffering'] = 'no'
              proxyRes.headers['Cache-Control'] = 'no-cache'
              proxyRes.headers['Connection'] = 'keep-alive'
            }
          })
        },
      },
      '/api/wiki/ai/edit': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        timeout: 300000,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.setHeader('Connection', 'keep-alive')
            proxyReq.setHeader('Cache-Control', 'no-cache')
            proxyReq.setHeader('X-Accel-Buffering', 'no')
          })
          proxy.on('proxyRes', (proxyRes) => {
            proxyRes.headers['X-Accel-Buffering'] = 'no'
            proxyRes.headers['Cache-Control'] = 'no-cache'
            proxyRes.headers['Connection'] = 'keep-alive'
          })
        },
      },
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq, req) => {
            if (req.url?.includes('/bootstrap/')) {
              proxyReq.setHeader('Connection', 'keep-alive')
            }
          })
        },
        timeout: 120000,
      },
    },
  },
})