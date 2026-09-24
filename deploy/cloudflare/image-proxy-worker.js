/* =============================================================================
 * 星笺 STELLAR INK · 图片代理 Worker（Cloudflare Workers，免费套餐可用）
 *
 * 用途：把 img.geminix.work/* 代理到腾讯云 COS 香港桶，让头像走自己的域名 + CF 边缘缓存。
 *
 * 为什么需要 Worker：CF 会把请求的 Host 原样发给源站，而 COS 只认自己的端点域名
 *   （bucket.cos.<region>.myqcloud.com）或你在 COS 里另绑的自定义域名 —— 直接 CNAME 到 COS
 *   会拿到 403/404。标准解法是 Origin Rules 的「Host 重写」，但它在部分套餐（含免费）不可用，
 *   而 Transform Rules 根本不允许改 Host 头。Worker 自己发起 fetch、天然带正确的 Host，
 *   同时还能吃到边缘缓存；免费套餐每天 10 万请求，博客图片远远用不到。
 *
 * 部署步骤见同目录 README.md；改桶只改下面 ORIGIN 一行。
 * ============================================================================= */

const ORIGIN = 'https://REPLACE_ME.cos.ap-hongkong.myqcloud.com' // ← 换成你的桶端点
const CACHE_SECONDS = 31536000 // 1 年
const ALLOWED_PREFIX = '/avatars/' // 只代理头像目录，不把整个桶暴露成任意代理

export default {
  async fetch(request) {
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return new Response('Method Not Allowed', {
        status: 405,
        headers: { allow: 'GET, HEAD' },
      })
    }

    const { pathname, search } = new URL(request.url)
    // 只放行 /avatars/ 前缀（pathname 由 URL 解析器规范化，天然挡掉 ../ 穿越）
    if (!pathname.startsWith(ALLOWED_PREFIX)) {
      return new Response('Not Found', { status: 404 })
    }

    const res = await fetch(ORIGIN + pathname + search, {
      method: request.method,
      // 让 Worker 自己走边缘缓存；若已在 Cache Rules 里配了 TTL，这两行可以删掉
      cf: { cacheEverything: true, cacheTtl: CACHE_SECONDS },
    })

    const out = new Response(res.body, res)
    // 对象名带 8 位随机串、换头像即换 URL，可以放心长缓存
    out.headers.set('cache-control', `public, max-age=${CACHE_SECONDS}, immutable`)
    return out
  },
}
