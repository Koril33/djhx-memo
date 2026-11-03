# 本机开发环境

本轮按项目负责人要求完成 Linux 本机 Go、Rust 和 Android 环境配置。工具来源与隔离方式见 [ADR-0001](adr/0004-linux-development-toolchains.md)。

仓库仍只有目录骨架：没有 `go.mod`、`Cargo.toml`、Android Gradle 工程、`justfile` 或 CI。本文记录本机环境，不定义正式协议，不固定未来项目的最低 Android 版本、依赖版本或 CI 工具链。后续工程初始化时需提交对应工具链配置、Gradle Wrapper 和依赖锁文件。

## 本机版本与路径

记录日期：2026-11-03。操作系统为 Debian 13（trixie），架构为 x86_64。

| 组件 | 已安装版本 | 路径或说明 |
|---|---|---|
| Go | 1.27.3 | `~/.local/opt/go` 指向 `~/.local/opt/go1.27.3/go`，优先于系统 Go |
| gopls | 0.24.0 | `~/go/bin/gopls` |
| golangci-lint | 2.15.0 | `~/.local/bin/golangci-lint` |
| govulncheck | 1.9.0 | `~/go/bin/govulncheck` |
| Rust / Cargo | 1.99.1 | rustup 的 `stable-x86_64-unknown-linux-gnu`，入口位于 `~/.cargo/bin` |
| Rust 开发组件 | rustfmt、Clippy、rust-analyzer、rust-docs | 与当前稳定工具链配套 |
| cargo-ndk | 4.1.2 | `~/.cargo/bin/cargo-ndk`，通过 `--locked` 安装 |
| Android Studio | Rabbit 2 / 2026.2.2 | `~/.local/opt/android-studio`，build `AI-262.10968.63.2622.16730706` |
| IDE 运行时 | JBR 25.0.4 | Studio 随附 `jbr/`，启动器通过 `STUDIO_JDK` 指向它 |
| 命令行 JDK | OpenJDK 21.0.12.1 | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` |
| Android SDK | 复用原有 SDK | `~/Android/Sdk`，通过 `ANDROID_HOME` 指向它 |
| SDK Platform | Android 36、37.0 | 保留原有 API 36，新增 API 37.0 revision 2 |
| SDK Build Tools | 35.0.0、36.1.0、37.0.0 | 保留已有版本并补充 37.0.0 |
| SDK Platform Tools | 37.0.1 | PATH 优先使用 SDK 内的 adb |
| SDK Command-line Tools | 23.0 | `~/Android/Sdk/cmdline-tools/latest` |
| Android CLI | 1.1.16717390 | Command-line Tools 23 提供的官方命令入口 |
| Android Emulator | 37.2.13 | `~/Android/Sdk/emulator` |
| Android NDK | 30.0.16248370（r30） | `~/Android/Sdk/ndk/30.0.16248370` |
| CMake | 3.31.6 | Debian 主机构建工具与 SDK 内同版本组件 |
| 主机构建依赖 | build-essential、pkg-config、libssl-dev、libsqlite3-dev、libclang-dev、ninja-build | 使用 Debian 包管理；另安装 ripgrep 供源码检索 |

Go 发行包的 SHA-256 已与官方发布元数据核对：

```text
65efbc80fdc1fa2cd2631cad5b481777a68e8725f34e89f50fc6ac714e379b40
```

golangci-lint 发行包也已与上游 checksums 文件核对。Android Studio 使用负责人已下载的 `android-studio-rabbit2-linux.tar.gz`，其 SHA-256 已与 [Android 官方下载页](https://developer.android.com/studio) 核对一致：

```text
b47fdcc0703019618a7afd5ad1822cafc5de66bed984640be2977a0fdb538304
```

SDK/NDK 组件通过官方 SDK Manager 安装。旧 Command-line Tools 19.0 保留于 `cmdline-tools/19.0`，`latest` 指向本轮安装的 23.0。

## 环境加载与常用命令

环境变量集中保存在 `~/.config/djhx-dev/env.sh`，已接入 `~/.profile`、`~/.bashrc` 和 `~/.zshrc`。新终端自动加载；当前终端可执行：

```sh
. "$HOME/.config/djhx-dev/env.sh"

