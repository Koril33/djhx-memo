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

## 2026-11-07 至 2026-11-08 品牌与本机偏好增量

本轮依 [产品规范 §10](../spec/product-v1.md) 增加桌面中英双语、系统/浅色/深色外观、本机显示时区偏好及 DJHX Memo 品牌。偏好仅写 WebView 本地存储，与 core 数据、服务器归属及凭据身份无关；无需迁移 core 数据。编辑器通过 CodeMirror compartment 更新语言与 CSS 变量切换主题，不以语言/外观作为组件 key。设置关闭按钮改为固定点击区域内的 SVG，避开字体乘号基线裁切。生产默认地址为 `https://memo.djhx.site`。

本轮实际执行：

- Linux 宿主 `npm run format:check`、`npm run lint`、`npm run check` 通过；类型检查 0 错误、0 警告。
- `npm test`：4 文件 25 项通过；既有 16 项保留，新增 9 项覆盖持久偏好往返、无效或不可读存储回退、系统语言和明暗优先级、上海与 UTC 跨日、语言与时区独立、生成空间标签与用户自定义名称隔离、双语完整性及插值参数一致、已知错误的本地化与原始错误抑制、Markdown 任务可访问名称及图片阻止提示。偏好写入失败被调用方显示为本地化错误；不将该次选择误报为成功持久化。
- `npm run build` 成功；最终前端 JS 674.57KB、gzip 233.21KB，仍存在大于 500KB 的体积提示。
- Rust `cargo fmt --check` 通过；Ubuntu 24.04 构建容器复用已锁定依赖及缓存，`cargo clippy --locked --offline -- -D warnings` 通过，`cargo test --release --locked --offline --bin djhx-memo` 2 项通过（已有导出不覆盖测试及新增 portal 设置解析）。

Linux 增量 deb 已于本轮在 Ubuntu 24.04 容器构建成功（6.68MiB）；包名仍为 `djhx-memo`，`.desktop` 启动器显示 DJHX Memo，统一图标、原有三份依赖许可证和 NSIS 模板 MIT 原文均进入包内。构建时的 SHA256 为 `93e53718f1fb6a8cfd1021db7cae3df2a9ef7bcc2bfc0f310260877364baeddb`，包内 `/usr/bin/djhx-memo` 载荷 SHA256 为 `f86368249d0ac7404dab37b40a0840a25116774f34a341fa19db594c2d16601a`；最终归档校验值以 `dist/linux/SHA256SUMS` 为准。品牌资产协作者对桌面偏好、i18n、日期、编辑器重配置、CSS 与原生托盘接口做了独立静态审阅，未发现阻断项。

Windows 品牌改名另外固定 NSIS 旧安装注册表身份与默认目录，保留官方覆盖安装流程；同版本上游模板及 MIT 原文一并归档。独立复审发现 `/UPDATE`、`/NS` 模式先删旧快捷方式可能导致入口丢失，已改为核验目标后改名迁移，目的入口存在或移动失败时保留旧入口，非关键改名失败不会中断安装。修复后独立复审未见进一步阻断；Windows 实际覆盖安装与卸载项核验另记。

Ubuntu 26.04 实际 GUI 检查发现：GNOME `prefer-dark` 与 portal `uint32 1` 已生效，但 GTK3/WebKit 的 `matchMedia` 仍返回浅色，不能据此宣称系统跟随通过。已补原生 portal 优先、GNOME gsettings 回退的只读探测，仅跟随系统模式每 2 秒异步检查，显式 light/dark 保持优先；原生标题栏使用同一解析结果。此修正新增 portal 嵌套 variant/未知值解析测试，最终 GUI 结果由本轮整合记录补充。

Android 协作者独立审阅另发现未知服务端错误码仍被拼入界面，且普通对象索引可误命中 `constructor` / `toString` / `__proto__` 原型属性。已为 core 与 native 两张映射表加入自身属性检查，仅已知稳定码附带固定诊断码；未知码只显示当前语言的通用错误。新增双语回归覆盖未知码、中文原始 payload、4,096 字符长码及原型属性，确认不会回显或导致渲染异常。

英文数量文案集中补齐单复数：8 个实际含 `{count}` 的消息由类型推导强制维护单数模板，使用 `Intl.PluralRules('en')` 选择。回归逐项验证 1 / 0 / 2、字符数实际传入的格式化字符串、单复数插值参数一致以及中文文案保持不变；未改无数量文案。

