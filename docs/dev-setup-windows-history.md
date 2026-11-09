# 历史本机记录：Windows 环境探针

> 本文保留 2026-11-03 另一台 Windows 主机的环境阶段记录，包含当时尚未实现的工程和临时探针版本；这些状态不代表当前仓库。新贡献者请从[公共开发入口](dev-setup.md)和[Windows 原生桌面说明](dev-setup-windows.md)开始。不要照搬本文的个人路径、代理端口或探针依赖。当前业务检查以[验证索引](validation.md)为准。

本文件区分**可供所有开发主机参考的要求**与**此前 Windows 开发主机的实测记录**。本机路径、代理端口、SDK 位置和设备信息不属于项目配置，不得复制为所有主机的默认值。

当前 Linux 主机实际安装见 [dev-setup-linux](dev-setup-linux.md)。下文 Windows 记录来自另一台机器；本轮 QEMU/KVM VM 的新安装与客户端验收见 [桌面验证](desktop-validation.md)，不能混同两台机器。

## 1. 通用要求

| 范围 | 所需工具 | 跨主机约定 |
|---|---|---|
| Go | Go、gofmt、go vet；Windows race 测试需可用的 C 编译器 | 项目初始化后由 go.mod 定义版本，依赖由 go.sum 锁定 |
| Rust | Rust MSVC（Windows）、Cargo、rustfmt、clippy；Windows C++ 工具及 SDK | 初始化后以 rust-toolchain.toml/Cargo.lock 固定项目版本，本机 stable 版本不是所有项目的强制默认 |
| Android | 兼容的 JDK、Android Studio、SDK Platform/Build Tools/Platform Tools | SDK、JDK 路径按主机配置；Gradle 使用项目 Wrapper，不要求全局安装 |
| Rust → Android | NDK、aarch64-linux-android 与 x86_64-linux-android 标准库 | arm64 用于真机；x86_64 用于模拟器；项目初始化时明确 ndkVersion |
| C/C++ 辅助 | SDK CMake/Ninja；Windows 的 MSVC；Go race 所需 MinGW GCC | 两种 Windows 编译器用途不同，分别验证 |
| 桌面前端 | Node LTS、npm、WebView2 | 前端初始化后提交 package-lock.json，使用 npm ci |
| Linux | 独立 Linux 环境及系统依赖 | Windows 构建不证明 Linux GUI/托盘/输入法可用，仍需独立验证 |

SDK 编译 API 与 Android 最低运行 API 分开配置；v1 最低为 API 29。工具升级前核对 IDE/AGP/Gradle/JDK 矩阵，不将 SDK Manager 中的最高 API 自动当作 compileSdk。

## 2. Windows 会话入口

在当前 PowerShell 进程中执行：

```powershell
& .\scripts\enter-dev-env.ps1
```

脚本不安装工具、不写注册表、不修改用户或系统持久环境变量。它按显式参数 → 本机私有配置 → 已有环境变量/SDK 默认位置选择 JDK/SDK，配置当前进程的 PATH、NDK linker/CC/AR 和 Android API 29。SDK 中多个 NDK/CMake 未指定版本时取已安装的最高数字版本；正式工程初始化后应显式选择与项目一致的版本。

SDK/JDK 在非默认目录时，可使用 -AndroidSdk、-JavaHome、-NdkVersion、-CMakeVersion 参数，或建立私有配置：

```powershell
New-Item -ItemType Directory -Path .local -Force
Copy-Item scripts/dev-env.example.json .local/dev-env.json
```

编辑其中路径后再执行入口。`.local/` 整体被 Git 忽略，禁止强制提交。本机可选字段：

| 字段 | 含义 |
|---|---|
| androidSdk | 当前主机 SDK 根目录 |
| javaHome | 当前主机完整 JDK 根目录（必须含 javac） |
| ndkVersion / cmakeVersion | 当前主机选择的 SDK 组件版本 |
| proxyUrl | 当前主机 HTTP 代理地址，可不配置 |

代理必须主动启用：

```powershell
& .\scripts\enter-dev-env.ps1 -UseProxy
```

也可用 -ProxyUrl 显式传入当前主机的 HTTP 代理 URL。脚本只为当前进程及子进程设置 HTTP_PROXY/HTTPS_PROXY 和 Java 代理参数，不修改 Git、Cargo、Gradle 或操作系统的全局代理。关闭终端即可结束这些会话设置；不要通过新开的 `powershell -File` 子进程期待修改父终端环境。

此前 Windows 开发主机的代理为 127.0.0.1:7897，仅记录在被忽略的本机配置中；其他主机自行填写或不启用。不要在仓库内提交个人代理凭据、绝对路径或 local.properties。

## 3. 此前 Windows 开发主机实测记录（2026-11-03，仅本机）

以下版本用于说明本次验证组合，不表示其他主机必须使用这些安装路径或全局版本。完整本机清单、缓存位置和日志在 `.local/environment-report.json` 与该文件指向的本机缓存中，不上传 Git。

