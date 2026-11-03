# ADR-0002：开发环境与本机配置边界

- 状态：已接受环境搭建方案，已完成部分构建验证；非业务实现验收
- 日期：2026-11-03

## 背景

本机将是主要开发环境，但项目也会在其他主机开发。当前机器已有 Go、Android Studio、JDK、MSVC，缺少 NDK/Android 命令行工具及 Rust Android 目标；原 Rust stable 过旧。路径与代理不能写死到跨主机项目配置。

## 决策

- 复用现有 Go、Visual Studio、JDK、Android Studio 和 SDK；将本机 Rust stable 更新到官方 1.99.1，补装 Android 两种目标。
- 补装官方 Command-Line Tools、NDK r29 和 SDK CMake；本机以 API 29 链接 Rust Android 库。
- 本机验证使用 AGP 8.9.2、Gradle 8.11.1、Kotlin 2.1.20、compileSdk 35，保持与现有 Studio 的兼容性；正式工程初始化时再锁定版本。
- 仓库提供只修改当前 PowerShell 进程的环境选择脚本；个人路径/代理由参数和被忽略的 `.local/` 提供，不写入通用构建文件。
- 不安装全局 Gradle，正式工程使用 Wrapper。不启用 WSL/重启，不把 Windows 的结果当作 Linux 验收。
- 构建探针放在本机缓存，正式仓库仍未初始化业务工程；本机明细报告只保存于 `.local/`。

## 工具与依赖核查

Rust/Cargo（MIT/Apache-2.0）、Go（BSD-3-Clause）、Gradle/Kotlin/AGP（Apache-2.0）均使用官方发布或官方 Maven 仓库；Android SDK/NDK 按其分发协议安装，CMake/Ninja 随官方 SDK 包提供。JDK/Visual Studio 复用已有安装及其原许可，不在本轮替换。

外部 Rust 探针使用 rusqlite 0.41.1（MIT，官方 crates.io 元数据要求 Rust ≥1.89），明确选择原生 libsqlite3-sys/bundled 后端，验证 C 工具链及 SQLite/FTS5；其依赖与锁文件仅属于该探针，不构成正式 core 依赖锁定。正式引入时仍需检查传递依赖许可证和更新状态。

## 备选方案与影响

将所有主机固定为本机绝对路径会破坏可移植性，改为显式选择。直接更新全部 IDE/SDK 虽可获得最新组合，也会扩大现有工程影响，当前先完成可工作的兼容组合。只检查命令是否存在不足以确认链接和打包，增加仓库外构建与模拟器探针。

环境脚本不偷偷安装软件或持久改变代理；当前主机发生的持久修改在开发说明中单独列出。未来正式工程版本约束优先于本机最高已安装版本。

## 验证来源

- [开发环境与本机记录](../dev-setup.md)
- [AGP 官方兼容矩阵](https://developer.android.com/build/releases/agp-8-9-0-release-notes)
- [Android CLI 官方说明](https://developer.android.com/tools/agents/android-cli)
- [rusqlite 官方发布](https://crates.io/crates/rusqlite/0.41.1)