上述自动检查不等于 Windows/Linux 原生 GUI 的语言、主题、中文 IME、系统明暗动态变化和升级持久化全部验收；实际 VM 检查及最终安装包由本轮整合记录补充。之前尚待验收的三端矩阵、参考系统和连续 7 天试用不因本轮外观修改而视为完成。

### 2026-11-08 Ubuntu 展示与最终覆盖安装

在原有 Ubuntu Desktop 26.04.1 / GNOME Wayland VM 上原生安装、覆盖安装；测试启动使用独立 XDG 数据/配置/缓存目录，不打开真实账号和笔记。通过 AT-SPI 定位实际控件、虚拟机物理按键选择选项和屏幕截图复核，未用前端模拟渲染代替。

实际通过：

- 窗口与左上角只显示 DJHX Memo，GNOME 启动器/顶栏使用统一图标；账号表单默认 `https://memo.djhx.site`，新偏好默认上海。
- 设置由中文切换英文后，页签、表单、偏好、关闭控件及主界面即时翻译；选择英文、浅色、UTC 后关闭并重启应用，三项保持，最后覆盖安全错误提示修复包仍保持。
- 系统设 `prefer-dark` 时应用选择浅色仍保持浅色，标题栏相同；选择跟随系统后，实际切换系统深色→浅色→深色，每次等待约 3 秒后截图确认应用、输入控件和原生标题栏一致。测试后恢复原系统 `prefer-dark`，未调整系统语言和时区。
- 将窗口贴靠到最小 800px 宽，英文浅色和中文深色设置页均可读，SVG 关闭图标完整居中且焦点可见；点击关闭仅收起设置，Esc 同样可关闭设置。没有把请求缩放成功但窗口未变化的 AT-SPI 调用作为验收证据。
- 设置与错误提示修复包 `55b6579454494bef6c91805f7a32faa89027e65e723354827834ad1e9b59da30` deb 原生覆盖安装成功，`desktop-file-validate` 通过；安装后的 `/usr/bin/djhx-memo` 与该 deb 内载荷 SHA256 同为 `8c017f4946a548547c47fe6c1a3c97403e296c3b2204884c761e68ebb0aed11d`。此后仅增补英文数量单复数，最终包的增量检查另记。

最终 deb 归档到 `dist/linux/djhx-memo-0.1.0-linux-amd64.deb`。可见截图保留于忽略目录 `.local/presentation-20261107/ubuntu-final-*.png`；早期锁屏、黑屏和未实际缩放截图不作为通过依据。本增量没有重复完整笔记/同步矩阵，也不替代 Ubuntu 24.04 真桌面、中文输入法组合及其他尚待验收项。

本机 Debian 13.7 同时对最终 deb 执行覆盖安装成功；保留用户正在运行的旧进程，需从托盘退出后重开加载新版。Ubuntu 隔离验证进程、临时助手及临时 SSH 公钥均已清理，VM 保持运行，原有用户配置和笔记目录未清理。

最后的英文数量修正包 `93e53718f1fb6a8cfd1021db7cae3df2a9ef7bcc2bfc0f310260877364baeddb` 再次在 Ubuntu VM 与 Debian 宿主覆盖安装成功；安装载荷与包内同为 `f86368249d0ac7404dab37b40a0840a25116774f34a341fa19db594c2d16601a`。在新的隔离英文空间中通过实际键盘输入一条测试笔记，自动保存完成，截图目视确认列表为 **1 note**（而非固定复数），界面保持系统深色；证据 `.local/presentation-20261107/ubuntu-final-one-note.png`。此增量另经 25 项前端测试；未重复已记录的全套主题矩阵。最终文件 7,002,846 字节，归档与子目录校验文件已更新。


### 2026-11-08 Windows 原生升级与展示验证

在现有 Windows 10 22H2 x86_64 VM（`10.0.19045.6466`，已安装 WebView2）中使用既有 Node 22.23.2、Rust MSVC 与构建缓存原生编译。最后一次构建执行 `format:check`、`lint`、Svelte/TypeScript 检查（0 错误、0 警告）、4 文件 25 项测试和 NSIS 打包，均通过。最终前端为 `index-CT9cfHzW.js`，含 `Object.hasOwn` 安全映射和 `Intl.PluralRules`；VM 中 `bridge.ts`、`i18n.ts` 的 SHA256 与本机最终源码一致，未以旧退出码或空日志作为新包证据。Vite 的大于 500KB 体积提示仍存在。

