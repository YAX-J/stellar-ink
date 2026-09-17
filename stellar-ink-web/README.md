# 星笺前端

本目录是星笺的 Vue 3 单页应用，负责文章浏览与写作、流星、回声、星链、账号和角色管理等用户界面。

## 技术栈

- Vue 3 + Vite
- Pinia
- Vue Router
- 原生 CSS 与 Canvas

项目不使用第三方 UI 组件库。视觉基准是 [`src/styles/tokens/variables.css`](src/styles/tokens/variables.css) 里的语义变量（三主题 night / dusk / dawn）。

## 运行

```bash
npm install
npm run dev
```

开发地址为 `http://localhost:5173`。Vite 将 `/auth`、`/user`、`/posts`、`/meteors`、`/echos`、`/links`、`/stats`、`/tags` 和 `/search` 代理到网关 `http://localhost:8080`。

生产构建：

```bash
npm run build
npm run preview
```

构建结果写入 `dist/`，该目录不入库。

## 源码结构

| 目录 | 职责 |
|---|---|
| `src/views/` | 路由页面，每个页面独立子目录 |
| `src/components/` | Canvas、通用组件（含顶部导航 TopNav）和文章组件 |
| `src/stores/` | Pinia 业务状态与后端数据访问 |
| `src/api/client.js` | Fetch 封装、token、超时和统一错误处理 |
| `src/api/mock.js` | 仅保留视觉常量和写作提示 |
| `src/router/` | 路由注册、懒加载和角色守卫 |
| `src/styles/` | 基础样式、公共元件和主题 Token |
| `src/utils/` | Canvas、格式化和角色工具 |

## 数据流

页面组件通过 Pinia store 读取和修改业务数据，组件不得直接调用后端。API 默认使用相对路径：开发环境由 Vite 代理，生产环境由 Nginx 同源反代。token 保存在 `localStorage`，请求头格式为 `Authorization: <token>`，不带 `Bearer` 前缀。

## 样式约定

- 颜色、字体、圆角和缓动只使用 `src/styles/tokens/variables.css` 的变量。
- 所有组件必须在 night、dusk、dawn 三个主题下可读。
- 共用样式放 `src/styles/components.css`，页面私有样式写在对应 View 的 scoped style 中。
- Canvas 使用 `src/utils/canvas.js` 的 `fitCanvas`，并在组件卸载时取消动画帧。

更完整的编码规则见 [`../AGENTS.md`](../AGENTS.md)，接口见 [`../docs/api/README.md`](../docs/api/README.md)。
