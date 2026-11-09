# DJHX Memo 静态官网

面向访客的产品介绍、桌面界面示意和安装入口。站点使用 HTML、CSS、SVG 和 Vite，详细决策见 [ADR-0014](../docs/adr/0014-static-website.md)。

在本目录使用 Node.js 22.12+ 和 npm：

```sh
npm ci
npm run dev -- --host 127.0.0.1
npm run build
npm run preview -- --host 127.0.0.1
```

将 `dist/` 中的文件交给静态文件服务器即可。静态产物使用相对资源路径，可以在域名根目录或子目录托管。发布到现有服务域名前，需保留同步 API 与健康检查的路由。品牌图标源自 [品牌母版](../assets/brand/master.svg)。

安装包统一放在 [GitHub Releases](https://github.com/Koril33/djhx-memo/releases)。发布新版本时更新页面的版本说明和各平台下载地址。只有附件已上传并验证后，才将该平台的“安装包待补充”换成直链。包名和校验流程见[客户端发布](../docs/client-release.md)。
