# 公开页面 HTML 与 SEO

首页、星图／笔记列表、公开文章和公开笔记详情，首次HTTP响应即包含可阅读正文、真实链接、
title／description／canonical／Open Graph和详情BlogPosting。所有访客走同一逻辑，不按User-Agent分流。
不执行应用JavaScript时仍可阅读并沿普通链接浏览；执行时由Vue接管，初始公开数据进入原有store，随后正常刷新。
模块加载失败时保留静态正文并显示刷新入口。此实现是按请求生成的公开阅读层，不是整个Vue应用的SSR水合。

## 运行与更新

- 本机：正常启动网关后，`cd stellar-ink-web`，`npm run dev`；生产构建预览使用 `npm run build`、`npm run preview`。
  新插件需要重启已有preview一次；后续只重建dist仍可继续使用preview。
- 本地可选环境变量：`PUBLIC_GATEWAY_URL=http://127.0.0.1:8080`；`SITE_BASE_URL`留空时使用Vite实际监听端口的回环地址。
- 生产：Compose的`public-pages`使用前端Dockerfile的`renderer`阶段，仅Node标准库和共用Markdown／SEO纯函数；
  Nginx提供静态资源和唯一公网入口，页面经内网4180到渲染器，再经8080匿名访问网关。
  不新增npm依赖，不为预渲染访问数据库或AI服务。
- `.env`设置`SITE_BASE_URL`，同步决定公开HTML与content-service sitemap/RSS的绝对地址。
  浏览器接管后保持该配置源地址；不以Host或转发头生成分享地址。
- 发布、编辑、删除和转私有无需重新构建，HTML每次读取网关且使用`Cache-Control: no-store`与`CDN-Cache-Control: no-store`。
  不使用静态文件快照、ETag或旧正文回退。Cloudflare的自定义规则必须绕过HTML缓存，不能覆盖这些响应头。

## 隐私与失败语义

渲染器只构造固定匿名GET，既不转发Authorization/Cookie，也不接受请求指定API目标、状态筛选或内部路径。
文章要求`status=1`，笔记同时要求`visibility=PUBLIC`；初始JSON只输出展示字段，详情正文仅在校验通过后加入。
Markdown复用原有转义及白名单解析，JSON-LD与初始JSON转义`<`，标题、摘要和属性均HTML转义。

| 场景 | HTTP与输出 |
|---|---|
| 公开详情／列表 | 200，正文和元信息；详情包含JSON-LD |
| 内容不存在、草稿、私有、匿名401／403 | 404、noindex，无正文、初始数据或JSON-LD |
| 上游失败、畸形响应、5秒总超时 | 503、noindex、Retry-After:30，不显示为“没有文章” |
| 登录、账号、管理、编辑、搜索 | SPA入口200、noindex，无服务端业务取数 |
| 旧光谱／星籍地址 | 308到归并后的路径 |
| HEAD | 与GET相同状态及元信息，无正文 |

作者登录后仍由浏览器携带自己的令牌读取草稿或私有笔记；首次匿名HTML为404不影响现有页面授权读取。
`/notes`同时是API和页面，Nginx与Vite以Accept区分，GET／HEAD的HTML请求进渲染器，JSON请求仍进网关；
响应带Vary:Accept，sitemap/feed保持原来的XML出口。

## 验证

`npm run check`包含`node --test scripts/public-page.test.mjs`，12组真实HTTP回归覆盖：
正文与JSON-LD、转私有与撤回、列表混入私有记录、匿名请求、不可信Host、XSS及字面量替换字符、
无缓存、错误契约、真实上游超时、HEAD和浏览器初始数据清理。原有前端自检、构建和入口预算同时执行。

浏览器验证使用现有本机公开内容：文章29与笔记20；文章阅读设置可展开，390px笔记无横向溢出，
Vue接管后正文与schema各一份。阻断应用脚本后笔记仍有标题、正文和导航；首页模块故障时正文及刷新提示保留。
不修改现有内容、角色或私有状态；状态切换使用隔离HTTP测试数据。

本机使用官方Nginx1.27.5验证实际配置语法及`Nginx → Node → 网关`链路；
仅替换证书／文件路径、绑定端口和测试上游地址。首页、文章、笔记、HTML／JSON分流、HEAD、404、308与sitemap均通过。
本机无Docker，尚未执行Linux整栈镜像启动；用户当前无生产环境，未部署或执行线上收录验收。

参考：[Google JavaScript SEO](https://developers.google.com/search/docs/crawling-indexing/javascript/javascript-seo-basics)、
[Vite插件中间件](https://vite.dev/guide/api-plugin.html#configureserver)、
[Nginx proxy_pass](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass)。
