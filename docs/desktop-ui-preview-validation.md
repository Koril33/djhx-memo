# 桌面 0.1.1 界面预览验证

本轮对象为 `codex/quiet-ui-preview` 的桌面 0.1.1 展示增量。依据 [产品规范 §11](../spec/product-v1.md) 与[共同界面设计](ui-design.md)，只调整客户端展示和入口；core 仍为 0.1.0，未修改同步、鉴权、数据目录或安装身份。本文分开记录构建与实际目标系统验收，不把界面预览视作 v1 完整发布。

## 实现范围与保护措施

- 使用纸白/炭灰与品牌绿的成对语义颜色、系统字体、4/8 间距。导航缩为 60px；DJHX Memo 品牌、搜索与列表占独立区域，编辑器占主体。
- 普通操作改为随包 Lucide 线性 SVG，图标按钮及菜单行至少 36px，有中英可访问名称和悬停提示。来源、锁定版本及许可见 [ADR-0011](adr/0011-offline-interface-icons.md)。包内增加 Lucide 许可，未引入 npm 或 Rust 依赖。
- 当前空间导出/账号入口放列表更多菜单，当前笔记删除/恢复、复制和文字导出放笔记更多菜单。菜单支持键盘、Esc、外点、焦点移出与窗口失焦收起；执行和 Esc 返回入口焦点。现有操作的保存流程和导出范围保留。
- 底部紧凑状态展开后分别显示本地保存、云同步和最近成功时间，并可手动同步。未保存/组合输入仍显示保存中；保存失败、凭据失效、冲突、只读恢复、密钥环故障和关闭退出模式继续可见，复制/重试/导出救援保持可用。
- 源码、预览、对照切换使用 CSS 隐藏面板，保持 CodeMirror 实例、撤销与组合输入状态。切换笔记的 `documentKey` 仍只在既有保存后切换流程中更新，不以主题、语言或布局为 key。
- Markdown 任务控件复用静态 `check` 图标；用户 Markdown 的 SVG/HTML 继续转义，不能提供图标标签、属性或路径。只读任务显示 checked/read-only 语义。
- 桌面 package/锁文件、Cargo 本 crate/锁文件与 Tauri 均为 0.1.1；应用 ID、二进制名、keyring、Linux 包名和旧 NSIS 安装身份不变。

## 自动检查与 Linux 构建

根据 Ubuntu 首轮实机反馈，最终展示收尾将浅色次级字色提高为 `#6B726B`，源码去行号并与正文使用同一系统 sans 字体，保留语法高亮及原编辑实例。三个原生 details 的 summary 明确标注 button 角色，补足 WebKitGTK 的 AT-SPI 角色识别；展开行为仍由原生 details 提供。Svelte 对这一显式兼容角色的冗余提示仅在对应三处附原因说明后局部忽略，最终类型检查仍为 0 错误、0 警告。

宿主使用 Node 22.23.2、Rust 1.99.1；原生依赖检查与打包复用 Ubuntu 24.04 构建镜像 `djhx-memo-linux-build` 和现有 target 缓存，不改宿主 GTK/systemd 软件包。

| 检查 | 实际结果 |
| --- | --- |
| `npm run format:check`、`npm run lint` | 通过 |
| `npm run check` | 0 错误、0 警告 |
| `npm test` | 4 文件、26 项通过；既有 25 项保留，新增任务矢量图标/只读状态/用户 SVG 转义回归 |
| `cargo fmt --check` | 通过 |
| `cargo clippy --locked --offline -- -D warnings` | Ubuntu 24.04 容器通过 |
| `cargo test --release --locked --offline --bin djhx-memo` | 2 项通过：portal 系统主题解析、导出不覆盖已有文件 |

前端回归继续覆盖保存防抖、连续输入上界、飞行中续写、IME/并发代次、错误救援与预览快照，以及语言/时区/持久偏好、完整双语与数量单复数、错误原始 payload 和原型属性保护。本轮新增控件回归不替代真实窗口中的焦点和中文输入法验收。

Linux 构建命令为：

```sh
cd desktop
npm run tauri build -- --config src-tauri/tauri.linux.conf.json --bundles deb
```

构建中仍有大于 500KB 的前端块体积提示，未视作错误，也未为消除提示改变编辑器加载流程。当前构建 CSS 16.99KB（gzip 4.25KB）、JS 683.42KB（gzip 236.38KB），实际捆绑 23 个已锁定 npm 包；本轮动态 SVG 组件使原有 Svelte 传递依赖 clsx 进入产物，已重新生成实际 npm 许可通知。