最终安装包 `dist/windows/djhx-memo-0.1.0-windows-x64-setup.exe` 为 6,492,662 字节，SHA256：`750c1e9044c0a4398aab3a2c0372f3cd70310d1fe20606609d00c10d2a72be67`。在退出应用后执行 `/S` 覆盖安装，退出码为 0；安装后的 `djhx-memo.exe` 与从该 NSIS 解出的载荷 SHA256 同为 `1a80eac54b8030bb403afe6c8c3782475a53e52322e8d96bd68f61110249e553`。Tauri 会为 NSIS 补丁处理二进制，验证比较的是实际打入安装器的载荷，而非未补丁的构建目录 exe。

实际通过：

- 从原“简记”候选版覆盖升级，继续使用原安装目录、`app.djhx.memo` 数据及凭据身份；原“简记”卸载注册表键的显示名更新为 DJHX Memo，没有新建第二个 `DJHX Memo` 卸载键。旧的本安装目录桌面/菜单快捷方式改名迁移，IShellLinkW 检查新快捷方式指向原安装目录中的正确 exe。Explorer 最初保留旧图标残影，刷新桌面后消失，未通过删除其他目标的入口来处理缓存。
- 升级前未运行应用，使用只读 SQLite 对既有本机空间的所有笔记字段排序计算哈希；两次修正版最终覆盖后仍为同一条未删除笔记、同一整行哈希，未打印正文、清空数据库或注销凭据。安装的模板 MIT 许可文件内容完整。
- 新偏好默认跟随系统语言/外观、上海；账号表单实际控件值为 `https://memo.djhx.site`。切换简体中文与 English 后设置、表单、主界面及原生托盘菜单随之更新，品牌始终为 DJHX Memo。
- 系统应用外观原值为深色。跟随系统时实际将 Windows `AppsUseLightTheme` 切为浅色再切回深色，界面及标题栏一致变化；显式浅色覆盖系统深色，显式深色覆盖系统浅色。测试后恢复原系统值，没有调整操作系统语言或时区。
- 原生窗口缩为 800×650 时，英文深色与中文浅色/深色的偏好页和关闭 SVG 完整，焦点可见。点击关闭仅收起设置，Esc 同样有效；原生窗口关闭保存后隐藏，进程保持，随后可从真实托盘恢复/退出。中英文托盘的“仅本机”同步项均禁用。
- English、Dark、UTC 经过真实托盘退出、覆盖安装和重新打开后保留；随后选择中文、跟随系统、上海，最后单复数修正版覆盖后仍保留。最终包切回系统语言后，原生 UI Automation 找到实际的 `1 note` 标签。收尾将应用偏好恢复为系统语言/系统外观/上海。

本轮使用临时 .NET UI Automation 助手定位实际控件，并通过 QEMU 物理鼠标与截图确认真实通知区菜单；没有把桌面 `DJHX Memo` 的 ListItem 误当托盘图标。英文与中文托盘均实际点击退出并确认主进程为 0，没有强制终止应用。截图和结构化检查保留于忽略目录 `.local/presentation-20261107/windows-*.png`、`windows-artifacts/`。VM 保持运行，测试助手在收尾移除，构建工具链与用户数据保留。

上述记录覆盖常规 `/S` 升级路径；`/UPDATE`、`/NS` 的快捷方式保护经过独立代码复审，不宣称全部参数组合均已安装实测。显示时区默认与偏好持久化在 GUI 验证，跨日格式化由本轮自动测试覆盖；完整桌面 IME、同步、Windows 11、缺失 WebView2 和此前尚待验收项保持原状态。

最后的英文数量修正包 `93e53718f1fb6a8cfd1021db7cae3df2a9ef7bcc2bfc0f310260877364baeddb` 再次在 Ubuntu VM 与 Debian 宿主覆盖安装成功；安装载荷与包内同为 `f86368249d0ac7404dab37b40a0840a25116774f34a341fa19db594c2d16601a`。在新的隔离英文空间中通过实际键盘输入一条测试笔记，自动保存完成，截图目视确认列表为 **1 note**（而非固定复数），界面保持系统深色；证据 `.local/presentation-20261107/ubuntu-final-one-note.png`。此增量另经 25 项前端测试；未重复已记录的全套主题矩阵。最终文件 7,002,846 字节，归档与子目录校验文件已更新。
