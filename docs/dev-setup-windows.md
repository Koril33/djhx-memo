# Windows 原生开发

本文面向 Windows x64 开发主机，介绍原生桌面、core 和 Android 构建。桌面已在 Windows 10 22H2 x64 虚拟机完成原生构建、安装及部分交互验收。设备覆盖范围见[桌面验证](desktop-validation.md)、[界面预览验证](desktop-ui-preview-validation.md)与[验收清单](validation.md)。

以下 PowerShell 命令从仓库根目录执行，路径可自行选择，不需要维护者的 `.local/` 文件。已有工具可复用；只开发桌面时无需 JDK、Android Studio、SDK/NDK、Go 或 MinGW。

## 1. 安装桌面工具

1. 安装 Git for Windows，用 Git 克隆仓库。
2. 安装 [Microsoft C++ Build Tools](https://visualstudio.microsoft.com/visual-cpp-build-tools/)，选择 **Desktop development with C++（使用 C++ 的桌面开发）**、MSVC x64/x86 编译工具和一个匹配的 Windows SDK。已有 Visual Studio 同工作负载可复用，无需再装一套 IDE。
3. 安装 [WebView2 Evergreen Runtime](https://developer.microsoft.com/en-us/microsoft-edge/webview2/)。已有运行时可复用；Edge 浏览器和可用的 WebView2 Runtime 应分别核实。项目安装器携带引导程序，缺失运行时时仍需要网络下载。
4. 从 [Rust 官方入口](https://rust-lang.org/tools/install/)安装 rustup，选择 `x86_64-pc-windows-msvc`。不要用 GNU 工具链替代此桌面构建目标。
5. 从 [Node 官方下载](https://nodejs.org/en/download)安装兼容的 Node 22.x（至少 22.12）及 npm；已实测 22.23.2 / 10.9.8，历史版本可在[官方归档](https://nodejs.org/dist/v22.23.2/)获取。不要求把系统升级到下载页最新大版本。

原生工具要求以 [Tauri 前置依赖](https://v2.tauri.app/start/prerequisites/)为依据。项目当前输出 NSIS setup.exe，不要求启用仅 MSI 构建需要的 Windows 功能；Rust/Node 的项目约束与锁文件见[公共入口](dev-setup.md)。

文档/契约/安全检查另需安装 [Python 3.11+](https://www.python.org/downloads/windows/)，确认 `python --version`；仅原生桌面编译本身不依赖 Python。品牌资源再生成的工具与锁版本见 [ADR-0010](adr/0010-brand-assets.md)。

重开终端使安装器添加的 PATH 生效。建议从 Visual Studio 的 **x64 Native Tools Command Prompt** 启动 `powershell.exe -NoProfile`，继承正确的 x64 MSVC/SDK 环境；也可使用已配置 x64 的 Developer PowerShell。[微软说明](https://learn.microsoft.com/en-us/cpp/build/building-on-the-command-line?view=msvc-170)描述了这些开发终端，避免手工拼接编译器目录。

```powershell
git --version
Get-Command cl.exe
node --version
npm.cmd --version
rustup --version

# 在本终端选择与既有验证相同的 Rust；不修改其他项目的全局默认值。
rustup toolchain install 1.99.1-x86_64-pc-windows-msvc --profile minimal --component rustfmt --component clippy
$env:RUSTUP_TOOLCHAIN = '1.99.1-x86_64-pc-windows-msvc'
rustc -vV
cargo --version
```

`rustc -vV` 的 host 应为 `x86_64-pc-windows-msvc`。`cl.exe` 不存在时先修复 C++ 工作负载/开发终端，不能以安装 MinGW 代替。Windows PowerShell 5.1 不会因本机程序退出非零而自动中止后续行；每条命令检查 `$LASTEXITCODE`，非零时先解决错误再继续。

## 2. 首次检查与运行桌面

使用 `npm.cmd` 可直接调用 npm，无需为 `npm.ps1` 调整 PowerShell 执行策略：

```powershell
Push-Location desktop
npm.cmd ci
npm.cmd run format:check
npm.cmd run lint
npm.cmd run check
npm.cmd test
npm.cmd run build
cargo fmt --manifest-path src-tauri/Cargo.toml --check
cargo check --locked --manifest-path src-tauri/Cargo.toml
cargo clippy --locked --manifest-path src-tauri/Cargo.toml -- -D warnings
cargo test --locked --release --manifest-path src-tauri/Cargo.toml --bin djhx-memo
npm.cmd run tauri dev
Pop-Location
```

`tauri dev` 启动本地前端和原生应用，退出开发进程后再继续。`npm run build` 只构建前端；需要显示真实窗口、托盘和系统文件对话框时运行 Tauri。第一次 Rust 构建会编译随包 SQLite、TLS 和桌面依赖，耗时与机器性能及网络有关。

从仓库根目录单独验证 core：

```powershell
cargo fmt --manifest-path core/Cargo.toml --check
cargo clippy --locked --manifest-path core/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path core/Cargo.toml
```

其中标为 ignored 的真实服务端联调需另行显式执行；普通测试通过不表示联调完成。`scripts/validate-local.sh` 是 Linux/Bash 检查入口，Windows 原生检查采用上面的命令。

## 3. 构建 Windows 安装器

```powershell
Push-Location desktop
npm.cmd run tauri build -- --bundles nsis
if ($LASTEXITCODE -ne 0) { throw 'Windows installer build failed' }
Pop-Location
Get-ChildItem desktop/src-tauri/target/release/bundle/nsis/*-setup.exe
Get-FileHash desktop/src-tauri/target/release/bundle/nsis/*-setup.exe -Algorithm SHA256
```

默认输出位于 `desktop/src-tauri/target/release/bundle/nsis/`；若显式设置 `CARGO_TARGET_DIR`，改在对应 target 下查找。使用锁定的本地 Tauri CLI 和仓库自定义 NSIS 模板；不要通过全局安装换成未验证的 CLI，也不要修改模板里的旧安装身份来“清理品牌”，该身份用于兼容已有候选版升级。安装器细节见 [desktop README](../desktop/README.md)和 [Tauri Windows 打包说明](https://v2.tauri.app/distribute/windows-installer/)。

开发者自行生成的包没有自动获得代码签名；是否签名及 Windows 安装提示需按实际产物说明。安装、覆盖升级、退出重开、托盘和输入法仍需在目标 Windows 上检查。WSL 构建 Linux 二进制不能替代此原生 Windows 流程；Windows 构建也不替代 Linux GUI 验收。

## 4. 可选：Go 服务端开发

纯桌面开发可跳过。**当前服务端依赖 Unix 文件锁，原生 Windows 不是支持的构建路径。** `server/epoch.go` 直接调用 `syscall.Flock`，在原生 Windows 编译会失败；不要在 PowerShell 中执行服务端的 `go build`、`go vet` 或 `go test` 并把失败归因于缺少 Go 工具链。

需要开发服务端时，使用独立 Linux 主机或 WSL2 中的 Linux 发行版。WSL 安装见[微软官方说明](https://learn.microsoft.com/en-us/windows/wsl/install)；进入发行版后，按[文件系统建议](https://learn.microsoft.com/en-us/windows/wsl/filesystems)在其 Linux 文件系统内（例如 `~/src/djhx-memo`）单独克隆仓库，不使用 `/mnt/c/` 下的 Windows 工作树与构建缓存。按 [Linux 公共入口](dev-setup-linux-portable.md)安装该环境自己的 Git、Go 和 C 编译器，再按[服务端 README](../server/README.md)运行开发检查。

WSL 与 Windows 原生终端的工具链、PATH 和 target 缓存相互独立；不要在 WSL 里借用 `go.exe` 或 MSVC。Windows 原生 Go 的 race 编译器要求也不能解决上述 Unix API 限制。当前没有完成 WSL 服务端的完整验收；部署仍按[运维说明](operations.md)在 Linux 上执行并核实 systemd/Nginx、备份和恢复，不从桌面客户端构建成功推导服务端可用。

## 5. 可选：Android 工具与边界

Android 客户端使用 Kotlin/Compose 和 UniFFI。用支持 AGP 8.13 的 Android Studio 打开仓库下的 `android/` 工程目录，Gradle JDK 选择 JDK 21。IDE 可使用自己的随附 JBR。参见 [Android Studio 兼容表](https://developer.android.com/build/releases/about-agp#android_gradle_plugin_and_android_studio_compatibility)和 [Gradle JDK 说明](https://developer.android.com/build/jdks)。

按当前 [Android 工程](../android/app/build.gradle.kts)准备 SDK Platform 36、Build Tools 36.1.0、Platform Tools、NDK 30.0.16248370 和 SDK CMake。构建脚本使用 NDK API 29 链接器，APK 编译目标为 API 36。

从仓库根目录执行。SDK、JDK 在非默认路径时，可传入 `-AndroidSdk` 和 `-JavaHome`，或按示例创建 Git 忽略的 `.local/dev-env.json`。脚本调用会话辅助来配置 Clang、SDK 和 JDK 路径。

```powershell
rustup target add aarch64-linux-android x86_64-linux-android
& .\android\scripts\build.ps1
```

入口先从 Rust 源码生成 Kotlin 绑定与两个 ABI 的 JNI 库，再用 `gradlew.bat` 完成检查及打包。IDE 单独执行 Gradle 任务前，可先用 `-NativeOnly` 生成文件。debug APK 位于 `android/app/build/outputs/apk/debug/`。Gradle 任务与发布签名选项见 [Android README](../android/README.md)。

手工设置 `android/local.properties` 时，Windows 盘符分隔符需要转义，例如 `sdk.dir=C\:/Android/Sdk`。该文件和 Android Studio 的本机 Gradle JDK 设置留在 Git 忽略目录。

2026-11-09 在 Windows 10 22H2 x64 上以 `91bd1b8` 的业务源码验证此入口。Rust 1.99.1、JDK 21、NDK r30 生成了 Kotlin 绑定和双 ABI JNI，`testDebugUnitTest detekt lintDebug assembleDebug assembleDebugAndroidTest` 通过。12 项 JVM 测试通过，Lint 为 0 错误和 47 条提示。调试包通过 APK v2 签名、ZIP 16 KiB 对齐及两套 core ELF 检查。API 35 x86_64 模拟器的 `CoreBindingTest` 3 项通过，覆盖 JNI、SQLite 和 Keystore。arm64 完成编译与打包，运行验收仍待真机。IDE 并行安装了 Rabbit 2 Patch 1，构建验证通过 Gradle 命令行完成。

## 6. 可选网络设置与排错

仅当本机确实有 HTTP 代理时，为当前 PowerShell 及子进程设置，例如：

```powershell
$env:HTTP_PROXY = 'http://127.0.0.1:7890'
$env:HTTPS_PROXY = $env:HTTP_PROXY
```

不需要时执行 `Remove-Item Env:HTTP_PROXY, Env:HTTPS_PROXY -ErrorAction SilentlyContinue` 撤销示例变量。端口只是例子。Gradle 还需 Java 代理参数；Android 会话辅助可显式用 `-ProxyUrl 'http://127.0.0.1:7890'`，或按 [Gradle 说明](https://docs.gradle.org/current/userguide/networking.html)配置个人 Gradle 配置，不提交代理或签名凭据。官方工具下载失败时先检查网络、代理和证书链，不关闭 TLS 校验。首次 NSIS 构建可能需要下载其工具资源，`--offline` 只适用于已完整缓存的 Cargo 依赖。
