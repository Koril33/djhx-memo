# 开发环境与实施准备

状态：准备清单，尚未初始化工程；本文件不是已完成环境安装的记录。

## 工具与平台

| 模块 | 需要的工具 | 版本策略 |
|---|---|---|
| core | Rust stable、Cargo、rustfmt、clippy | 初始化时以 rust-toolchain.toml 锁定具体版本，提交 Cargo.lock |
| server | Go、格式化/静态检查工具 | go.mod 记录版本，提交 go.sum；SQLite 驱动选择和许可证写 ADR |
| Android | JDK、Android SDK/NDK、Gradle Wrapper | 兼容 Android 10+，具体 JDK/AGP/NDK 在绑定验证时锁定 |
| desktop | Tauri 2 工具、Node LTS、npm、系统 WebView 开发依赖 | 提交 package-lock.json，使用 npm ci；桌面端 Rust 依赖也锁定 |
| Windows | C++ 构建工具、WebView2 | 在目标 Windows 11 x86_64 记录构建与运行环境 |
| Linux | Tauri 系统依赖、AppIndicator 支持、中文输入法 | 在 Ubuntu 24.04 x86_64 记录 Wayland/X11、桌面扩展和包版本 |

工具版本在下一轮实际验证后填入，不在文档阶段猜测“最新版本”。生产服务 URL、TLS 域名、真实 Linux 发行版与设备信息在实现/部署时记录，不是新增产品决策。

## 初始化后的统一入口

实施阶段建立本地统一入口（优先 justfile），覆盖 setup、fmt-check、lint、test-core、test-server、test-conformance、test-desktop、test-android 和 check。入口支持 PowerShell 和 Linux，安装步骤明确列出系统依赖，不暗中安装软件。

目前上述命令尚不存在。Android 真机、桌面托盘/输入法和恢复演练不能被单个“全仓检查成功”替代。无需建立 CI、Runner 或远程流水线。

调试服务器可以使用显式开启的本地 HTTP 配置；生产客户端必须验证 HTTPS。测试凭据只用于临时数据库，不提交真实 token、邀请码、密码或笔记。

## M0 本机验证记录

基于文档提交 `4633cf7`。Windows 10 Pro x86_64（19045）、Rust 1.99.1 GNU、Android Studio JBR 25.0.4 / Kotlin 2.4.0、NDK r29。

| 检查 | 结果 |
|---|---|
| SQLite 提交、关闭后重开、中文 trigram 查询与数据库失败路径 | Rust 主机测试通过 |
| Kotlin → UniFFI → Rust → SQLite | Windows JVM 主机测试通过，含提交回调与错误转换 |
| Android arm64 / x86_64 原生库 | API 29 交叉编译与链接通过，已从 arm64 库生成 Kotlin 绑定 |
| Android 设备进程内加载和重启后读回 | 待验证，ADB 无设备且模拟器加速不可用 |
| Windows Tauri 托盘 | 待验证，MSVC C++ 工具与 Windows SDK 尚未安装 |
| Linux Tauri 托盘和中文输入法 | 待验证，当前无 Linux 图形会话 |

复现命令见 [原生绑定验证](../tools/native-probe/README.md)。记录覆盖 M0 技术探针，发布验收依照 [本地质量检查与 v1 验收](validation.md)。