go version
rustc --version
cargo --version
java -version
adb version
android --version
```

配置设置 `JAVA_HOME`、`ANDROID_HOME`、`ANDROID_NDK_HOME`、`NDK_HOME`，并将 Go、Cargo、Go 工具目录及 SDK 的命令行工具、adb、模拟器加入 PATH。重复加载不会重复追加这些 PATH 条目。Go 的 `GOROOT` 由工具自身确定，不手工固定。

此配置在加载时选择 JDK 21；如果其他项目需要 SDKMAN 的其他 JDK，可在该终端中加载本配置后执行 `sdk use java ...`。Android Studio 始终使用自己随附的 JBR 25；新 Android 工程的 Gradle JDK 应显式选择与命令行一致的 JDK 21，再结合实际 Gradle / AGP 版本确认兼容性。

启动 Android Studio：

```sh
android-studio
```

应用菜单中也有 **Android Studio Rabbit 2**。桌面文件为 `~/.local/share/applications/android-studio-rabbit2.desktop`，启动脚本为 `~/.local/bin/android-studio`。IDE 使用独立的 `~/.config/Google/AndroidStudio2026.2.2`，已指向现有 SDK；旧版 IDE 配置仍保留。

## 网络与 SDK 管理

本机当前使用 `127.0.0.1:7890` HTTP 代理。环境安装已沿用现有 `HTTP_PROXY` / `HTTPS_PROXY`，没有把账户密码或代理凭据写入仓库。

- `~/.gradle/gradle.properties` 的 HTTP/HTTPS 代理已由无监听的旧端口改为 7890，原文件已备份。Gradle 使用 Java 的代理配置，不自动读取 shell 的 HTTP 代理变量。
- Android Studio 的 HTTP 代理已配置为 `127.0.0.1:7890`，可在 Settings → HTTP Proxy 中修改。
- Command-line Tools 23 的 Android CLI 原生使用系统代理。旧的 `sdkmanager` 入口仍兼容，但会提示已弃用并转调 `android sdk`；不再需要 SDK Manager 的旧 `--proxy` 参数。本机没有保留额外的 SDK Manager 代理包装脚本。

```sh
android sdk list

# 后续重新安装本机所选稳定组件的示例。
for package in \
  "platform-tools" "cmdline-tools/latest" "emulator" \
  "platforms/android-37.0" "build-tools/37.0.0" \
  "ndk/30.0.16248370" "cmake/3.31.6"; do
  android sdk install "$package"
done
```

已有 SDK 许可证已保留。增加组件若出现新许可证，应根据官方工具的提示处理。代理软件未运行或端口改变时，需要同步调整 Gradle 与 IDE 的设置；Android CLI 跟随系统代理配置。

## Rust 到 Android 的交叉编译

已安装四个 Android Rust 目标：

| Android ABI | Rust target |
|---|---|
| arm64-v8a | `aarch64-linux-android` |
| armeabi-v7a | `armv7-linux-androideabi` |
| x86 | `i686-linux-android` |
| x86_64 | `x86_64-linux-android` |

后续初始化 Rust crate 后，可使用以下命令；API 24 仅是本轮独立验证的参数，实际产品最低版本需在 Android 工程初始化时决定：

```sh
cargo ndk \
  -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 \
  --platform 24 build
