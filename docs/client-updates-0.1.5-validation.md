# 0.1.5 Windows / Android 更新器验证

- 日期：2026-11-12；主机：Windows；分支 `codex/windows-android-updater`，基于调研提交 `7bf581b`。
- 范围：客户端 0.1.5（Android versionCode 6），core/server 仍为 0.1.4 / 协议 2 / local schema 3。无 Linux 构建测试，无 CI/Runner，无服务器部署或远程 Release/tag。
- 私钥在仓库外由维护者授权生成，提交仅公钥。签名工具与公开配置见[发布说明](update-publishing.md)。

## 实际结果

| 检查 | 结果与边界 |
|---|---|
| core | rustfmt、Clippy all-targets `-D warnings`；37 项单元测试通过，其中 10 项更新相关测试验证真实 Tauri 签名格式、版本绑定、目录时效/篡改/重复字段、序号回放、限量流式下载、取消/磁盘故障、损坏状态及安装屏障 |
| Rust/Go | 显式文件集 `types.go store.go auth.go http.go export.go purge.go purge_test.go` 的 `TestPermanentDeletionCoreHTTP` 在 Windows `-race` 下通过，真实 Rust 客户端对隔离 Go 处理器；常规 cargo 中 9 项 ignored 联调不据此全部算通过 |
| Windows 前端 | Svelte/TS、ESLint、28 项 Vitest 通过；保留既有 IME/输入屏障和保存回执测试；Vite 构建保持原有大 chunk 提示 |
| Windows 原生 | Cargo 检查/Clippy/原生导出测试通过；真实 NSIS `.exe/.sig` 生成成功；显式 `signed_nsis` 测试通过 Tauri restore/verify、版本 trusted comment、PE 文件版本与篡改/错误版本拒绝；测试没有执行该安装器 |
| Windows WebView2 | 独立 identifier `app.djhx.memo.validation015`，初次 CDP 检查更新控件/偏好持久化/未登录手动检查/检查失败时笔记保持可读写通过；当时控件位于数据设置页。后移至独立软件更新页，最终界面复测被自动批准审查阻止，不能将初次结果当作最终布局已验收。线上静态目录尚未部署，返回 `UPDATE_NETWORK`，没有伪报已是最新 |
| Android 构建 | JBR、NDK r30、Windows 原生脚本生成 host DLL / arm64-v8a+x86_64 JNI / UniFFI；12 项 JVM 测试、detekt、Lint（0 errors / 53 warnings）、debug/测试 APK、unsigned release 构建通过 |
| Android 原生回归 | API 35 x86_64 模拟器，隔离 validation014 包：最终 runner 报告 `OK (10 tests)`，其中升级后专用案例因缺少 `afterUpgrade=1` 条件跳过；实际执行 4 项更新预检/安装屏障/安装取消 + 既有 CoreBinding 3 项和 EditorLifecycle 2 项。包含中文/emoji、Keystore、SQLite、组合 span、持续输入与旋转；取消系统会话后等待真实 core 恢复并读取已保存笔记 |
| Android 系统安装 | 独立较新 debug APK versionCode 7（0.1.6-debug）；同包名/同签名/双 ABI 预检通过；系统明确显示更新确认，点击确认完成 6→7；升级后经真实 Rust 核心读到升级前已提交的中文/emoji 笔记。另两次独立 instrumentation 各 1 项通过，不使用生产 keystore |
| 目录发布工具 | 本机生成真实签名目录，使用共享 Rust 验证器校验；错误签名钥失败且不推广 current.json；输出仅在忽略目录，没有上传或部署 |

Rust 为 1.99.1 MSVC，Node 22.22.1，Go 1.27.3。新 crate 从官方源下载并核对 registry checksum，网络代理仅用于本机命令；没有修改公共网络配置或关闭 TLS 校验。Go race 需当前进程 PATH 包含 MSYS2 UCRT64 的 bin，避免 cgo 编译器缺少运行库；Rust 子测试不继承 Go 通用 CC。

首次 Tauri 验签互通测试发现 builder 即使使用 restore_update 仍要求非空 endpoints；已修正正式配置并用实际安装器验证，不靠跳过签名测试获得通过。初次原生 UI 脚本误用了简化按钮名，改为实际本地化可访问名称后验证通过。

## 最终交付检查

