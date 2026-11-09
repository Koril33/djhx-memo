# ADR-0014 静态官网构建

官网位于 [website/](../../website/README.md)，使用原生 HTML、CSS 和 SVG，另附一份极小的原生 ES 模块（[app.js](../../website/app.js)，无第三方运行时依赖）负责界面交互：四语切换、深浅模式与下载平台下拉跳转。构建复用桌面前端已采用的 Vite 8.3.5，将资源打包为带内容哈希的静态文件。Vite 由 [Vite 团队](https://github.com/vitejs/vite)维护，采用 MIT 许可，确切传递版本由 npm 锁文件固定。

页面使用系统字体和本地品牌图标，不引入外部字体、UI 框架或图标库。示意界面直接由 HTML 和 CSS 绘制；交互脚本仅操作 DOM 与 `localStorage`，不发网络请求。语言（简体中文 / English / 日本語 / 한국어）与主题（浅色 / 深色）选择持久保存在本机，主题默认跟随系统 `prefers-color-scheme`，并通过 `<head>` 内联脚本避免首屏闪色。构建产物可由任意静态文件服务器提供，面向访客的下载入口为平台下拉，选择后跳转 GitHub Releases。
