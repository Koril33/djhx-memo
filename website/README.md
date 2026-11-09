# DJHX Memo 静态官网

面向访客的单屏产品介绍、桌面/移动端界面示意和安装入口。站点使用 HTML、CSS、SVG 与一份原生 ES 模块（`app.js`），详细决策见 [ADR-0014](../docs/adr/0014-static-website.md)。

## 页面与交互

- 桌面端为单屏无滚动布局（`100dvh`），左栏为简介与下载，右栏为桌面 + 移动端界面示意；窄屏下自然堆叠并允许滚动。
- 顶部导航提供语言切换（简体中文 / English / 日本語 / 한국어）、深浅模式切换与 GitHub 链接。语言与主题选择保存在本机 `localStorage`，主题默认跟随系统 `prefers-color-scheme`。
- 下载入口为平台下拉（Windows / Linux / Android），选择后跳转 GitHub Releases 的版本页面。各平台跳转地址集中在 `app.js` 的 `DOWNLOAD_URLS`。

在本目录使用 Node.js 22.12+ 和 npm：

```sh
npm ci
npm run dev -- --host 127.0.0.1
npm run build
npm run preview -- --host 127.0.0.1
```

将 `dist/` 中的文件交给静态文件服务器即可。静态产物使用相对资源路径，可以在域名根目录或子目录托管。发布到现有服务域名前，需保留同步 API 与健康检查的路由。品牌图标源自 [品牌母版](../assets/brand/master.svg)。

安装包统一放在 [GitHub Releases](https://github.com/Koril33/djhx-memo/releases)。发布新版本时更新页面的版本说明和各平台下载地址。Linux / Android 安装包上传并验证后，再把 `app.js` 中对应平台的 `DOWNLOAD_URLS` 指向直链。包名和校验流程见[客户端发布](../docs/client-release.md)。
