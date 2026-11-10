/* 对实际构建产物设入口预算，避免助手或编辑器重新进入首屏入口。单位为 KiB。 */
import { readFile } from 'node:fs/promises'
import { gzipSync } from 'node:zlib'

const html = await readFile(new URL('../dist/index.html', import.meta.url), 'utf8')
const source = html.match(/<script\b[^>]*type="module"[^>]*src="([^"]+)"/)?.[1]
if (!source?.startsWith('/assets/') || source.includes('..')) throw new Error('找不到生产构建入口，无法检查体积预算')
const code = await readFile(new URL(`../dist${source}`, import.meta.url))
const gzip = gzipSync(code)
const limits = { raw: 150 * 1024, gzip: 60 * 1024 }
console.log(`入口 JS：${(code.length / 1024).toFixed(2)} KiB，gzip ${(gzip.length / 1024).toFixed(2)} KiB`)
if (code.length > limits.raw || gzip.length > limits.gzip) {
  throw new Error('入口超过 150 KiB / gzip 60 KiB 预算，请检查是否引入了应按需加载的功能')
}
console.log('入口体积预算通过；该指标不等同于全部首屏请求或真实用户加载时间')