当前 Linux 包为 `.local/desktop-linux-target/release/bundle/deb/djhx-memo_0.1.1_amd64.deb`（7,004,538 字节，6.68MiB）；包名 `djhx-memo`、版本 0.1.1、架构 amd64。deb SHA256 为 `3cbf23978b488b2355d242e0005b9784194793a5f8af8edc0053d278b85f4572`，包内 `/usr/bin/djhx-memo` 载荷为 `dd8080d726243858759a9c679d2b3b5557b10f332b4b34f922050ac9358e43e7`。已核包内 core/桌面 Rust/npm、Tauri 安装模板及 Lucide 共五份许可；最终归档以 `dist/linux/SHA256SUMS` 为准。

## 目标系统实测

以下记录本轮实际目标系统检查。Ubuntu 24.04 真桌面、中文 IME 完整矩阵、Windows 11、全三端同步矩阵及 7 天试用等未完成验收继续以[总验证记录](validation.md)为准。


### Windows 10 原生构建、升级与窗口验收（2026-11-08）

在现有 QEMU/KVM Windows 10 22H2 x64（10.0.19045.6466）虚拟机原生构建，使用 Node 22.23.2、Rust 1.99.1、已安装的 MSVC 与 WebView2；未更改安全策略。最终构建同步 desktop、core 与随包图标/许可资源，108 个源码文件逐个 SHA256 对齐；最终源码 ZIP 的 SHA256 为 `a7efa42e42b24a79c6cc1f5e538c2798067e1038a0b3b00e41f2cd1335fd4679`。

| 检查 | 实际结果 |
| --- | --- |
| 原生 `npm ci --offline`、format、lint、Svelte check | 通过；类型检查 0 错误、0 警告 |
| 原生 `npm test` | 4 文件、26 项通过 |
| 原生 `npm run tauri build -- --bundles nsis` | 最终 0.1.1 NSIS 构建成功；相同前端块体积提示，无构建错误 |
| 原生 `cargo test --release --locked --offline --bin djhx-memo` | Windows 适用的 1 项导出保护测试通过；Linux portal 测试不在 Windows 编译运行 |
| 0.1.0 → 0.1.1 覆盖安装 | 安全退出应用后执行 `/S`，exit 0；真实原有 1 条笔记的完整记录哈希前后一致 |
| 最终展示收尾包覆盖 | 再次 `/S` exit 0；4 条隔离演示笔记的记录哈希不变；English、Dark、UTC 偏好跨托盘退出、覆盖安装与重开保留 |
| 安装身份与载荷 | 仍仅有旧 `简记` 卸载注册表项、原安装目录与 DJHX Memo 快捷方式；最终安装 EXE 哈希等于 NSIS 解包载荷；五份许可齐全 |

最终归档 `dist/windows/djhx-memo-0.1.1-windows-x64-setup.exe` 为 6,489,464 字节，SHA256 `e2d0a34fc96ccd6ff08c162b4fc8a63381ab91d7c2de5be3d957871974a32342`。NSIS 包内及实际安装的 `djhx-memo.exe` 均为 `07958ed688ac06a18b5b816e8eed1955c828cf15623b08e6665e8d0c568043e7`；`dist/windows/SHA256SUMS` 的 0.1.0/0.1.1 两包校验均通过。本轮未新增签名能力，也未在缺少 WebView2 的系统重做安装矩阵。

真实安装窗口通过 Windows UI Automation、QEMU 鼠标键盘及原生截图核验：

- 中文浅色的预览、源码、偏好设置与状态详情；英文深色的主界面和设置；980×700 与最小 800×650 外框均可用。源码使用最终系统 sans 字体、无行号；搜索、品牌、编辑模式与更多入口不重叠，设置关闭线性图标完整且按钮至少 36px。
- 菜单可用 Tab 进入，Esc 收起并返回入口焦点，外点收起；空间导出与当前笔记操作范围分别列出。实际点击当前笔记移到回收站再恢复，演示笔记数量 4→3→4，正文集合哈希恢复一致；没有物理删除。
- 保存状态展开可分别查看本地保存、云端同步和最近成功同步；本机空间显示“仅本机”，手动同步禁用。界面未把本机保存误写成云同步成功。
- 源码输入后切到预览再回源码，撤销并保存后正文集合哈希与输入前一致，原编辑器撤销保留。最终收尾包再次复核内容恢复；此次没有替代完整中文 IME 组合输入矩阵。
- 默认显示时区为“上海 · UTC+08:00”，英语时区偏好可切 UTC；中文/英文即时切换。跟随系统模式下将 Windows 应用主题 dark→light→dark，内容和原生标题栏同步变化；测试后恢复原系统值。
- 关闭窗口保存后进入真实系统托盘；真实托盘中英菜单和退出均验证。正常退出后应用进程为 0；没有强杀笔记进程。

