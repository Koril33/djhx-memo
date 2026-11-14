# DJHX Memo 静态官网

面向访客的单屏产品介绍、桌面/移动端界面示意和安装入口。站点使用 HTML、CSS、SVG 与一份原生 ES 模块（`app.js`），详细决策见 [ADR-0014](../docs/adr/0014-static-website.md)。

## 页面与交互

- 视觉方向为日式极简（和纸纹理、圆相、朱印、竖排索引）与瑞士国际主义（十二栏网格、规则线、无衬线大标题）的结合，文字保持最少。
- 桌面端为单屏布局（`100dvh`），左五栏为标题与下载，第六栏为竖排索引，右六栏展示桌面与移动端界面示意。窄屏下自然堆叠并允许滚动。
- 顶部导航提供语言分段控件（中 / EN / 日 / 한）、日食造型的深浅模式切换与 GitHub 链接。语言与主题选择保存在本机 `localStorage`，主题默认跟随系统 `prefers-color-scheme`。支持 View Transitions 的浏览器以按钮为圆心做圆形揭示。
- Windows、Linux 和 Android 磁贴分别直达 GitHub Releases 的 EXE、DEB、APK；发布说明/历史版本是独立次级入口。版本与文件名只在 `downloads.json` 维护，Vite 构建时生成 HTML 直链及同名静态清单，关闭 JavaScript 时也可以下载。翻译/主题脚本不写下载地址。
- 圆相描绘、入场、指针视差和磁贴墨色晕开使用 CSS 与少量 `requestAnimationFrame`。系统开启“减少动态效果”时使用静态展示，视差仅在精确指针设备上启用。

在本目录使用 Node.js 22.12+ 和 npm：

```sh
npm ci
npm run dev -- --host 127.0.0.1
npm run build
npm run preview -- --host 127.0.0.1
```

将 `dist/` 中的文件交给静态文件服务器即可，`npm run build` 同时从规范来源生成 `dist/licenses/` 的项目、图标和 Vite 许可。静态产物使用相对资源路径，可以在域名根目录或子目录托管。发布到现有服务域名前，需保留同步 API 与健康检查的路由；本机部署路径为 `/var/www/memo.djhx.site/`，步骤与回退见[运维](../docs/operations.md)及[实际部署记录](../docs/website-deployment.md)。品牌图标源自 [品牌母版](../assets/brand/master.svg)。

安装包统一放在 [GitHub Releases](https://github.com/Koril33/djhx-memo/releases)。发布新版本时，先上传并验证本次平台附件与 SHA256SUMS，再更新页面的版本说明和下载地址，避免发布不可用直链。允许各平台版本不同：0.1.9仅更新Android，Windows/Linux保留0.1.8直链。官网 Logo 由统一品牌生成器维护，不能手改。包名和校验流程见[客户端发布](../docs/client-release.md)。

## 下载回归与部署

构建后在仓库根目录执行 `python scripts/check-website-downloads.py --exercise-controls`；脚本使用安装的 Chromium/Edge、新建隔离浏览器配置，实际执行 JS，核对渲染版本、三个附件地址和语言/主题切换。可用 `--browser <浏览器绝对路径>`、`--language zh-CN --dark` 选择主机配置；线上使用 `--url https://memo.djhx.site/`。这不代替附件的匿名下载与哈希核对。

部署必须包含同一次构建的整个 `dist/`，包括 HTML、哈希脚本/样式、下载清单和许可；使用新发行目录并原子切换站点链接，同时保留原 `updates/` 及 API 路由。不能仅替换 HTML 中的版本字符串：旧脚本仍可能重写下载地址。0.1.8 已复现这一历史问题，修正及实际线上检查见[验证记录](../docs/releases/0.1.8-validation.md)。服务器目录和连接参数属于发行主机配置。
