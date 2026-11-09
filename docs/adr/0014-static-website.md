# ADR-0014 静态官网构建

官网位于 [website/](../../website/README.md)，使用原生 HTML、CSS 和 SVG。构建复用桌面前端已采用的 Vite 8.3.5，将资源打包为带内容哈希的静态文件。Vite 由 [Vite 团队](https://github.com/vitejs/vite)维护，采用 MIT 许可，确切传递版本由 npm 锁文件固定。

页面使用系统字体和本地品牌图标。示意界面直接由 HTML 和 CSS 绘制，浏览器无需执行 JavaScript。构建产物可由任意静态文件服务器提供，面向访客的下载入口指向 GitHub Releases。