最终 Svelte 检查为 0 errors / 0 warnings，ESLint、Prettier、Markdown 本地链接/OpenAPI 检查、`git diff --check` 通过。PowerShell 两份签名助手通过语法解析；初始化助手不会重新生成已有信任根。Gitleaks 对 374 份公开源码、可达历史和提交元信息扫描均为 0 findings；该结果不等于绝对无漏洞。

两种 Android ABI 和 UniFFI 由本轮 core 重编。最终 debug 和 unsigned release 均通过 build-tools 的 `zipalign -c -P 16 4`；未加载维护者原发布证书，unsigned release 不可直接发布。最终 Windows NSIS 使用仓库外包钥生成 `.sig`，再次通过显式 `signed_nsis` 测试；目录工具以序号 3 验证这份最终资产并在忽略目录生成签名信封，没有推广到线上。

可复用检查命令（仓库根目录，先按开发文档进入本机工具链）：

```powershell
cargo test --locked --offline --manifest-path core/Cargo.toml
cargo clippy --locked --offline --manifest-path core/Cargo.toml --all-targets -- -D warnings
npm.cmd --prefix desktop run check
npm.cmd --prefix desktop run lint
npm.cmd --prefix desktop run test
cargo test --locked --offline --manifest-path desktop/src-tauri/Cargo.toml
# 额外设置 DJHX_TEST_INSTALLER 为已签名 NSIS 的绝对路径后：
cargo test --locked --offline --manifest-path desktop/src-tauri/Cargo.toml signed_nsis -- --ignored --nocapture
python scripts/check-docs.py
python scripts/check-secrets.py --gitleaks .local/release-audit/go-bin/gitleaks.exe
```

Gitleaks 路径是本机工具位置，其他主机按 SECURITY 自行安装并传入。Android 仪器入口为 `UpdatePlatformTest,CoreBindingTest,EditorLifecycleTest`，使用 `updateApk=/data/local/tmp/djhx-update015.apk`；升级后专用案例已在此前实际 6→7 系统安装后以 `afterUpgrade=1` 单独通过。较新测试 APK 只在隔离应用中使用，不覆盖生产应用。

下表仅标识本机候选产物，没有上传 Release。相对路径均从仓库根目录解析，实际文件被 Git 忽略；重建可能改变哈希：

| 产物 | 字节数 | SHA-256 |
|---|---:|---|
| `desktop/src-tauri/target/release/bundle/nsis/DJHX Memo_0.1.5_x64-setup.exe` | 7825851 | `8384f3fca76038723e961fb9ab791e6916e03ee6612d246c9cd52b9e828425a4` |
| `android/app/build/outputs/apk/debug/app-debug.apk`（隔离测试包） | 42365918 | `2a202a13370b8b0ca4303a278176ba3f818fd15292e6a8cf8c93695974e99ba2` |
| `android/app/build/outputs/apk/release/app-release-unsigned.apk` | 36991082 | `7af969edd5ddd10a195ba551a8a026985c81c49e52b5ad440f7998acf8660787` |

最终再次启动带回环调试端口的 Windows 隔离实例时，自动批准审查返回 `blocked by policy`，没有提供更多原因。未重试该动作或通过其他路径绕过；因此新增 `desktop/scripts/update-smoke.mjs` 未完成最终原生运行，独立更新页及最后的时间显示调整仅完成静态检查和构建。

## 未验证与交接

- 正式目录的部署、GitHub Release 推广和生产 U1→U2 线上下载链路尚未进行；旧安装需要人工覆盖 U1 一次。
- Windows 没有覆盖维护者当前安装，尚未进行真实旧“简记”注册身份升级、安装器失败/杀毒拦截/文件占用矩阵；NSIS 模板保持既有兼容逻辑。
- 本机没有加载原 Android 发布证书；真实升级使用隔离 debug 证书，不能替代生产签名连续性或 arm64 真机验证。正式签名 APK 与这部分验收由持钥者完成。
- Linux AppImage/DEB 更新安装不在本轮实现/测试/打包；未执行完整三端矩阵和 7 天试用。
- 没有独立交叉评审。安装屏障影响 core 并发入口，需要按 CONTRIBUTING 在合并前独立复核；本轮自检不是独立评审。
- 未宣称完全防 TUF 冻结、私钥泄露自动修复、跨 schema 自动回滚，或所有 Android 设备静默安装。

所有测试均使用合成内容、独立标识/空间、临时 HTTP 服务与测试 APK；没有访问维护者生产账号/笔记或卸载正式客户端。临时 Windows 进程、模拟器和调试端口在验证后关闭，私钥保留在受控仓库外路径供维护者备份。
