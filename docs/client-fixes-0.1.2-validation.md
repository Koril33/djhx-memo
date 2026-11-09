# 客户端 0.1.2 控件与滚动修正验证

日期：2026-11-08。依据[产品规范 §11.1](../spec/product-v1.md#111-展示缺陷修正验收)，修正 0.1.1 用户反馈的桌面下拉文字截断及 Android 编辑滚动。运行行为只变更客户端展示/手势适配，core、server、同步、鉴权、迁移及安装/签名身份保持不变。配套 MIT 授权、公开环境文档与安全审计另见[开源准备检查](open-source-readiness.md)。

## 桌面复现、原因与最小修正

在本机 Debian 13 的 WebKitGTK 2.54.0、GTK Adwaita 深色环境，以仓库原始 style.css 和原生 HTML select 复现用户截图：Language、Appearance、Display time zone 三个当前值的下半部被截断。相同控件将 CSS 垂直内边距清零后，原生文字完整居中；现有水平内边距、40px 高度、原生箭头与键盘行为保留。该复现使用实际宿主 WebKitGTK，并非 Chromium 或图片模型。

根因是共享 input/select 的 8px 垂直 padding 与 GTK 原生选择器内部标签 inset 在固定高度下冲突。本次只为 `.field select` 增加 `padding-block: 0`，不改普通输入框、不替换原生选择器、不新增依赖。规则同时覆盖偏好三项和数据复制来源选择器。修前/修后控件截图：本机 `.local/client-fixes-012/select-before.png`、`select-after.png`。

## 桌面自动检查与 Linux 构建

- 前端 format、lint、Svelte 检查 0 错误/0 警告、26 项既有测试及 Vite 构建通过；版本元数据最终格式亦通过。没有为纯 CSS 修正新增镜像实现的单测，以原生控件实测验证裁切问题。
- Ubuntu24.04 构建容器中 `cargo fmt --check`、`cargo check --locked --offline` 及最终 Tauri deb 构建通过。未修改 Rust 功能，因此本轮没有重复 clippy 或 Rust 单元测试；旧检查按原日期保留。
- 最终 CSS 17.01KB，JS 683.42KB，仍有原有的大于500KB块提示。五份依赖/模板/图标许可齐全，未引入依赖。
- `dist/linux/djhx-memo-0.1.2-linux-amd64.deb`：7,004,306 字节，SHA256 `e207d69f205bc6042ddce45eab7b9b9ad95aa82f21e5b90afcd579d28ad729a2`；实际载荷 SHA256 `7c03e85e9c52697db0715e30a204e3809f23000a83a166377534cfc9e0cd475c`。包名仍为 djhx-memo，0.1.2/amd64。旧0.1.0/0.1.1包保留，各子目录校验通过。

目标系统安装和原生控件实测记录追加于下方，不将样式复现窗口当成完整客户端 GUI 验收。

## Debian 13 实际客户端修前/修后验证

使用已安装的 0.1.1 二进制，在独立 D-Bus/Xvfb X11 会话和独立 XDG 数据/配置/缓存目录中打开真实 Tauri 客户端，精确重现半字截断。随后通过 dpkg 安装最终0.1.2 deb，同一隔离配置打开真实客户端；不是只比较HTML样例。不会读取用户账号或笔记，也没有退出用户现有编辑会话。

- 英文深色、中文深色、中文浅色、英文浅色的三个偏好下拉当前值均完整可见；实际用键盘 Home/方向键/Tab 依次切换语言和主题。
- 实际展开原生时区选项，Esc 收起，再点击 Close settings 返回主界面。原生箭头、焦点框及下拉能力保留，没有用静态图片或文字伪装选择器。
- 安装后包版本为0.1.2，`/usr/bin/djhx-memo` SHA256 与上述载荷一致；实际用户进程未被安装/测试终止，需正常从托盘退出并重开以加载修正。
- 修前/修后原生截图位于 `.local/client-fixes-012/native-before-*.png`、`native-after-*.png`，包括中英/明暗与原生弹出菜单；最终归档到 `dist/previews/0.1.2/`。测试仅含空白隔离本机空间，无真实内容。

该实际原生会话为本机GTK/WebKitGTK和X11虚拟显示；不冒充完整GNOME Wayland/X11/输入法矩阵。独立会话启动门户服务等待时发生的自动化超时未计为控件测试通过；修前和修后均在窗口实际可操作后才完成截图与键盘验证。

## Windows 原生回归（2026-11-08）

使用现有 Windows 10 22H2 x64 虚拟机（10.0.19045.6466）、Node 22.23.2、Rust 1.99.1 与 MSVC 原生构建。修复后的 108 个 desktop/core/assets 文件逐项 SHA256 对齐，最终源码 ZIP 为 `e6e5dff0b369b2f10dd2b814dd2a66bfbe325d9d9e4ba6f0ba7cbd550ec4995a`。`npm ci --offline`、format、lint、Svelte check（0 错误/0 警告）、4 文件 26 项测试和 Tauri NSIS 构建均通过。首轮版本元数据 JSON 的 Prettier 问题已修正后重新完整构建，没有跳过检查；前端仍有已知大于 500KB 块体积提示。

实际从 0.1.1 安全退出状态 `/S` 覆盖安装，exit 0；沿用原安装目录、快捷方式和旧卸载项，DisplayVersion 为 0.1.2。原有 1 条笔记完整记录 SHA256 在安装前后及测试恢复后均为 `9a3488d6cdcd1216b497266ccab51457673cc5f7ddc2e54d927b1c32e680fb7e`。

最终 `dist/windows/djhx-memo-0.1.2-windows-x64-setup.exe` 为 6,490,441 字节，SHA256 `1b47c73660b5b44d9040cfe8fadfbc16f82b16e5c873de364be9d33e6b4d03fd`；实际安装 EXE 与 NSIS 解包载荷均为 `18f0009b7768cb480b42d28acfdf07c14b3016fe21961bcf5e1308a269aa8136`。五份许可齐全；`dist/windows/SHA256SUMS` 中三版安装包校验均通过。

本轮只回归偏好设置的语言、外观、时区三个原生 select。确认没有正在运行的 Memo 会话后，以带恢复 journal 的同盘原子改名保护原 Roaming/Local 两目录，在空白隔离 profile 中验证，没有输入或截图真实笔记。

| Windows 真实显示缩放 | `GetDpiForWindow` | 三个 select 的物理高度 | 中文/英文 × 浅色/深色 |
| --- | --- | --- | --- |
| 100% | 96 | 各 40px | 4 组合的键盘选择、值及启用状态通过 |
| 125% | 120 | 各 50px | 4 组合的键盘选择、值及启用状态通过 |
| 150% | 144 | 各 60px | 4 组合的键盘选择、值及启用状态通过 |

- 通过 Windows 显示设置切换真实 DPI；为容纳高 DPI 最小窗口临时使用 1920×1080，没有用页面缩放替代。12 个组合逐项用 Home/方向键/Enter 切换并核对语言、明暗与 UTC/上海；Tab 可从语言到外观、时区，Esc 正常关闭原生下拉。
- 三个原生下拉在三档 DPI 均打开实拍；补充中文 100%/150% 下拉，代表截图中汉字笔画、English/Shanghai 的字母下沿均完整，没有新增自绘选择器。相关截图为 `selects-{100,125,150}-{zh,en}-{light,dark}.png` 与 `selects-*-dropdown-*.png`，保存在本机忽略目录 `.local/client-fixes-20261108/windows-artifacts/`；同目录 JSON 保留实际 DPI、尺寸及值匹配记录。
- 结束后已恢复原 100% / 1024×768，核对显示设置与基线一致，系统主题仍为原值 `AppsUseLightTheme=0`。通过真实系统托盘正常退出，应用进程为 0；两个原目录均已恢复，`*.fixes-original-012` 备份路径均不存在，原笔记哈希再次一致。
- 本轮未重跑未变动的 Rust 单元测试、Windows 11、WebView2 缺失安装及完整三端验收；不把选择器回归等同于这些矩阵通过。

临时 Windows worker、.NET 辅助程序、空 profile、下载/构建工作目录已清除；宿主临时 HTTP 8765 已停止且确认无监听。保留虚拟机、工具链、共享构建缓存及真实用户数据。

### Windows MIT 许可收尾构建

用户选择 MIT 开源后，又以最终许可元数据重建一次同版本安装包。此轮源码 ZIP SHA256 为 `4d0ae9af1e567d1c8db83df4dafee99ee5875151712e44fcd2e48c68111d7ea9`，包含 root `LICENSE` 与 109 个来源文件。与上述 DPI 实测构建逐项比较 desktop/core 的 26 个运行时源码文件，哈希均未变化；本次只变更许可/包元数据，因此沿用前述 DPI 矩阵，没有重复声称另一轮完整 GUI 测试。

Windows 原生 format、lint、Svelte check、26 项前端测试与 NSIS 构建再次通过。实际 `/S` 覆盖 exit 0，启动读取原有 1 条笔记，设置显示 0.1.2，再经真实系统托盘正常退出；原笔记完整记录哈希仍与上文一致，两个原目录无改名备份，应用进程为 0，100% / 1024×768 及系统主题原值保持。

**同名最终归档以本段为准**：`dist/windows/djhx-memo-0.1.2-windows-x64-setup.exe` 为 6,491,600 字节，SHA256 `93db0b79296fa7190ae9a64c971d29d09e54b045697caa09e66cc0b8ddce432d`；已安装 EXE 与 NSIS 解包载荷均为 `1469dfc6b354b2b428bdfcf363d2df4f05fcdae8ba784205bb019548a8f798ff`。包内现在有 **六份**许可；新增 `licenses/djhx-memo.txt` 与仓库 `LICENSE` 的 SHA256 均为 `bcb590678be15f9bc837e63be8153da989c31d742d921103e5bca8b5e47d1c2f`。子目录 SHA256SUMS 三包校验均通过，原生 npm 实际版本另核为 10.9.8。

本次未再次隔离数据或切换显示设置；追加的 MIT 构建 worker、.NET 助手和下载工作目录同样已清除，临时 HTTP 8765 停止。脱敏构建/安装/启动/清理证据位于 `.local/client-fixes-20261108/mit-final/windows-artifacts/`。上文五份许可、旧大小/哈希记录保留为许可补入前的实际测试步骤，不是最终交付包。

## Android 滚动复现与最小修正

在原 API 36 x86_64 模拟器 `emulator-5554`，通过 core 创建独立 180 段中文/English/emoji 长文，注入触摸拖动并在抬指后采样。0.1.1 源码 EditText 的 13 次 scrollY 全为 `804`，没有惯性；正文 TextWatcher 变更次数为 0，排除了本次拖动由正文更新/语法 span 重排触发的推测。原生拖动抬指还将光标从 0 移到 104；本机 SDK 36 的 `Touch.java` 仅实现拖动，`ArrowKeyMovementMethod.java` 在滚动结束后调用 `moveCursorToVisibleOffset`，与实测相符。外层没有第二个 Compose 纵向滚动容器。

0.1.2 只在 SourceEditor 中使用有限高度的原生 ScrollView 承载原来的同一 EditText，正文按内容高度布局，拖动、惯性、边界和点击/长按仲裁由平台负责；没有新增依赖或自写同步、编辑、速度衰减规则。隐藏预览/只读状态时同时隐藏、禁用父子并退出无障碍树；停止旧惯性，保持等值正文不重设及原生撤销。后续实机截图发现 AndroidView 默认不裁切互操作内容，长文会越绘到工具栏；已在有限 ScrollView 设置 BOUNDS outline 与 clipToOutline，并明确子级裁切。修后真实 IME 截图已独立复核，顶部系统栏和工具栏干净，正文、原生选区手柄及浮动工具条在正文区域可用。

平台行为参考 [Android 原生滚动说明](https://developer.android.com/develop/ui/views/touch-and-input/gestures/scroll)、[View.setClipToOutline](https://developer.android.com/reference/android/view/View#setClipToOutline(boolean))。Compose 1.7.5 的官方源码 KDoc 同样说明 AndroidView 不自动按布局边界裁切，建议在原生 View 侧设置 outline 裁切；本轮未升级 Compose。

## Android 最终自动与设备检查

本机工具链为 JDK 21、Rust 1.99.1、cargo-ndk 4.1.2、Android SDK 36、Build Tools 36.1.0；复用已验证的双 ABI JNI，没有因本轮 UI 修正重编 core。设备为 Android 16/API 36、x86_64、1080×2400、420dpi、KVM/SwiftShader。

| 检查 | 实际结果 |
| --- | --- |
| `bash scripts/validate-local.sh android` | 最终通过，22 秒；detekt、lintDebug、JVM 与 debug/release 构建检查完成，已缓存的相同源任务保持 UP-TO-DATE |
| JVM | 12 项通过，原有测试全部保留；未为手势物理行为添加模拟实现的 JVM 单测 |
| Android Lint | 0 错误、48 警告，仍为依赖更新、保留资源与已有平台使用提示；没有为隐藏警告删除资源或升级依赖 |
| 全部设备测试 | 原有 15 项保留，新增 SourceScrollTest 1 项；逐项状态解析为 16 项：15 通过、1 服务端联调跳过、0 失败，45.437 秒 |
| 真实键盘定向回归 | `expectIme=true` 的新增滚动回归通过，断言系统 IME 可见且长文深处光标在键盘上方可见；系统硬件键盘显示偏好最终还原 |
| 签名/对齐/许可 | release 与 debug 的 apksigner 验证及 `zipalign -c -P 16 -v 4` 均成功；两个 JNI ABI 和四份随包许可/通知存在，APK 内项目 MIT 文本与根 LICENSE 字节一致 |

完整套件通过 `adb install -r` 安装 debug 与测试 APK，使用 `adb shell am instrument -w -r` 运行，避免 Gradle connected 测试结束时卸载应用导致原有 debug 演示笔记被清除。结果按每项 instrumentation 状态统计；ServerIntegrationTest 未配置本轮隔离服务器/邀请码，状态为跳过，JUnit 尾部 `OK (16 tests)` 不代表 16 项业务通过。没有卸载或清除 release/debug 数据。

最终完整套件实测抬指后的原生滚动事件为 `776 → 810 → 870 → … → 1085`，共 26 个不同位置；正文变更次数 0，光标 0/0、组合区间 0..4 均保持。早期 40ms 轮询会漏掉实际动画帧，因此改用原生 onScrollChanged 事件验证至少三次连续位移，没有放宽持续滚动的要求。测试还验证上下边界停于 0/51765、稳定保存后的 generation 不变、深处点击定位到 offset 2923、真实长按产生 2865..2867 选区、源码/预览往返同一 EditText 及 native undo 恢复原文。

裁切回归直接比较截图像素：在正文顶部保存工具栏 ROI，滚入长文深处、显示 IME 并长按选区后，相同工具栏 ROI 逐像素不变；ROI 排除系统时钟及正文内的原生浮动工具条。不能用 EditText 的 globalVisibleRect 单独推断 RenderNode 的 outline 裁切，实机截图为最终依据。预览未改，回归使用实际可见的预览滚动节点边界；先前 Compose 没有发出可量化的 scrollY 事件，因此未将其全零事件记录当作正常预览物理轨迹或与源码的定量比较。

新 fixture 经 core 创建并移入回收站清理，原有四条演示笔记的完整记录在测试前后相等，最终日志 `EXISTING_DEMO_RECORDS_UNCHANGED=4`。没有物理清除真实演示内容。

## Android 安装包与真实升级

版本为 0.1.2 / code 3，发布包仍为 `app.djhx.memo`，debug 仍为 `app.djhx.memo.debug`；应用/Keystore 身份及发布证书不变。新产物单独归档，原 0.1.0/0.1.1 包和校验条目保留：

| 文件 | 字节 | SHA256 |
| --- | ---: | --- |
| `dist/android/djhx-memo-0.1.2-android.apk` | 32,905,134 | `ee4e2fd535d4f67ea3de9a07683193b5f438dd32f961368d8dac2281c8662a7c` |
| `dist/android/debug/djhx-memo-0.1.2-android-debug.apk` | 38,126,626 | `46ec9f2087b82e826e138e4cc1231c015c1489da585d803e05cddd75379575d7` |

发布证书 SHA256 为 `ec8a88aa7aad47d51fa07ffddb4a17bb69c4aad71ee3c0dd4b3ad0eb4f5cdcdb`，v2 签名验证成功。真实 release 从 0.1.1/code2 以 `adb install -r` 覆盖到 0.1.2/code3，安装成功、Activity 正常启动；`firstInstallTime=2026-11-04 21:32:32`、`dataDir=/data/user/0/app.djhx.memo` 保持不变，最终更新时间为 2026-11-08 21:55:06。拉取设备已安装 base.apk 后，SHA256 与上述最终归档 APK 完全一致。未注入或读取真实 release 正文来拍摄演示，不能据此声称逐条审计真实账号内容或完整云同步升级矩阵。

## Android 开源构建、注释与隐私边界检查

Android README 已提供不依赖本机私有 env.sh 的 Linux 从零入口，并区分 Windows PowerShell 的 `gradlew.bat` 阶段与 WSL2/Linux 完整 JNI 流程。当前 Bash JNI 脚本硬编码 Linux 主机 `.so`；正式 app 的 Windows 原生 UniFFI/JNI 完整构建没有经过本轮验证，不能用此前原生探针代替。Gradle/插件/依赖锁与 NDK 版本可追溯，但仓库未固定 Rust 工具链文件，本轮也未在新机器上从空缓存重建 JNI，因此没有宣称 APK 字节级可复现。

本轮 Android 生产修改限于原生滚动适配、裁切及版本号/项目许可资源。新增注释说明系统组件职责、互操作裁切和持久编辑器边界，没有复制平台算法或引入框架。检查生产 Kotlin、Manifest、脚本和构建配置，没有发现本轮新增正文/密码/token 日志或嵌入私密值；默认 `https://memo.djhx.site` 是产品指定的公开默认地址，用户仍可改填。release 禁止明文流量，debug 的 HTTP 仅用于隔离测试；没有新增权限或遥测 SDK。发布签名只通过环境变量读取私有密钥，公开源码无需、也不包含发行者密钥；自签包不能覆盖不同签名的已安装 release，这些限制已写入 README。

证据：本机 `.local/android-scroll-baseline-device2.log`、`.local/android-scroll-pixels-ime-device.log`、`.local/android-0.1.2-final-check.log`、`.local/android-0.1.2-full-device.log` 及同名 `.json`；签名、对齐、APK 元信息、安装前后记录与设备安装包哈希在 `.local/android-0.1.2/`。最终真实 IME/选区截图为该目录 `source-longpress-ime.png`，仅包含专用长文 fixture。系统设置恢复记录 `device-settings-restored.txt` 确认 1080×2400、420dpi 无 override、font_scale=1.0、show_ime_with_hard_keyboard=0，通知面板已收起。

没有物理 arm64/API29 设备，本轮也不是连续帧率/低端机长文性能基准。真实键盘可见性和原生 composing span 回归不等同于全部中文输入法候选词矩阵；云同步全矩阵、厂商后台、省电、Windows 原生 Android JNI、全新机器从零构建及连续 7 天试用仍未据此通过。0.1.1 记录中的极端大字号表单视觉限制未在本轮扩大修整。

## Linux MIT 许可收尾构建

补入项目 MIT 许可后重建同版本 deb，格式与 Ubuntu24 构建容器中的 Cargo check、Vite/Tauri 打包再次通过。**最终同名归档**为 `dist/linux/djhx-memo-0.1.2-linux-amd64.deb`，7,004,436 字节，SHA256 `d3736e1ded5427ed7bea816e52fb3ca62ddc981d0d0c62ec677598e69c4cc0bb`。六份随包许可齐全，新增项目许可与根 LICENSE 字节相同；二进制载荷仍为 `7c03e85e9c52697db0715e30a204e3809f23000a83a166377534cfc9e0cd475c`，与前述原生 GUI 实测载荷一致。子目录历史三版包的 SHA256SUMS 检查通过。

已通过 dpkg 在本机同版本覆盖安装最终 MIT deb，核对 `/usr/bin/djhx-memo` 载荷和已安装 `licenses/djhx-memo.txt` 与归档/根许可一致，未终止原用户编辑进程。上文五份许可、旧包大小/哈希是补入许可前的过程记录；最终包以本节为准。没有因许可资源重打包而重复宣称另一轮 GUI 矩阵；本轮 core/server/真实联调的额外质量检查另见开源准备记录，不能与未重跑的桌面 Rust 测试混淆。
