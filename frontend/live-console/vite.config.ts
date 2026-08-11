import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'node:path'

export default defineConfig({
  base: process.env.NODE_ENV === 'production' ? '/platform/live/console/' : '/',
  plugins: [
    vue(),
    {
      name: 'spa-fallback-platform-live',
      configureServer(server) {
        server.middlewares.use((req, res, next) => {
          if (!req.url) {
            next()
            return
          }
          const pathname = req.url.split('?')[0]
          if (pathname !== '/platform/live' && !pathname.startsWith('/platform/live/')) {
            next()
            return
          }
          const accept = String(req.headers.accept || '')
          if (!accept.includes('text/html')) {
            next()
            return
          }
          if (
            req.url.startsWith('/platform/live/frontend') ||
            req.url.startsWith('/platform/live/session') ||
            req.url.startsWith('/platform/live/media') ||
            req.url.startsWith('/platform/live/chat') ||
            req.url.startsWith('/platform/live/agent-runs') ||
            req.url.startsWith('/platform/live/api')
          ) {
            next()
            return
          }
          const index = req.url.indexOf('?')
          const suffix = index >= 0 ? req.url.substring(index) : ''
          req.url = `/${suffix}`
          next()
        })
      },
    },
  ],
  build: {
    outDir: resolve(__dirname, '../../dist/platform_live_app'),
    emptyOutDir: true,
    sourcemap: false,
  },
  server: {
    proxy: {
      '/platform/frontend': 'http://127.0.0.1:8080',
      '/platform/session': 'http://127.0.0.1:8080',
      '/platform/media': 'http://127.0.0.1:8080',
      '/platform/chat': 'http://127.0.0.1:8080',
      '/platform/memory': 'http://127.0.0.1:8080',
      '/platform/live': 'http://127.0.0.1:8080',
      '/agent-runs': 'http://127.0.0.1:8080',
    },
  },
})
