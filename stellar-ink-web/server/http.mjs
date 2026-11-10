import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { pathToFileURL } from 'node:url'
import { resolve } from 'node:path'
import { createPublicPage } from './public-page.mjs'

/** 可复用的 HTTP 入口；测试也运行这层，不以字符串检查代替页面响应验证。 */
export function publicPageHandler({ render, loadTemplate }) {
  return async (req, res) => {
    if (req.url === '/healthz') {
      res.writeHead(200, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' })
      res.end(req.method === 'HEAD' ? '' : 'ok')
      return
    }
    if (!['GET', 'HEAD'].includes(req.method)) {
      res.writeHead(405, { Allow: 'GET, HEAD', 'Cache-Control': 'no-store' }); res.end(); return
    }
    const controller = new AbortController()
    const abort = () => { if (!res.writableEnded) controller.abort() }
    res.on('close', abort)
    try {
      const page = await render(req.url, await loadTemplate(req.url), { signal: controller.signal })
      if (res.destroyed) return
      res.writeHead(page.status, page.headers)
      res.end(req.method === 'HEAD' ? '' : page.html)
    } catch {
      // 模板或进程配置异常也不能伪装成可索引的成功页，不回显堆栈与内部地址。
      if (!res.destroyed) {
        res.writeHead(503, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store', 'X-Robots-Tag': 'noindex', 'Retry-After': '30' })
        res.end(req.method === 'HEAD' ? '' : '页面暂时无法读取，请稍后重试。')
      }
    } finally {
      res.off('close', abort)
    }
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  if (!process.env.SITE_BASE_URL) throw new Error('请配置站点对外地址 SITE_BASE_URL')
  const render = createPublicPage({ gatewayUrl: process.env.PUBLIC_GATEWAY_URL || 'http://gateway:8080', siteUrl: process.env.SITE_BASE_URL })
  const template = await readFile(new URL('../dist/index.html', import.meta.url), 'utf8')
  const server = createServer({ requestTimeout: 10000, headersTimeout: 10000 }, publicPageHandler({ render, loadTemplate: async () => template }))
  server.listen(Number(process.env.PORT || 4180), process.env.HOST || '0.0.0.0', () => console.log('公开页面服务已启动'))
  for (const event of ['SIGTERM', 'SIGINT']) process.on(event, () => server.close())
}
