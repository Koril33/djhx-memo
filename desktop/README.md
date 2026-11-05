# desktop

Windows / Linux Tauri 2 客户端。Rust 后端直接链接 core，本地前端在本目录内采用 Svelte + TypeScript + Vite，编辑器使用 CodeMirror 6；不依赖尚未开发的独立 Web 工程。

当前 0.1.0 自用候选实现包含托盘、未登录笔记、账号、Markdown、搜索、回收站、导出与同步。全局快捷键、开机自启、自动升级暂缓。Windows 首先验收负责人当前的 Windows 10 22H2 x86_64，Windows 11 兼容性另行记录；Linux 默认 Ubuntu 24.04 x86_64，验证 Wayland/X11、输入法及 AppIndicator 环境。

## 开发与构建

要求 Node 22.12+、Rust 1.99+；Linux 需要 GTK3、WebKitGTK 4.1、AppIndicator 开发包，Windows 需要 MSVC C++ 工具链与 WebView2。依赖版本及维护来源见 [ADR-0007](../docs/adr/0007-desktop-dependencies.md)。

```sh
cd desktop
npm ci
npm run format:check
npm run lint
npm run check
npm test
npm run build
npm run tauri dev
```

原生检查在 `desktop/src-tauri/` 执行 `cargo fmt --check`、`cargo check --locked`、`cargo clippy --locked -- -D warnings` 和 `cargo test --locked --release --bin djhx-memo`。Linux 打包：`npm run tauri build -- --bundles deb`；Windows 原生环境打包：`npm run tauri build -- --bundles nsis`。生成物在 Cargo target 的 `release/bundle/deb/` 或 `release/bundle/nsis/`。Windows 安装器嵌入 WebView2 引导程序，缺失运行时时需要网络下载微软运行库。

Linux 包名为 `djhx-memo`，桌面显示“简记”；GNOME 托盘需启用 AppIndicator 扩展。托盘宿主无法确认时，窗口明确提示“关闭后退出”，不会隐藏到无法恢复的位置。

前端仅经 `core_invoke` 调用 [JSON 桥接](../spec/core-bridge.md)，不持有 token、数据库路径或同步状态机。系统 keyring 不可用时，账号凭据仅驻留进程并有提示。编辑缓冲在提交成功前保留，保存失败可复制或另行导出 Markdown 救援。

见 [产品规范](../spec/product-v1.md)、[UI 接口](../spec/core-api.md)、[桌面验证记录](../docs/desktop-validation.md) 与 [三端验收](../docs/validation.md)。

安装包归档在仓库根目录 `dist/linux/` 和 `dist/windows/`，各有 `SHA256SUMS`。包内 `licenses/` 携带 core、桌面 Rust 与实际打入前端的 npm 依赖许可证原文；生成方法见 ADR-0007。当前验证环境和剩余验收项以桌面验证记录为准，候选包不代表 v1 发布验收已完成。