| 项目 | 处理与结果 |
|---|---|
| OS | Windows 10 专业版 22H2，19045，x86_64；开发进程非管理员 |
| Go | 已有 1.27.3，官方发布索引核对后复用；go test -race 通过 |
| Rust | 本机 stable 从 1.86.0 更新到 1.99.1；rustfmt、clippy 已更新 |
| Android Rust 目标 | 补装 aarch64-linux-android、x86_64-linux-android，两个目标均构建 SQLite 动态库通过 |
| Windows C/C++ | 已有 VS 2022 Community 17.13.6、MSVC 14.43、Windows SDK 10.0.22621.0；MinGW GCC 12.2.0 |
| JDK | 全局原有 Oracle 21.0.4 保留；本项目会话选现有 Android Studio JBR 21.0.5 |
| Android Studio | 现有 2024.3.1（AI-243），未替换 IDE；探针使用其兼容的 AGP 8.9.2/Gradle 8.11.1/API 35 |
| Android SDK | 已有 Platform 35/36、Build Tools 35.0.0/35.0.1、Platform Tools 35.0.2，复用 |
| Android 命令行 | 补装 Command-Line Tools 23.0，内含 Android CLI 1.1.16717390 |
| NDK / CMake | 补装 NDK r29（29.0.14206865）、CMake 3.22.1（含 Ninja 1.10.2） |
| Kotlin / Gradle | 探针用 Kotlin 2.1.20、AGP 8.9.2、Gradle 8.11.1、JDK 21 打包通过；不是正式 app 的已锁版本 |
| Rust/SQLite | 仓库外探针用 rusqlite 0.41.1，关闭默认特性并选择 libsqlite3-sys + bundled，验证原生 SQLite/FTS5 |
| Node / WebView2 | 已有 Node 22.22.1、npm 10.9.4、WebView2 156，未替换 |
| 模拟器 | 已有 API 35 x86_64 AVD、AEHD 2.2 加速可用；只读运行探针，Kotlin → Rust JNI → SQLite 返回 42，通过后卸载探针并关闭模拟器 |
| Linux | WSL 组件尚未启用；没有执行管理员提权、系统组件启用或重启，不宣称 Linux 构建/GUI 已验证 |

本机持久变更仅包括：Rust stable 更新、Android SDK 组件补装、用户级 ANDROID_HOME 和用户 PATH 的 Android 工具入口。原用户 PATH 备份在 `.local/user-path-before.txt`。没有修改系统级 JAVA_HOME、系统 PATH 或系统代理；项目的 JDK/NDK/代理选择由会话脚本承担。

外网故障处理：Go 发布索引直连超时，Rust 组件下载曾停滞，使用本机代理恢复；不关闭 HTTPS 校验、不全局切换镜像。保留原有 Go GOPROXY 设置，不将本机镜像固化到仓库。

## 4. 已完成验证与未完成项

- Go 独立标准库探针执行 go test -race 成功。
- Windows Rust 探针执行 cargo test、cargo fmt --check、cargo clippy --all-targets -- -D warnings 成功，包含 bundled SQLite/FTS5 验证。
- 同一探针以 NDK API 29 交叉构建 arm64/x86_64 Android 动态库成功。
- Kotlin Android APK 打包成功；加入两种架构的 Rust/SQLite 库后，离线重新打包成功。
- API 35 x86_64 模拟器启动和安装成功，实际调用 Rust JNI 并完成 SQLite/FTS5 操作返回 42；这不是 UniFFI 绑定验证，arm64 仅完成编译，尚未在真机运行。
- 所有探针位于仓库外的本机缓存，不是本项目业务代码或 conformance 测试。

尚未完成：正式 core/Go/Android/Tauri 工程、UniFFI 生成绑定、Android arm64 真机、Linux GUI、三端业务/同步测试、最终依赖版本锁定。现有 Studio 可继续使用已验证组合；采用超出其兼容范围的 AGP/compileSdk 前，应协调升级 IDE 和构建工具。

## 5. Android 命令行注意事项

本机安装的新版工具推荐 `android sdk`。其包名使用 `ndk/<版本>`、`cmake/<版本>`，通过 --sdk 指定当前主机路径，安装后检查 source.properties 和可执行文件真实存在。旧版 sdkmanager 的 `ndk;<版本>` 写法不能不加验证地交给新版兼容包装器；本次遇到过输出“Package not found”却退出码为 0 的情况。

没有全局安装 Gradle。正式 Android 工程建立时提交 Wrapper 与校验信息，再按锁定版本运行；不要复制本机 `.gradle/wrapper/dists` 的绝对路径到工程。

## 参考资料

- [Go 安装](https://go.dev/doc/install) / [官方发布索引](https://go.dev/dl/?mode=json)
- [Rust 安装](https://rust-lang.org/tools/install/)
- [Android CLI](https://developer.android.com/tools/agents/android-cli)
- [Android AGP 8.9 兼容信息](https://developer.android.com/build/releases/agp-8-9-0-release-notes)
- [Android 构建 JDK](https://developer.android.com/build/jdks)

## Linux 开发主机与原生探针

本次 Linux 主机记录见 [Linux 开发环境](dev-setup-linux.md)。另一台 Windows 主机的 UniFFI 探针记录见 [M0 原生绑定验证](../tools/native-probe/README.md)。各机器的安装与验证结果不互相替代。
