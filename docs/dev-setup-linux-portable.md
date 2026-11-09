# Linux 公共开发说明

本文面向新克隆仓库的 Linux 主机，命令从仓库根目录执行，不依赖原主机的环境脚本。原 Debian 13 主机的安装路径和探针见[历史记录](dev-setup-linux.md)，不能照搬为其他机器的默认配置。

桌面打包基线是 Ubuntu 24.04 x86_64；已记录的实际 GUI 主机为 Debian 13 与 Ubuntu 26.04，不能用容器构建替代 Ubuntu 24.04 真桌面或其他发行版验收。当前证据见[桌面验证](desktop-validation.md)、[界面预览验证](desktop-ui-preview-validation.md)和[验收清单](validation.md)。

## 1. 按模块安装工具

只做服务端时安装 Go；只做 core 时安装 Rust 与本机 C/C++ 工具；做桌面再安装 Node/npm、GTK/WebKit 开发包和真实桌面环境。仅桌面开发无需 JDK、Android SDK/NDK 或 Go。

Ubuntu 24.04/Debian 的桌面构建系统依赖示例：

```sh
sudo apt update
sudo apt install --no-install-recommends \
  build-essential ca-certificates curl git pkg-config libssl-dev \
  libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev libxdo-dev \
  libglib2.0-bin patchelf file python3 xdg-utils
```

