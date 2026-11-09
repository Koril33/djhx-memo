# 开发环境入门

从新克隆的仓库开始，按需要开发的模块安装工具即可。以下入口不依赖维护者的用户名、SDK 路径、代理或私有环境脚本；命令默认从仓库根目录执行。当前没有 CI/Runner，本地执行检查并记录实际结果。

| 开发主机或任务 | 入口 | 验证范围 |
|---|---|---|
| Linux 桌面、core、服务端 | [Linux 公共开发说明](dev-setup-linux-portable.md) | Ubuntu 24.04 构建基线；实际 GUI 系统另见验证记录 |
| Windows 原生桌面、core | [Windows 公共开发说明](dev-setup-windows.md) | 已有 Windows 10 x64 原生桌面构建与安装记录 |
| Android | [Android README](../android/README.md) | Linux Bash 或 Windows 原生 PowerShell，均从当前源码生成绑定和 JNI |
| 服务端部署、备份与恢复 | [运维说明](operations.md) | Linux/systemd/Nginx；不由 Windows 桌面构建结果替代 |

## 工具与锁定来源

| 工具 | 当前工程要求与参考组合 | 哪些任务需要 |
|---|---|---|
| Git / Python | Git；Python 3.11+（标准库脚本，实测3.13.5） | 获取源码、文档/契约/安全检查；品牌再生成另需ADR-0010工具 |
| Rust | core 与桌面 Cargo.toml 声明 `rust-version` 1.99；已实测 1.99.1，配套 rustfmt/Clippy | core、桌面、Android 原生库 |
| Node / npm | 桌面要求 Node 22.12+；已实测 Node 22.23.2 / npm 10.9.8 | 桌面前端及 Tauri CLI |
| Go | [go.mod](../server/go.mod) 声明 1.27.0；已实测 1.27.3 | 服务端及真实 Go/core 联调；纯客户端可不安装 |
| JDK | Android 当前实测 JDK 21；Java/Kotlin 产物目标为 17 | Android；纯桌面不需要 |
| Android 工具 | AGP 8.13.0 / Gradle Wrapper 8.13 / Kotlin 2.0.21，compile/target API 36、最低 API 29、NDK 30.0.16248370 | Android；以当前工程为准，不沿用历史探针版本 |
| C/C++ 与系统 WebView | Linux 的 GTK3/WebKitGTK 4.1；Windows 的 MSVC、Windows SDK 与 WebView2 | 随包 SQLite、桌面原生层；细节见平台入口 |

当前未提交 `rust-toolchain.toml`；`rust-version` 是最低版本约束，**`Cargo.lock` 锁定依赖，不锁定编译器**。可在开发终端选择上述已实测版本，升级后重新运行相关检查。工具组合来自当前工程和[模块验证](validation.md)，不是全局安装脚本。

保留并使用以下文件：

- Rust：`core/Cargo.lock`、`desktop/src-tauri/Cargo.lock`，构建和检查使用 `--locked`。
- npm：`desktop/package-lock.json`，首次安装用 `npm ci`，Windows PowerShell 用 `npm.cmd ci`；使用仓库内 Tauri CLI，不要求全局安装。
- Go：`server/go.mod`、`server/go.sum`。
- Android：`gradlew` / `gradlew.bat`、Wrapper 配置与 SHA256、Gradle 依赖锁文件；不需要全局 Gradle 或 Kotlin。

修改依赖前阅读 [ADR](adr/README.md)，更新连同锁文件、许可及实际验证一起提交。首次构建需要访问对应官方下载和包仓库；`--offline` 只适用于依赖已完整缓存的环境。

## 从源码验证

各模块的开发、运行和产物路径见 [core](../core/README.md)、[server](../server/README.md)、[desktop](../desktop/README.md)、[Android](../android/README.md)。Linux 可使用统一入口：

```sh
bash scripts/validate-local.sh core
bash scripts/validate-local.sh server
bash scripts/validate-local.sh integration
bash scripts/validate-local.sh desktop
# 首次需先按 Android README 生成 JNI/UniFFI 产物。
bash scripts/validate-local.sh android
```

每条选择并检查一个模块；桌面完整格式/lint 与原生 Clippy/测试另见平台说明。Windows 原生命令见 Windows 入口，该 Bash 脚本不是 PowerShell 脚本。自动测试不替代托盘、中文输入法、原生文件对话框及设备安装验收。

文档与契约引用检查：

```sh
python3 scripts/check-docs.py
git diff --check
```

## 本机配置与可选代理

SDK/JDK 绝对路径、`.local/`、`android/local.properties`、签名材料、密码和 token 留在本机。项目不要求某个个人目录，也不要求连接维护者的服务器即可开发离线功能；集成测试使用自己控制的隔离实例。

网络需要代理时才设置当前终端的代理。例如本机 HTTP 代理监听 `127.0.0.1:7890`，Linux 可设置 `HTTP_PROXY` / `HTTPS_PROXY`，Windows 可设置同名进程环境变量；Gradle/Android Studio 的代理另按平台说明配置。端口只是示例，代理不应成为仓库默认值，不关闭 HTTPS 校验或提交代理凭据。

## 历史本机记录

以下材料保留当时的安装路径、探针组合和处理过程，供追溯使用；不作为新主机配置步骤，也不表示当前设备矩阵已完成：

- [Linux 本机安装与环境探针（2026-11-03）](dev-setup-linux.md)。其中 `~/.config/djhx-dev/env.sh` 只存在于原主机。
- [另一台 Windows 主机的环境探针（2026-11-03）](dev-setup-windows-history.md)。
- [M0 UniFFI 绑定探针](../tools/native-probe/README.md)。
- [桌面实现验证](desktop-validation.md)、[0.1.1 界面验证](desktop-ui-preview-validation.md)及[0.1.2 修正验证](client-fixes-0.1.2-validation.md)。

完整三端矩阵、物理 arm64 Android 与连续 7 天试用仍以[验收清单](validation.md)的实际状态为准。
