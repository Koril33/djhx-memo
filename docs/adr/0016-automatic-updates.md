# ADR-0016：三端自动更新与静态签名发行目录

- 状态：**Windows/Android 接受并实现；Linux 安装渠道仍为提议**。0.1.5 的实际验收与未完成项另记。
- 日期：2026-11-11。
- 范围：桌面安装渠道、Android 安装、共享发行验证、手工发布。
- 依据：[调研](../research/rust-gui-auto-update-survey.md)、[设计](../automatic-update-design.md)、[产品提案](../../spec/automatic-updates.md)。

## 背景与选择

现有桌面是 Tauri 2，Android 为 Kotlin/Compose + Rust core。Windows 使用 currentUser NSIS；Linux 仅有 DEB；公开包托管在 GitHub，无 CI/Runner。单一自研跨平台替换器会重复实现安装权限和签名处理，且无法让 Android/DEB 获得通用静默安装权限。

建议桌面复用官方 Tauri updater；新增 AppImage 自更新渠道，DEB 继续交给系统；Android 使用 PackageInstaller。统一静态签名发行目录由官网分发，不依赖 Go 账号 API；分享解析/验签行为，保留平台安装差异。安装前显式保存，不依赖进程正常退出事件。

## 锁定依赖与版本

2026-11-12 已按下表精确锁定依赖，核对官方 crate registry 校验和；更新 core/desktop 锁文件和随包通知。Windows 编译、Clippy 和双 ABI JNI 已运行，实际安装覆盖见[验证记录](../client-updates-0.1.5-validation.md)。这不代表 Linux 或完整安全评审已通过。

| 依赖 | 精确版本 | 许可证与维护 | 理由/边界 |
|---|---|---|---|
| `tauri-plugin-updater` | `2.14.1` | MIT OR Apache-2.0；[Tauri 官方仓库](https://github.com/tauri-apps/plugins-workspace/blob/updater-v2.14.1/Cargo.toml)、[发布元数据](https://docs.rs/crate/tauri-plugin-updater/2.14.1) | 2026-10-28 发布的稳定版；依赖 Tauri ^2.12，与现有 2.12.5 的版本约束相容；有 restore_update、版本签名与 NSIS/AppImage 支持。已做 Windows 编译/签名互通验证，不使用 3 alpha |
| `minisign-verify` | `0.3.0` | MIT；[作者固定源码](https://github.com/jedisct1/rust-minisign-verify/blob/0.3.0/Cargo.toml)、[发布记录](https://docs.rs/crate/minisign-verify/0.3.0) | 2026-09-25 发布，也被 updater 2.14.1 使用；共享 Rust 验证原始发行目录；无外部依赖但仍需安全评审，不自行实现密码算法 |
| `semver` | `1.0.28`（现有锁定） | MIT OR Apache-2.0；[上游](https://github.com/dtolnay/semver) | core 已直接引用该锁定版本；严格比较稳定版本，避免 UI 字符串排序 |
| Android `PackageInstaller` / `PackageManager` | 系统 API，项目 minSdk 29 / targetSdk 36 | Android 平台 API；[官方](https://developer.android.com/reference/android/content/pm/PackageInstaller) | 不引入 Play SDK 或第三方 APK 安装库；API 31+ 分支处理用户操作要求 |

HTTP、Base64、JSON 和 SHA-256 复用现有依赖，匿名更新客户端不复用带账号授权的同步请求对象。桌面原生 Rust 调用插件，没有增加 JS updater/process 权限。桌面测试额外开启已锁定 Tauri 的 `test` feature，仅供测试；Android 没有新增第三方安装/后台调度依赖。

上游当前活跃与有发布只能说明维护来源，不能当作无漏洞保证；启用前检查安全公告、验签边界和许可证通知。调研软件中 GPL/AGPL 更新逻辑不复制到 MIT 项目。

## 备选与代价

- **只读 GitHub latest 并打开下载页**：最省事，但达不到 Windows/AppImage/Android 自动下载与安装整合目标；保留为人工恢复入口。
- **所有平台自写安装助手**：维护成本大，绕不过系统权限；不采用。
- **仅 DEB + 签名 APT**：适合坚持系统包管理的 Linux 用户，但需维护仓库与系统配置；保留后续渠道，不把它冒充现有能力。
- **首期迁移 Flatpak/Play/winget**：增加渠道、商店政策和签名运维，不能覆盖现有侧载用户；暂缓。
- **只有 HTTPS + 包签名**：较简单，但目录中的版本、平台兼容与撤回信息缺少独立签名绑定；选用小型签名目录。它不是完整 TUF，冻结/根钥泄露边界在设计中明确说明。

代价是多一套目录签名管理、目录定期重签、AppImage 构建/验收和 Android 安装授权流程。换来的好处是无需常驻更新服务或 CI，账号服务故障不影响发行检查，三端能复用可信目标选择。

## 启用门槛

正式启用前完成隔离环境下的目录→包→真实安装→重启验收。本轮经维护者授权在仓库外生成发行目录/Windows 包私钥，候选客户端只包含公钥；这不代表生产目录已部署或上述链路已全部验收。U1 首次人工安装、旧安装身份、离线 outbox、输入法保存、包撤回与密钥桥接都要有实际记录。涉及保存/迁移/授权边界时补独立交叉评审；本 ADR 的写成不代表评审完成。
