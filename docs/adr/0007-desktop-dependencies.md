# ADR-0007：桌面客户端的锁定依赖与安全边界

- 状态：已接受实现；跨平台验证结果另记
- 日期：2026-11-03
- 范围：`desktop/`，补充 ADR-0001 的具体版本

## 决策

在官方 npm registry 与 crates.io 的发布元数据中复核以下直接依赖的版本、许可证、维护仓库和兼容条件后引入。使用稳定的 Tauri 2，不使用 3 alpha。Svelte 检查器目前只支持 TypeScript 5/6，因此选择 6.0.3，不强行使用 TypeScript 7。所有精确版本写入清单，完整传递依赖由 `package-lock.json`、`src-tauri/Cargo.lock` 锁定。

| 依赖 | 精确版本 | 许可证 | 用途与维护来源 |
| --- | --- | --- | --- |
| svelte | 5.57.3 | MIT | UI；[Svelte 官方](https://github.com/sveltejs/svelte) |
| typescript | 6.0.3 | Apache-2.0 | 类型检查；[Microsoft](https://github.com/microsoft/TypeScript) |
| vite | 8.3.5 | MIT | 本地前端构建；[Vite 官方](https://github.com/vitejs/vite) |
| @sveltejs/vite-plugin-svelte | 7.3.1 | MIT | Svelte 官方 Vite 插件；[官方仓库](https://github.com/sveltejs/vite-plugin-svelte) |
| svelte-check | 4.7.8 | MIT | Svelte/TS 检查；[Svelte 官方](https://github.com/sveltejs/language-tools) |
| @tauri-apps/api / @tauri-apps/cli | 2.12.4 | Apache-2.0 OR MIT | IPC 与打包；[Tauri 官方](https://github.com/tauri-apps/tauri) |
| @codemirror/state | 6.7.6 | MIT | 编辑状态；[作者维护](https://code.haverbeke.berlin/codemirror/state) |
| @codemirror/view | 6.43.15 | MIT | 中文组合输入与编辑视图；[作者维护](https://code.haverbeke.berlin/codemirror/view) |
| @codemirror/commands | 6.11.1 | MIT | 撤销和标准编辑键位；[作者维护](https://code.haverbeke.berlin/codemirror/commands) |
| @codemirror/lang-markdown | 6.5.2 | MIT | Markdown 语法；[作者维护](https://code.haverbeke.berlin/codemirror/lang-markdown) |
| @codemirror/language | 6.13.1 | MIT | 标题、代码、链接高亮；[作者维护](https://code.haverbeke.berlin/codemirror/language) |
| marked | 18.1.3 | MIT | CommonMark/GFM 分词；[官方维护](https://github.com/markedjs/marked) |
| vitest | 5.1.0 | MIT | 缓冲保存和 Markdown 安全回归测试；[官方维护](https://github.com/vitest-dev/vitest) |
| prettier / prettier-plugin-svelte | 3.9.10 / 4.1.1 | MIT | 前端格式检查；[Prettier](https://github.com/prettier/prettier)、[Svelte 官方插件](https://github.com/sveltejs/prettier-plugin-svelte) |
| eslint / @eslint/js | 10.14.0 / 10.0.1 | MIT | JavaScript 静态检查；[ESLint 官方](https://github.com/eslint/eslint) |
| typescript-eslint | 8.72.1 | MIT | TypeScript 静态规则，支持 TS <6.1；[官方维护](https://github.com/typescript-eslint/typescript-eslint) |
| eslint-plugin-svelte | 3.24.0 | MIT | Svelte 模板与组件检查；[Svelte 官方](https://github.com/sveltejs/eslint-plugin-svelte) |
| globals | 17.14.0 | MIT | ESLint 浏览器/Node 全局变量表；[作者维护](https://github.com/sindresorhus/globals) |
| tauri | 2.12.5 | Apache-2.0 OR MIT | 原生 WebView、托盘、窗口；[Tauri 官方](https://github.com/tauri-apps/tauri) |
| tauri-build | 2.7.4 | Apache-2.0 OR MIT | Tauri 构建脚本；同上 |
| tauri-plugin-single-instance | 2.5.5 | Apache-2.0 OR MIT | 第二实例激活主窗口；[官方插件](https://github.com/tauri-apps/plugins-workspace) |
| tauri-plugin-dialog | 2.8.4 | Apache-2.0 OR MIT | 原生导出路径选择；同上 |
| tauri-plugin-opener | 2.7.2 | Apache-2.0 OR MIT | 系统浏览器打开受限 HTTP(S) 链接；同上 |
| keyring | 4.2.0 | MIT OR Apache-2.0 | Linux Secret Service / Windows Credential Manager；[官方维护](https://github.com/open-source-cooperative/keyring-rs) |
| base64 | 0.23.1 | MIT OR Apache-2.0 | 解码核心 ZIP 返回值；[作者维护](https://github.com/marshallpierce/rust-base64) |
| serde / serde_json | 1.0.229 / 1.0.154 | MIT OR Apache-2.0 | IPC 类型与桥接 JSON；[Serde 官方](https://github.com/serde-rs) |

## 平台与数据边界

上述前端工具要求 Node 22.12+，Rust 桌面外壳依赖最低要求 1.90，整体工程因链接 core 要求 Rust 1.99+；本机 Node 22.23.2、Rust 1.99.1 可用。Linux 构建依赖 WebKitGTK 4.1、GTK3、AppIndicator；deb 声明运行依赖。Linux 额外探测 StatusNotifierHost，无法确认托盘可恢复时关闭窗口改为保存后退出，界面明确提示。

系统凭据只经 Rust 的 CredentialStore 访问。密钥环不可用时向核心返回失败，让核心只在进程内保存并在 UI 提示，不退化到明文文件。前端无文件系统、进程或通用 URL 打开权限。

Marked 仅用于结构解析：自定义渲染器逐项转义 HTML，移除图片，链接仅允许 HTTP(S)，任务按钮只携带源码偏移。原始 HTML 作为文本显示。CSP 同时限制网络、图片和 frame。保存与同步仍完全属于 core；前端只保留尚未提交的编辑缓冲与提交回执。

导出由原生对话框选址，ZIP 数据由 core 产生，平台使用同目录临时文件和不覆盖已有目标的落盘方式。救援正文导出独立于数据库，可用于保存失败时保全内存文本。

## 许可证随包交付

`python3 scripts/cargo-notices.py desktop/src-tauri/Cargo.toml desktop/THIRD_PARTY_RUST_NOTICES.txt` 基于 Cargo 锁定元数据收集 Rust 包及原始许可证（包含锁定的跨平台可选依赖）。`npm run build` 先由 Vite 输出实际模块所属包，再由 `scripts/npm-notices.mjs` 根据 package-lock.json 收集打入前端的 22 个运行包和许可证原文，输出 `THIRD_PARTY_NPM_NOTICES.txt`。这些收集脚本只用已安装工具，不引入新依赖。

Tauri resources 将上述两个通知及 `core/THIRD_PARTY_NOTICES.txt` 一同装入 Linux `/usr/lib/djhx-memo/licenses/` 或 Windows 安装目录的 `licenses/`。项目自身的授权决策不由本 ADR 代作。