这组依赖对应仓库 [Dockerfile.linux](../scripts/Dockerfile.linux)和 [Tauri Linux 前置说明](https://v2.tauri.app/start/prerequisites/)。其他发行版按上游说明选择同等包，不混装 Debian/Ubuntu 版本的软件源。SQLite 随 Rust core 编译，不要求为了业务数据库再安装系统 SQLite 开发库。`libglib2.0-bin` 提供 Linux 外观检测用的 `gdbus/gsettings`。

通过 [Rust 官方 rustup](https://rust-lang.org/tools/install/)安装 Rust，随后选用与既有验证相同的工具链：

```sh
rustup toolchain install 1.99.1 --profile minimal --component rustfmt --component clippy
export RUSTUP_TOOLCHAIN=1.99.1
rustc -vV
cargo --version
```

如果新终端找不到命令，按 rustup 安装提示加载 `$HOME/.cargo/env`。项目最低 Rust 为 1.99，依赖使用 Cargo.lock + `--locked`；这里的终端变量只用于选择参考编译器，不修改所有项目的全局默认值。

桌面使用 Node 22.x（至少 22.12）与 npm。可从 [Node 官方下载](https://nodejs.org/en/download)安装，或选择兼容版本管理器；已实测 22.23.2 / npm 10.9.8。发行版旧 Node 包可能不满足 Vite 构建要求。工具下载核对官方校验值，不从其他应用的私有运行目录借用 Node。

```sh
git --version
node --version
npm --version
pkg-config --modversion gtk+-3.0 webkit2gtk-4.1 ayatana-appindicator3-0.1
```

## 2. core 与桌面

core 本机检查不需要 GUI：

```sh
bash scripts/validate-local.sh core
```

桌面首次安装与完整检查：

```sh
cd desktop
npm ci
npm run format:check
npm run lint
npm run check
npm test
npm run build
cargo fmt --manifest-path src-tauri/Cargo.toml --check
cargo check --locked --manifest-path src-tauri/Cargo.toml
cargo clippy --locked --manifest-path src-tauri/Cargo.toml -- -D warnings
cargo test --locked --release --manifest-path src-tauri/Cargo.toml --bin djhx-memo
npm run tauri dev
```

`tauri dev` 需要在本机图形会话中运行；关闭开发进程后回到仓库根目录。统一 `bash scripts/validate-local.sh desktop` 包含基础检查，但不替代上面的格式、lint 与原生 Clippy/测试。

GNOME 下托盘需要可用的 AppIndicator 宿主/扩展，具体启用方式由发行版决定；单装开发头文件不代表桌面托盘可用。凭据持久化需要桌面 Secret Service/keyring 可用；缺失时应用明确提示凭据仅驻留进程。Wayland/X11、中文输入法、托盘、原生文件对话框及主题跟随应在实际目标会话验证。不要从无图形会话的 SSH 构建成功推导这些交互通过。

构建 deb（从 `desktop/`）：

```sh
npm run tauri build -- --config src-tauri/tauri.linux.conf.json --bundles deb
find src-tauri/target/release/bundle/deb -maxdepth 1 -name '*.deb' -print
sha256sum src-tauri/target/release/bundle/deb/*.deb
```

默认产物在 `desktop/src-tauri/target/release/bundle/deb/`；设置 `CARGO_TARGET_DIR` 后在该 target 查找。兼容性说明见 [Tauri Debian 打包](https://v2.tauri.app/distribute/debian/)。仓库的 [Dockerfile.linux](../scripts/Dockerfile.linux)固定 Ubuntu 24.04 基础镜像并安装系统构建依赖，**不包含 Rust 或 Node**；可作为自行配置工具链的容器基础层，不能把维护者的缓存镜像名当成新 clone 已有环境。容器适用于隔离原生构建依赖，实际安装和 GUI 验收仍在目标系统执行。

## 3. 可选：服务端与联调

按 [Go 官方安装说明](https://go.dev/doc/install)安装满足 `server/go.mod` 的 Go（当前至少 1.27.0，已实测 1.27.3），不要依赖发行版旧版 Go。运行服务端完整检查还需 [server README](../server/README.md)中锁定的 golangci-lint；`-race` 需要可用的 C 编译器。

```sh
go version
bash scripts/validate-local.sh server
bash scripts/validate-local.sh integration
```

联调入口使用自己编译的临时服务端和隔离数据，不需要真实账号或维护者的线上邀请码。部署、TLS、备份与恢复另见[运维说明](operations.md)，不要把测试实例当作生产数据目录。

## 4. 可选：Android

Android 的完整命令、两种 ABI、绑定生成与签名边界见 [Android README](../android/README.md)。先安装 JDK 21、兼容当前 AGP 的 Android Studio、SDK API 36、Platform Tools、NDK 30.0.16248370；IDE 使用其随附 JBR，Gradle 的 JDK 设置和命令行 `JAVA_HOME` 对齐。Java 源码目标 17 不表示必须使用 JDK 17 运行当前参考构建，参见 [Android JDK 说明](https://developer.android.com/build/jdks)。

根据自己的安装位置设置，下面默认 SDK 路径仅是常见约定；JDK 必须替换为真实安装目录：

```sh
export JAVA_HOME="/path/to/jdk-21"
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$HOME/.cargo/bin:$PATH"
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
java -version
adb version
bash android/scripts/build.sh
```

使用工程内 Gradle Wrapper，不安装全局 Gradle/Kotlin。首次先执行完整构建入口生成 UniFFI/JNI 产物，再按 Android README 执行仪器测试；模拟器和物理 arm64 设备分别验收。发布签名材料放在本机，未提供发布签名时生成 unsigned release APK，不能直接作为可安装发布包。Android 最低运行 API 29、编译 API 36 和 NDK 版本由当前工程决定，不使用历史探针的 API 24/四 ABI 参数。

## 5. 可选网络设置

只在本机 HTTP 代理确实监听时设置当前终端，例如：

```sh
export HTTP_PROXY=http://127.0.0.1:7890
export HTTPS_PROXY="$HTTP_PROXY"
```

不需要时在同一终端执行 `unset HTTP_PROXY HTTPS_PROXY`。Gradle 不自动使用这些 shell 变量；可按 [Gradle 网络说明](https://docs.gradle.org/current/userguide/networking.html)在个人 `~/.gradle/gradle.properties` 配置 Java HTTP/HTTPS 代理，Android Studio 代理单独配置。端口是可选例子，不提交真实代理凭据、不关闭证书校验。缓存不完整时不要加 `--offline`，先恢复网络并取得锁定的依赖。