```

`cargo-ndk` 会设置 NDK 链接器与 C/C++ 构建变量。没有写入全局 Cargo Android 链接器配置，也没有在项目中添加 UniFFI、SQLite 等依赖。

## 模拟器

`emulator -accel-check` 已确认 `/dev/kvm` 可用，当前用户已有 kvm 组权限。

新建的独立 AVD 为 `djhx_dev_api36`：Pixel 7 设备配置，使用本机已有 Android 36.1 Google Play x86_64 系统镜像。原有 AVD 与签名材料均保留。

本轮已验证软件图形渲染，开发 AVD 的 `hw.gpu.mode` 设为 `software`；CPU 虚拟化仍由 KVM 加速。

创建带小数 API 版本的 AVD 必须使用更新后的 Command-line Tools。本轮发现旧 19.0 工具把 Android 36.1 写成 `target=android-0`，导致模拟器虽报告启动完成，SurfaceFlinger 仍反复崩溃；仅更换 GPU 模式不能解决。已使用 23.0 重建本轮新增的 AVD，确认 `.ini` 与 `config.ini` 均为 `target=android-36.1`。该现象与 [上游工具问题的验证记录](https://github.com/DeviceFarmer/stf/pull/928) 一致。

```sh
emulator -list-avds
emulator -avd djhx_dev_api36
adb devices -l
```

这次没有下载额外的 API 37 模拟器镜像。API 37 的 SDK 可用于编译；需要在 API 37 上运行测试时，再通过 Device Manager 添加对应镜像。

## 验证记录与边界

验证使用仓库外临时工程，避免把环境验证代码当作业务实现或一致性验收。

- Go：独立模块通过 `gofmt`、`go vet`、`go test -race` 和 `golangci-lint run --no-config`；覆盖 SQLite 原生库链接与 Go 竞态运行时。
- Rust：独立 crate 通过 `cargo fmt --check`、`cargo clippy -- -D warnings` 和 `cargo test`。
- Android 原生库：四种 ABI 均成功生成共享库，ELF 头确认对应 ARM64、ARM、x86、x86_64 与 Android API 24。
- Android 构建：使用本机既有 Gradle 8.13、AGP 8.13.0、Kotlin/Compose 编译插件 2.0.21，构建最小 Kotlin + Compose APK 成功（compileSdk 36、Build Tools 36.1.0、命令行 JDK 21）。这些只是临时验证版本，不是项目版本决策。
- Android 运行：上述 APK 在新 AVD 上安装成功，`am start -W` 返回 `Status: ok`，前台 Activity 确认为验证应用；已读取截图确认中文 Compose 文本成功渲染。Rust x86_64 原生程序在同一模拟器上运行，返回预期结果 `42`。
- 模拟器稳定性：修复 AVD 元数据后观察运行超过三分钟，SurfaceFlinger PID 未变化、crash 日志为空。验证后已卸载临时应用、清理临时原生程序并关闭验证模拟器，AVD 留作后续开发使用。
- Android Studio：已实际启动，日志确认使用随附 JBR 25，无启动错误；桌面文件已通过 `desktop-file-validate`。
- 环境配置：已检查 shell 语法、登录 shell 的工具解析路径与重复加载的幂等性。

独立验证材料保存在 `~/.local/state/djhx-dev-setup/`：`smoke/` 为源码，`artifacts/` 为 APK 与四种 ABI 的共享库，`runtime-verification.txt` 为运行记录，`android-smoke.png` 为截图，`android-build.log` 为构建日志。这些文件均在仓库外。

本仓库尚无业务测试、仪器测试或跨语言一致性测试，本轮没有声称这些测试通过。Kotlin、AGP、Compose、Gradle Wrapper、ktlint、detekt 和项目级 lint 规则将在 Android 工程初始化时统一配置；本轮没有安装全局 Kotlin 或 Gradle。

## 本机变更与回退

shell 配置与 Gradle 配置的原始备份位于 `~/.local/state/djhx-dev-setup/backups/`。恢复对应文件即可撤销环境加载及代理修改。Studio 启动日志位于 `~/.local/state/djhx-dev-setup/android-studio-startup.log`。

Go 在用户目录安装，系统 `/usr/bin/go` 保留；移除用户环境中的 Go PATH 项即可重新使用系统版本。Rust 使用官方 rustup 管理；Android Studio、用户启动脚本和桌面文件可分别移除。SDK 与已有模拟器共用目录，不应为回退本轮配置而删除整个 SDK、AVD 或签名文件。

## 官方参考

- [Go 安装](https://go.dev/doc/install)、[官方发行包及校验值](https://go.dev/dl/)
- [rustup 安装](https://rust-lang.github.io/rustup/installation/index.html)、[交叉编译目标管理](https://rust-lang.github.io/rustup/cross-compilation.html)
- [Android Studio 安装](https://developer.android.com/studio/install)、[Java 与 Android 构建](https://developer.android.com/build/jdks)
- [Android CLI 与新版 SDK 命令](https://developer.android.com/tools/agents/android-cli)
- [NDK 与其他构建系统](https://developer.android.com/ndk/guides/other_build_systems)、[cargo-ndk 上游文档](https://github.com/bbqsrc/cargo-ndk)
- [AGP 与 Gradle 兼容关系](https://developer.android.com/build/releases/about-agp)、[golangci-lint 安装](https://golangci-lint.run/docs/welcome/install/)
