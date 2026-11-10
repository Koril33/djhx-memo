# ADR-0014 静态官网构建

官网位于 [website/](../../website/README.md)，使用原生 HTML、CSS 和 SVG，另附一份极小的原生 ES 模块（[app.js](../../website/app.js)，无第三方运行时依赖）负责界面交互：四语切换、深浅模式、下载平台链接与指针视差。构建复用桌面前端已采用的 Vite 8.3.5，将资源打包为带内容哈希的静态文件。Vite 由 [Vite 团队](https://github.com/vitejs/vite)维护，采用 MIT 许可，确切传递版本由 npm 锁文件固定。

页面使用系统字体和本地品牌图标。示意界面、和纸纹理、圆相和平台图标由 HTML、CSS 和内联 SVG 绘制。视差和主题过渡使用浏览器原生能力，遵循 `prefers-reduced-motion`。语言和主题偏好保存在本机 `localStorage`，主题默认跟随系统 `prefers-color-scheme`，并在 `<head>` 内初始化以减少首屏闪色。各平台磁贴的 `href` 直接指向 GitHub Releases 的安装包，下载独立于交互脚本。

构建后从根 LICENSE、统一图标许可和锁定 Vite 包复制随站通知；Vite 注入的 modulepreload 支持代码同样按其 MIT 通知分发。官网与同步服务共享域名，由 Nginx 将 `/v1` 及 `/healthz` 保持原样代理，其余提供静态文件；缺失静态文件不回退首页。官网不引入浏览器笔记或账号功能。

仓库入口旁的 GitHub 标记属于 GitHub 品牌，来源及使用要求见[官方 Logos](https://github.com/logos)；项目 MIT 与 Lucide 许可不重新授予该商标权，也不表示 GitHub 背书。