截图使用公共 core 接口创建的 4 条本机演示笔记。拍摄前先审计原目录、确认应用已退出，再以带恢复 journal 的同盘原子改名保护 Roaming/Local 两个原目录；没有把真实笔记用于截图。结束后两目录均已恢复，备份改名路径均不存在，原有 1 条笔记完整记录 SHA256 为 `9a3488d6cdcd1216b497266ccab51457673cc5f7ddc2e54d927b1c32e680fb7e`，与隔离前及恢复后完全一致。系统主题原值 `AppsUseLightTheme=0` 已还原。

原生截图和脱敏检查记录保存在本机忽略目录 `.local/quiet-ui-20261108/windows-artifacts/`：`windows-zh-light-main.png`、`windows-zh-light-editor.png`、`windows-zh-light-settings.png`、`windows-zh-light-status.png`、`windows-en-dark-narrow-main.png`、`windows-en-dark-narrow-settings.png`，以及主题切换/安装/恢复记录。临时 Windows worker、.NET 辅助程序、演示数据与下载目录已清除，宿主临时 HTTP 8765 已停止；保留虚拟机、工具链、构建缓存与真实用户数据。

本节只记录本轮界面预览和现有 Windows 10 环境；Windows 11、WebView2 缺失/安装失败、完整中文 IME、三端同步矩阵及连续 7 天试用仍未据此通过。


### Ubuntu 26.04 Wayland 与本机覆盖安装（2026-11-08）

在已运行的 Ubuntu 26.04 GNOME Wayland 虚拟机安装 0.1.0→0.1.1，并以最终收尾 deb 再覆盖同版本。包内及实际 `/usr/bin/djhx-memo` 均为上述 `dd8080d7…` 载荷。使用独立 XDG_DATA_HOME/CONFIG_HOME/CACHE_HOME；4 条中文演示笔记由 core 公共接口创建，不读取或替换用户原数据目录。

实际通过的检查：

- 中文浅色与英文深色的列表、源码、预览、设置及状态详情。纸白/炭灰、系统 sans 正文、无行号、统一线性图标与固定关闭按钮均在最终包中确认；三个原生展开入口在 AT-SPI 中是 button，名称正确。
- 最小 800px 窗口（GNOME 左侧贴靠）的英文深色设置及主界面可用，设置关闭按钮实测 36×36，未裁切。语言/外观/时区可键盘选择，关闭设置不会退出应用。
- 跟随系统时，GNOME prefer-dark→prefer-light→prefer-dark 后内容及原生标题栏对应变化；结束已还原原系统值 prefer-dark。显式 Dark 仍优先，未改操作系统时区。
- 最终包中实际键盘输入并提交，切到预览再返回源码，Ctrl+Z 后数据库正文与输入前完全相同，编辑器撤销记录保留。测试按真实键盘按下/释放时序进行，不以程序替换正文模拟编辑。
- 搜索框物理键盘输入无匹配查询后列表清空，清除后四条演示笔记恢复。此项是过滤与交互冒烟，不替代完整中文输入法/短词搜索矩阵。
- 空间更多菜单可达导出/空间/设置，笔记菜单可达删除/复制/文字导出，Esc 可关闭。实际经新菜单移到回收站→进入回收站→恢复→返回列表，数量 4→3→4；四条笔记的 ID、正文与最终 deleted 状态集合哈希与原 fixture 一致。
- 紧凑状态可展开分别显示本地已保存、仅本机、最近成功时间；本机空间手动云同步禁用。未显示虚假的“已同步”。
- 通过真实 GNOME 托盘中英文退出正常结束进程，确认无剩余笔记进程，再重新打开最终包；English、Dark、UTC 三项显式偏好保留。演示结束前将隔离偏好恢复为中文/浅色/上海，截图仍仅包含演示笔记。

最终截图位于本机忽略目录 `.local/quiet-ui-20261108/`：`desktop-main-zh-light.png`、`desktop-source-zh-light.png`、`desktop-settings-zh-light.png`、`desktop-status-zh-light.png`、`desktop-main-en-dark.png`、`desktop-settings-en-dark-narrow.png`，以及系统主题和重启证据；精选副本归档于 `dist/previews/0.1.1/`。没有浏览器仿制截图。

宿主 Debian 13 也已通过 dpkg 将 0.1.0 覆盖为最终 0.1.1，安装和实际二进制哈希核对通过。为保留用户正在使用的编辑会话，安装时没有退出其现有进程；应从托盘退出后重开加载新版本。没有据此宣称宿主完整 GUI 回归或 Ubuntu 24.04 原生运行通过。
