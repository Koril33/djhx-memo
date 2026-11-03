# desktop

Windows / Linux Tauri 2 客户端。Rust 后端直接链接 core，本地前端在本目录内采用 Svelte + TypeScript + Vite，编辑器使用 CodeMirror 6；不依赖尚未开发的独立 Web 工程。

v1 包含托盘、未登录笔记、账号、Markdown、搜索、回收站、导出与同步。全局快捷键、开机自启、自动升级暂缓。默认验收 Windows 11 x86_64 与 Ubuntu 24.04 x86_64；Linux 验证 Wayland/X11、输入法及 AppIndicator 环境。

当前没有 Tauri/前端工程或构建命令。先做真实环境托盘和核心调用验证；托盘失败时不能把窗口隐藏到无法恢复的位置。见 [产品规范](../spec/product-v1.md)、[UI 接口](../spec/core-api.md) 与 [本地验证](../docs/validation.md)。
