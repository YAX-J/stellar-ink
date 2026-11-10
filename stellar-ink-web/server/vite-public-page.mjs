import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { createPublicPage } from './public-page.mjs'
import { publicPageHandler } from './http.mjs'

/** dev 与 preview 都走同一公开渲染器；非 HTML 请求继续交给现有 API 代理。 */
export function publicPagePlugin() {
  function install(server, preview = false) {
    let render
    const handler = publicPageHandler({
      render: (...args) => render(...args),
      loadTemplate: async (path) => {
        const file = resolve(server.config.root, preview ? `${server.config.build.outDir}/index.html` : 'index.html')
        const template = await readFile(file, 'utf8')
        return preview ? template : server.transformIndexHtml(path, template)
      },
    })
    server.middlewares.use((req, res, next) => {
      const path = req.url?.split('?')[0] || ''
      if (!['GET', 'HEAD'].includes(req.method) || !req.headers.accept?.includes('text/html')
          || /\.[a-z\d]+$/i.test(path) || path.startsWith('/@') || path.startsWith('/src/')) return next()
      // 端口可能由 Vite 自动递增，以实际监听端口为准；不信任请求 Host。
      const port = server.httpServer?.address()?.port || (preview ? server.config.preview.port : server.config.server.port)
      render ||= createPublicPage({ gatewayUrl: process.env.PUBLIC_GATEWAY_URL || 'http://127.0.0.1:8080', siteUrl: process.env.SITE_BASE_URL || `http://127.0.0.1:${port}` })
      handler(req, res)
    })
  }
  return { name: 'stellar-public-pages', configureServer: (server) => install(server), configurePreviewServer: (server) => install(server, true) }
}
