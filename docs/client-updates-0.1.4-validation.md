# 0.1.4 增量开发与验证

日期：2026-11-11。状态：本轮 Windows/Android 自动检查与隔离功能验证完成，未发布；原生托盘鼠标验收及独立评审仍待完成。产品规则见[增量契约](../spec/permanent-deletion.md)，架构取舍见[ADR-0015](adr/0015-permanent-deletion.md)。

## 变更与兼容

Windows 托盘右键菜单、左键恢复/保存后隐藏；桌面列表折叠及本机偏好；Windows/Android 回收站单条彻底删除，离线待同步、并发拒绝、不可逆永久标记与未提交文字救援。无新增依赖。

core 迁移到本地 schema 3，服务端迁移到 schema 2；既有迁移保持原样，升级后旧程序拒绝高版本库。协议升为 2，HTTP 路径仍为 /v1，客户端与服务端必须配套更新。部署前按[运维说明](operations.md)备份服务端数据库及 epoch；程序回滚不能直接打开新 schema，需要保全新数据后恢复升级前备份。本轮不连接或改动生产账号/服务。

## 本机范围

Windows 原生 MSVC/WebView2；Android SDK/NDK、JDK、API 35 x86_64 模拟器。工具路径和代理只留在忽略的 `.local/` 与当前进程，不作为其他主机前提。

独立 Windows UI 验证使用不同 identifier/productName 的本机配置，数据与用户正在运行的正式客户端隔离。Android 使用 debug 包和合成 fixture，不卸载或清除现有应用数据。

## 实际结果

| 检查 | 实际结果 |
|---|---|
| core | `cargo fmt --check`、`cargo clippy --locked --all-targets -- -D warnings`；25 项单元测试通过，含持久化删除请求、迁移、撤销旧待发与普通删除冲突不迁移彻底删除 |
| 桌面前端 | Prettier、ESLint、Svelte/TS 检查通过；28 项 Vitest 通过；Vite 构建通过，保留原有大 chunk 提示 |
| Windows Rust | `cargo check --locked`、Clippy `-D warnings`、release 原生单元测试 1 项通过；NSIS 构建通过 |
| Windows WebView2 | 独立标识 debug 实例，实际 Svelte/CodeMirror → Tauri → SQLite；折叠后工作区 868→1160px；编辑器实例、撤销、三种模式、折叠偏好重载、800px 最小视口无横向溢出、回收站取消/确认/单条彻底删除通过。脚本为 `desktop/scripts/native-smoke.mjs` |
| Go 业务检查 | 显式文件集 `types.go store.go auth.go http.go export.go purge.go purge_test.go` 的 go vet、go test -race 通过；覆盖授权隔离、epoch、吊销、协议门槛、版本冲突、幂等、回滚、旧库升级和导出排除 |
| Rust/Go HTTP | 显式启用 `TestPermanentDeletionCoreHTTP`，真实 Rust 客户端对隔离 Go HTTP 处理器通过；离线申请重开、另一端恢复拒绝、未确认删除顺序及旧编辑不复活 |
| Android 构建 | Windows `android/scripts/build.ps1 -NativeOnly` 重新生成 host DLL、双 ABI JNI 与绑定；Gradle detekt（最终无 format）、testDebugUnitTest、lintDebug、assembleDebug、assembleDebugAndroidTest 通过；12 项 JVM 测试，Lint 0 错误/48 警告（依赖更新、未使用资源、既有样式等，未屏蔽） |
| Android 模拟器 | API 35 x86_64，独立 validation014 APK；单条删除 UI 取消/确认、原生 JNI 删除重开与防恢复、既有 CoreBindingTest/EditorLifecycleTest 共 7 项通过，覆盖 SQLite/Keystore、IME 组合 span、持续输入与旋转 |
| Android/Go HTTP | `TestPermanentDeletionAndroidHTTP` 另行通过，两个独立 JNI 客户端登录同一隔离账号，彻底删除与旧设备恢复请求最终收敛；不使用生产数据 |
| 文档与安全 | Markdown 链接及 OpenAPI 12 个操作检查通过；工作区/可达历史/提交元信息 Gitleaks 扫描无发现；此结果不代表绝对无漏洞 |

本机工具：Rust 1.99.1 MSVC、Node 22.22.1、Go 1.27.3、Android Studio JBR、NDK r30。Go race 使用当前进程的 MSYS2 GCC；Rust 子测试在 Windows 不继承 Go 的通用 CC 覆盖，避免混用 MinGW 与 MSVC。一次混用造成的链接失败在修正测试环境边界后重跑通过，没有修改业务断言。

Android UI 测试最初受弹窗虚拟无障碍节点查询时序影响，改为等待实际标题节点后重新定位控件；最终两项新测试及既有五项回归均通过。Windows native screenshot API 反复超时且缺少点击几何，改用隔离 WebView2 的 CDP 功能检查与截图；没有据此声称真实系统托盘鼠标已通过。

## 本机产物

- Windows `desktop/src-tauri/target/release/bundle/nsis/DJHX Memo_0.1.4_x64-setup.exe`：7,520,975 字节；SHA-256 `deb11e10abcbcff9321fc16038e24b2a5213f06b667c3f371d0bbe85737927b5`。未进行 Windows 代码签名或覆盖用户当前安装。
- Android `android/app/build/outputs/apk/debug/app-debug.apk`：隔离验证包，debug 签名，应用 ID `app.djhx.memo.validation014`；SHA-256 `1dd9aa2eb6d64e51ed7443cea39de95b75b10a5a785082b9d72129cf3b570093`，zipalign 16 KiB 检查通过。没有构建或发布正式签名 Android Release。
- 截图及配置仅在忽略的 `.local/version014/`；构建产物不入仓库，没有创建或修改远程 Release/tag，没有改动现有生产服务。

## 尚未验证与交接

- Windows 实际托盘右键菜单、左键隐藏/恢复、双击/快速点击、标题栏最小化与保存失败/IME 的组合仍需人工鼠标验收；既有保存屏障单元回归保持通过。
- 按维护者要求，本机不测试或打包 Linux 桌面。另一台机器执行 `bash scripts/validate-local.sh core|server|integration|desktop`（分别执行）及桌面 README 的 Linux 打包，检查 AppIndicator/Wayland/X11/IME。
- Windows 显式源文件的 Go HTTP 测试不包含 Unix epoch 文件锁/管理 CLI；完整服务端 `go test -race ./...`、lint、备份恢复与全部既有真实 Go 联调仍交另一台主机。
- 同步/迁移变更尚未经过独立交叉评审。本轮自检不等同于该评审；合并或部署前需要按 CONTRIBUTING 完成。
- Android arm64 真机、完整三端设备矩阵和连续 7 天试用不因本轮通过而完成。
