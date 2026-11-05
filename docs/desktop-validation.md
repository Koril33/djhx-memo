# 桌面客户端验证记录

日期：2026-11-03 至 2026-11-05。对象为 0.1.0 自用候选，链接 core 冻结点 `0549c3e` 的源码。打包、自动检查和已实测路径分别记录；不将候选包视作完整 v1 发布验收。

## 自动检查与产物

| 检查 | 环境 | 实际结果 |
| --- | --- | --- |
| `npm ci` | Linux / Windows，Node 22.23.2、npm 10.9.8 | 同一 package-lock.json 安装成功 |
| `npm run format:check`、`npm run lint` | Prettier / ESLint，Linux 与 Windows | 通过，独立于类型检查 |
| `npm run check` | Svelte 5 / TypeScript 6 | 0 错误、0 警告 |
| `npm test` | Vitest 5 | 3 个文件、16 项通过 |
| `npm run build` | Vite 8 | 成功，JS 643.02KB、gzip 222.73KB；仍有大于 500KB 的块体积提示 |
| `cargo fmt --check` | Rust 1.99.1 | 通过 |
| `cargo check --locked` | Ubuntu 24.04 构建容器 | 通过 |
| `cargo clippy --locked -- -D warnings` | 同上 | 2026-11-05 通过 |
| `cargo test --release --locked --bin djhx-memo` | 同上 | 1 项通过：导出不覆盖已有文件，失败不留部分临时文件 |
| `npm run tauri build -- --bundles deb` | Ubuntu 24.04 容器，GTK3 / WebKitGTK 4.1 | 最终增量构建成功 |
| `npm run tauri build -- --bundles nsis` | Windows 10 22H2 x64 / MSVC 14.44 / SDK 26100 | 原生构建成功，包含 WebView2 引导程序 |

前端回归覆盖 300ms 保存、连续输入 1s 上界、飞行中续写串行、保存失败保留与阻止切换、generation 冲突救援、迟到回包不回退、空草稿/空笔记、HTML/协议/图片过滤、代码块任务排除与源码行映射。独立评审后增加：组合输入期间不提升远端基准；关闭握手延迟期间冻结新输入；首次 create 已落盘但响应丢失后重试并继续保存较新正文；旧预览任务行在正文移动后拒绝执行。相关评审见 [desktop-review.md](reviews/desktop-review.md)。

归档文件为 `dist/linux/djhx-memo-0.1.0-linux-amd64.deb` 与 `dist/windows/djhx-memo-0.1.0-windows-x64-setup.exe`，各目录的 `SHA256SUMS` 为最终校验依据。Linux 包名为有效 ASCII `djhx-memo`，桌面显示“简记”。deb 与 NSIS 实际均携带 core、桌面 Rust、npm 三份许可证通知。源码与锁文件提交入 Git，安装包为本地生成物。

## 实际桌面验证

### Windows 10 虚拟机

系统为 Windows 10 22H2，OS build 19045，x86_64；已存在 WebView2 Runtime。NSIS 当前用户静默安装退出码 0，安装后显示中文三栏界面。

实际通过：

- 首次未登录输入中文 Markdown，列表实时出现笔记，状态显示保存完成。
- 预览任务勾选后回到源码，仅真实任务标记变为 `[x]`，围栏内的任务仍为 `[ ]`。
- 移到回收站后正文保留，恢复后同一笔记可继续使用。
- 窗口关闭后进程仍存在且窗口不可见；再次启动 exe 时第二实例退出、原窗口恢复，主进程数为 1。
- 从真实托盘菜单退出，主进程数为 0；重新打开后列表仍显示笔记。
- 在退出后读取该测试空间 SQLite，正文与预期中文源码逐字相等、deleted=false、generation=4。未把正文打印到验证日志。

最终包额外纳入外壳 clippy 的纯语法修正，已重新原生打包并覆盖安装（退出码 0）；安装目录 exe 的 SHA256 与从最终 NSIS 解出的载荷一致（`c639508c3dc7f30efc1ba5faba16524a010ff34d4f183c3a613f1c42a5f5eb05`）。最终包再次验证关闭隐藏、第二实例退出并恢复原窗口。首次安装与覆盖安装分别执行，不把缺失 WebView2 的场景视为已测。

### Linux 宿主与 Ubuntu 虚拟机

构建基线容器为 Ubuntu 24.04；实际 GUI 宿主为 Debian 13.7 / GNOME Wayland，虚拟机为 Ubuntu Desktop 26.04.1 / GNOME Wayland，两者不可替代 Ubuntu 24.04 真桌面验收。

宿主实际通过：中文源码输入与自动保存、列表刷新、任务勾选只改标记且围栏不变、回收站保留与恢复；WM 关闭后窗口隐藏且进程存活，第二实例激活已有窗口，托盘菜单退出。该轮 GUI 路径在最终 core 同修订号代次修正前执行，最终包另行重建。

Ubuntu 虚拟机实际通过：最终 deb 安装/覆盖安装，`desktop-file-validate`，原生 Wayland 窗口显示，未登录新草稿通过物理键盘输入并在 SQLite 核验已提交，重新启动后已有笔记仍在列表。初期 XTest/剪贴板自动化受 Wayland 焦点及剪贴板桥接影响，断言失败，未计为通过；后改用 AT-SPI 焦点与虚拟机物理键盘完成新草稿保存。模拟逐键 Markdown 输入还会触发编辑器自动续列表，不能把其生成内容当作原始粘贴样本；Ubuntu 任务和中文输入法矩阵仍待人工验收。

Linux 托盘宿主不可确认时，UI 提示关闭后退出，避免隐藏到无法恢复的位置；宿主已确认 AppIndicator 的路径及 Ubuntu 扩展启动时序需分别看待。

## 可重复执行

Linux 开发包：`build-essential pkg-config libgtk-3-dev libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev patchelf`。运行时需要 WebKitGTK 4.1、AppIndicator 与 Secret Service；deb 声明运行依赖并推荐 gnome-keyring。`libglib2.0-bin` 提供 gdbus，托盘探测超时 1 秒。

```sh
cd desktop
npm ci
npm run format:check
npm run lint
npm run check
npm test
npm run build
cd src-tauri
cargo fmt --check
cargo check --locked
cargo clippy --locked -- -D warnings
cargo test --locked --release --bin djhx-memo
cd ..
npm run tauri build -- --bundles deb
```

Windows 安装 Rust MSVC 工具链、Visual Studio C++ Build Tools、Node 后执行相同前端检查与 `npm run tauri build -- --bundles nsis`。本轮使用本机 QEMU/KVM 的原生 Windows VM，未以 Linux 交叉编译代替。

## 尚待验收

以下不宣称通过：Ubuntu 24.04 真桌面、Linux X11/Wayland 全组合与中文输入法实际组合输入/撤销矩阵、Windows 11、缺失 WebView2 的下载安装、ZIP 原生对话框全平台端到端与故障磁盘救援、桌面 UI 账号切换/设备吊销/多设备同步的完整人工矩阵、1,000 条性能样本、7 天自用。核心与服务端联调结果单独见各模块记录，不替代这些桌面验收。完整发布标准见 [validation.md](validation.md)。
