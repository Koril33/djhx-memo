# ADR-0004：本机开发工具链的安装与隔离

- 状态：已接受（项目负责人已授权本轮配置本机 Go、Rust 和 Android 开发环境）
- 日期：2026-11-03

## 背景

仓库目前仅有目录骨架，尚无语言版本约束、依赖清单和可执行工程。本轮需要准备 Linux 本机开发环境，特别是安装已下载的 Android Studio，并支持后续 Rust 核心库的 Android 交叉编译。

## 决策

- Go 使用官方维护的稳定版发行包，在用户目录安装，与系统包分离；下载后验证官方 SHA-256。
- Rust 使用官方 rustup 管理稳定版、rustfmt、Clippy 与 Android 编译目标，不以 Debian 的独立 rustc 包混合管理。
- 本机开发辅助工具包括官方 Go 语言服务 gopls、govulncheck、golangci-lint 和 cargo-ndk；cargo-ndk 负责 NDK 链接器及 C/C++ 构建变量，避免给所有 Rust 项目写入全局 Android 链接器配置。
- Android Studio 使用项目负责人下载的 Linux 安装包，安装到用户目录并提供桌面入口。
- 复用 `~/Android/Sdk`，通过官方 SDK Manager 补齐稳定版 SDK、Build Tools、NDK 和构建工具；保留已有镜像、模拟器与签名材料。
- 将环境变量集中放入用户配置文件，由实际使用的 shell 加载；不把本机绝对路径、安装包、凭据或签名材料写入项目构建配置。
- Kotlin、Android Gradle Plugin 和 Gradle Wrapper 在 Android 工程初始化时按兼容矩阵确定，本轮不添加全局 Kotlin/Gradle 或业务依赖。

## 许可证与维护

| 工具 | 许可证/使用条款 | 维护来源 |
|---|---|---|
| Go | BSD-3-Clause | Go 官方发布与安全维护 |
| Rust、rustup、rustfmt、Clippy | MIT / Apache-2.0 双许可（各组件以随附文件为准） | Rust 官方稳定通道 |
| Android Studio、SDK、NDK、模拟器 | Android SDK 使用条款及各组件随附的开源许可证 | Google / Android 官方发布通道 |
| JDK | 使用现有 OpenJDK 或 Studio 随附运行时，许可证以发行包为准 | Debian / JetBrains Runtime |
| C/C++ 基础构建工具 | 使用 Debian 软件包，许可证以各包版权文件为准 | Debian 稳定发行版 |
| gopls、govulncheck | BSD-3-Clause | Go 官方工具仓库 |
| golangci-lint | GPL-3.0 | golangci-lint 上游发布 |
| cargo-ndk | MIT / Apache-2.0 双许可 | bbqsrc/cargo-ndk 上游维护；采用已发布版本与锁定依赖安装 |

上述工具属于开发环境，不构成已确认的应用依赖或框架选型。后续引入项目依赖仍需另行记录 ADR，并提交相应锁文件。

## 备选方案

- 全部使用 Debian 工具链：安装简单，但已有 Go 版本较旧，Android 和 Rust 的目标管理也需要官方工具。
- 覆盖系统工具：会影响其他项目和系统包管理，故采用用户目录优先及集中环境配置。
- 本轮同时初始化全部工程：超出本机环境配置范围，且正式协议与工程级版本选择尚未完成。

## 影响

本机可进行编译器、SDK 和交叉编译的独立冒烟验证；这不代表业务测试或一致性测试通过。实际安装版本、路径、验证结果及未完成事项记录在 [开发环境](../dev-setup-linux.md)，工程初始化时再固定 CI 与仓库级工具链版本。
