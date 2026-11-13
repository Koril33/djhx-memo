# Rust GUI 软件更新方式调研

- 调研日期：2026-11-11。
- 范围：17 个独立产品或产品家族；覆盖 Rust 原生 GUI、Rust 为核心的 Tauri/Flutter 混合 GUI。图形终端属于 GUI；纯终端 TUI 不计入。
- 方法：读取官方说明、默认分支的固定提交源码、官方发布配置。下表列出关注量快照以说明样本代表性，不作为市场占有率或更新质量排名；多产品仓库的 star 属于整个仓库。
- 限制：这是文档/源码调研，没有安装并逐个执行 17 个软件的更新。开发分支能力不等于已经发布；“未见内置更新器”限于列出的源码与入口，不能证明所有发行包都没有该能力。
- 项目基线：`93b0f18`（0.1.4 开发分支）；研究分支 `codex/automatic-update-research`。当前项目未加入自动更新代码。

## 1. 能力口径

必须分别记录：**发现更新 → 下载资产 → 安装/替换 → 重启确认成功**。只通知用户打开网页不属于完整自动更新；系统包管理器的后台升级也不是应用自己静默安装。下文描述已读证据，不把未核实的哈希/签名能力补写给上游。

## 2. 样本总览

| # | 软件 | Rust GUI 形态 | 仓库 star 快照 | 主要更新方式 |
|---|---|---|---:|---|
| 1 | [Zed](https://github.com/zed-industries/zed) | GPUI / Rust | 94,742 | 后台下载，重启应用 |
| 2 | [Lapce](https://github.com/lapce/lapce) | Floem / Rust | 39,006 | GitHub API + 平台安装逻辑 |
| 3 | [Alacritty](https://github.com/alacritty/alacritty) | Rust / OpenGL 图形终端 | 66,167 | 包管理器或手动更新 |
| 4 | [WezTerm](https://github.com/wezterm/wezterm) | Rust 图形终端 | 29,529 | 只检查和通知 |
| 5 | [Warp](https://github.com/warpdotdev/warp) | Rust 原生 GUI | 65,981 | 自有服务 + 平台适配 |
| 6 | [RustDesk](https://github.com/rustdesk/rustdesk) | Rust 核心 + Flutter | 129,022 | 自研更新 + 签名元数据 |
| 7 | [GitButler](https://github.com/gitbutlerapp/gitbutler) | Rust + Tauri / Svelte | 21,953 | Tauri + 渠道判断 |
| 8 | [Spacedrive](https://github.com/spacedriveapp/spacedrive) | Rust + Tauri / React | 39,486 | Tauri 2 分段下载/安装 |
| 9 | [Clash Verge Rev](https://github.com/clash-verge-rev/clash-verge-rev) | Tauri / Rust 外壳 + Go sidecar | 157,776 | Tauri + 后台缓存 |
| 10 | [Pot](https://github.com/pot-app/pot-desktop) | Tauri 1 / Rust + React | 19,336 | Tauri 1 更新窗口 |
| 11 | [Hoppscotch Desktop](https://github.com/hoppscotch/hoppscotch) | Rust + Tauri | 80,782 | Rust 更新服务 + UI 事件 |
| 12 | [Yaak](https://github.com/mountain-loop/yaak) | Rust + Tauri | 19,481 | Tauri + 自研协调器 |
| 13 | [Jan](https://github.com/janhq/jan) | Rust + Tauri / React | 45,668 | 自有检查接口 + Tauri 安装 |
| 14 | [Rnote](https://github.com/flxzt/rnote) | GTK / Rust | 11,854 | Flathub / winget / 手动包 |
| 15 | [Czkawka / Krokiet](https://github.com/qarmin/czkawka) | Rust + GTK / Slint | 35,073 | Release 预编译包 / 源码安装 |
| 16 | [EasyTier GUI](https://github.com/EasyTier/EasyTier) | Rust + Tauri / Vue | 14,677 | Release / 外部安装管理 |
| 17 | [HuLa](https://github.com/HuLaSpark/HuLa) | Rust + Tauri / Vue | 7,836 | Tauri + 更新窗口 |

## 3. 逐项证据与适用边界

### 1. Zed

官方说明默认后台获取更新、下次重启应用；源码分别处理 Windows 安装助手与 Linux tar 包/rsync。可关闭自动更新。

对本项目的启示：将下载与重启分离；后台任务不能打断正在编辑的内容。它的自研安装助手不适合本项目照搬。

证据：[官方说明](https://zed.dev/docs/update)；[crates/auto_update/src/auto_update.rs](https://github.com/zed-industries/zed/blob/82f2558e17f6459f2cc8cb4a9fbe05e36520cdd9/crates/auto_update/src/auto_update.rs)；[crates/auto_update_helper/src/updater.rs](https://github.com/zed-industries/zed/blob/82f2558e17f6459f2cc8cb4a9fbe05e36520cdd9/crates/auto_update_helper/src/updater.rs)。源码快照 `82f2558e17f6`。

### 2. Lapce

源码从 GitHub latest/nightly 读取资产，按 OS/架构下载；Windows 区分 portable ZIP 与 MSI，Linux 解包替换可执行文件。

对本项目的启示：便携版和安装版需要不同路径；源码的覆盖与备份逻辑不能当作完整事务回滚保证。

证据：[lapce-app/src/update.rs](https://github.com/lapce/lapce/blob/f66ffaa95387794205c35864517de70958b9ea8d/lapce-app/src/update.rs)；[docs/new-release.md](https://github.com/lapce/lapce/blob/f66ffaa95387794205c35864517de70958b9ea8d/docs/new-release.md)。源码快照 `f66ffaa95387`。

### 3. Alacritty

官方 README 将安装指向平台包管理器、预编译包和源码构建；所查仓库目录未见应用内下载/安装更新模块。此处列为外部管理的对照样本。

对本项目的启示：Rust GUI 不必自带更新器；使用外部包管理器的安装应遵守其所有权。图形终端计入 GUI，纯 TUI 不计入。

证据：[官方说明](https://github.com/alacritty/alacritty/blob/master/INSTALL.md)；[README.md](https://github.com/alacritty/alacritty/blob/aa5d942f46c40184a3f214830ff5f7989f4733a1/README.md)；[alacritty/Cargo.toml](https://github.com/alacritty/alacritty/blob/aa5d942f46c40184a3f214830ff5f7989f4733a1/alacritty/Cargo.toml)。源码快照 `aa5d942f46c4`。

### 4. WezTerm

官方配置说明默认约每 24 小时检查 GitHub 稳定版并通知，明确不自动下载；发行版维护文档允许发行包默认关闭应用检查。

对本项目的启示：检查、下载、安装是三个能力；发行版安装不应出现两个互相竞争的更新器。

证据：[官方说明](https://wezterm.org/config/lua/config/check_for_updates.html)；[docs/config/lua/config/check_for_updates.md](https://github.com/wezterm/wezterm/blob/458a100c1bf4134d36cb5c42dd90e4c8266d9e1a/docs/config/lua/config/check_for_updates.md)；[README-DISTRO-MAINTAINER.md](https://github.com/wezterm/wezterm/blob/458a100c1bf4134d36cb5c42dd90e4c8266d9e1a/README-DISTRO-MAINTAINER.md)。源码快照 `458a100c1bf4`。

### 5. Warp

官方更新文档与源码显示：Windows 下载 Inno Setup 安装器；Linux 检测 AppImage 或 APT/DNF/pacman，采取不同更新路径。自编译 OSS 构建不自动取得生产发行身份。

对本项目的启示：安装渠道决定更新策略。包管理器操作涉及用户权限，不能把 AppImage 自替换等同于 DEB 更新。

证据：[官方说明](https://docs.warp.dev/support-and-community/troubleshooting-and-support/updating-warp)；[app/src/autoupdate/windows.rs](https://github.com/warpdotdev/warp/blob/2b2ed236f087b47b463f1cd55598485dd7965338/app/src/autoupdate/windows.rs)；[app/src/autoupdate/linux.rs](https://github.com/warpdotdev/warp/blob/2b2ed236f087b47b463f1cd55598485dd7965338/app/src/autoupdate/linux.rs)。源码快照 `2b2ed236f087`。

### 6. RustDesk

当前源码有定时/手动检查及自动更新选项；更新前后确认无活跃远程会话，校验签名 sidecar 元数据，再按大小与 SHA-256 验证资产。代码按平台/安装格式分支。

对本项目的启示：借鉴业务空闲屏障与签名元数据；本次证据聚焦桌面路径，不能据此认定 Android APK 能静默升级，也不混同定制客户端的商业功能。

证据：[官方说明](https://rustdesk.com/docs/en/client/)；[src/updater.rs](https://github.com/rustdesk/rustdesk/blob/0bfe22083df9c6319d917849ab72c1efb3920d7e/src/updater.rs)；[src/updater/artifact.rs](https://github.com/rustdesk/rustdesk/blob/0bfe22083df9c6319d917849ab72c1efb3920d7e/src/updater/artifact.rs)；[src/update_metadata.rs](https://github.com/rustdesk/rustdesk/blob/0bfe22083df9c6319d917849ab72c1efb3920d7e/src/update_metadata.rs)。源码快照 `0bfe22083df9`。

### 7. GitButler

当前 desktop 后端仍接入 Tauri updater，更新服务管理下载/安装状态；tauriManualInstall 明确将 DEB/RPM 标记为手动安装，另有官方下载页。

对本项目的启示：对本项目最直接的 Linux 参照：同一软件的 AppImage 与 DEB 不能承诺相同安装自动化。只描述已检查快照，不推断未来框架迁移。

证据：[apps/desktop/src/lib/updater/updater.ts](https://github.com/gitbutlerapp/gitbutler/blob/cb400035d9558ed7a48f22f34a9fb05a0b927926/apps/desktop/src/lib/updater/updater.ts)；[apps/desktop/src/lib/backend/tauri.ts](https://github.com/gitbutlerapp/gitbutler/blob/cb400035d9558ed7a48f22f34a9fb05a0b927926/apps/desktop/src/lib/backend/tauri.ts)；[crates/gitbutler-tauri/tauri.conf.release.json](https://github.com/gitbutlerapp/gitbutler/blob/cb400035d9558ed7a48f22f34a9fb05a0b927926/crates/gitbutler-tauri/tauri.conf.release.json)。源码快照 `cb400035d955`。

### 8. Spacedrive

Tauri 配置启用 updater artifacts、内嵌公钥、GitHub latest.json；hook 将 download 与 restartAndInstall 分开，展示下载进度。

对本项目的启示：与当前桌面技术栈接近，可借鉴分阶段 UI；配置和开发文档的存在不代表每个公开平台资产均已验收。

证据：[apps/tauri/src-tauri/tauri.conf.json](https://github.com/spacedriveapp/spacedrive/blob/11523b224d1422d98def9a1c0f99e89fafada00d/apps/tauri/src-tauri/tauri.conf.json)；[packages/interface/src/hooks/useAutoUpdater.ts](https://github.com/spacedriveapp/spacedrive/blob/11523b224d1422d98def9a1c0f99e89fafada00d/packages/interface/src/hooks/useAutoUpdater.ts)；[apps/tauri/AUTOUPDATER.md](https://github.com/spacedriveapp/spacedrive/blob/11523b224d1422d98def9a1c0f99e89fafada00d/apps/tauri/AUTOUPDATER.md)。源码快照 `11523b224d14`。

### 9. Clash Verge Rev

配置有多个元数据端点与 passive Windows 安装模式；Rust SilentUpdater 管理下载缓存，使用 update.verify 重验缓存，启动时询问是否安装。

对本项目的启示：缓存只能代表已下载；每次安装要重新绑定版本并校验。其第三方代理做法只作观察，本项目不默认信任这些代理。

证据：[src-tauri/tauri.conf.json](https://github.com/clash-verge-rev/clash-verge-rev/blob/2c60f55f45c51200fbfa0444779e46348fdd3365/src-tauri/tauri.conf.json)；[src-tauri/src/core/updater.rs](https://github.com/clash-verge-rev/clash-verge-rev/blob/2c60f55f45c51200fbfa0444779e46348fdd3365/src-tauri/src/core/updater.rs)。源码快照 `2c60f55f45c5`。

### 10. Pot

Rust 侧按偏好检查，有更新打开独立窗口；前端 checkUpdate/installUpdate 与下载进度事件；配置使用下载域名与 GitHub 回退。

对本项目的启示：成熟交互可借鉴，但 Tauri 1 API 和旧格式不能直接用于本项目的 Tauri 2。

证据：[src-tauri/src/updater.rs](https://github.com/pot-app/pot-desktop/blob/594d32ede96acd106b0256deaa8bb440ffcdff40/src-tauri/src/updater.rs)；[src/window/Updater/index.jsx](https://github.com/pot-app/pot-desktop/blob/594d32ede96acd106b0256deaa8bb440ffcdff40/src/window/Updater/index.jsx)；[src-tauri/tauri.conf.json](https://github.com/pot-app/pot-desktop/blob/594d32ede96acd106b0256deaa8bb440ffcdff40/src-tauri/tauri.conf.json)。源码快照 `594d32ede96a`。

### 11. Hoppscotch Desktop

Desktop 有 Rust 检查/下载/安装服务，向前端发送有类型的进度和错误事件，Tauri 配置生成签名更新资产。

对本项目的启示：将更新器放在原生层、UI 只展示状态；本项只计 Desktop，不把同仓 Web 与 Agent 再计成两个软件。

证据：[packages/hoppscotch-desktop/src-tauri/src/updater.rs](https://github.com/hoppscotch/hoppscotch/blob/55908a76a1d0555c9bbb5d74244c14173df95b06/packages/hoppscotch-desktop/src-tauri/src/updater.rs)；[packages/hoppscotch-desktop/src/services/updater.client.ts](https://github.com/hoppscotch/hoppscotch/blob/55908a76a1d0555c9bbb5d74244c14173df95b06/packages/hoppscotch-desktop/src/services/updater.client.ts)；[packages/hoppscotch-desktop/src-tauri/tauri.conf.json](https://github.com/hoppscotch/hoppscotch/blob/55908a76a1d0555c9bbb5d74244c14173df95b06/packages/hoppscotch-desktop/src-tauri/tauri.conf.json)。源码快照 `55908a76a1d0`。

### 12. Yaak

yaak-updater 封装插件，以 gate 限制一个检查/安装任务；按安装方式区分 Integrated、Flatpak、Manual，支持后台先下载再提示。

对本项目的启示：借鉴任务互斥与安装方式枚举；“更新已安装等待重启”应是独立状态，不能反复下载同一版本。

证据：[crates-tauri/yaak-updater/src/lib.rs](https://github.com/mountain-loop/yaak/blob/6e39955f1731b1315f18855343cc34b8de8ff26d/crates-tauri/yaak-updater/src/lib.rs)；[crates-tauri/yaak-updater/src/gate.rs](https://github.com/mountain-loop/yaak/blob/6e39955f1731b1315f18855343cc34b8de8ff26d/crates-tauri/yaak-updater/src/gate.rs)。源码快照 `6e39955f1731`。

### 13. Jan

自定义检查服务有请求签名与备用端点，安装服务最终使用 Tauri 验包；代码先下载并校验成功，随后停止引擎/子进程，再安装。

对本项目的启示：请求 HMAC 不等于发行物签名；失败下载不能提前关闭笔记核心。所查开发快照含功能开关引用，不将其等同于所有正式构建已启用。

证据：[src-tauri/src/core/updater/custom_updater.rs](https://github.com/janhq/jan/blob/171fdc77eba27909ff458d9822a4d902f1c023da/src-tauri/src/core/updater/custom_updater.rs)；[web-app/src/services/updater/tauri.ts](https://github.com/janhq/jan/blob/171fdc77eba27909ff458d9822a4d902f1c023da/web-app/src/services/updater/tauri.ts)；[src-tauri/tauri.conf.json](https://github.com/janhq/jan/blob/171fdc77eba27909ff458d9822a4d902f1c023da/src-tauri/tauri.conf.json)。源码快照 `171fdc77eba2`。

### 14. Rnote

官方 README 推荐 Linux Flathub，Windows 提供安装器和 winget，并说明 Flatpak 更新/降级方式；所查入口未见自有后台安装链路。

对本项目的启示：系统分发渠道能承担自动更新，但是否后台执行由系统策略决定；应用不能绕过包管理器改写其文件。

证据：[官方说明](https://docs.flatpak.org/en/latest/using-flatpak.html#updating)；[README.md](https://github.com/flxzt/rnote/blob/e507fcea95368d221cb68322b4c9bf97a8bf9205/README.md)；[Cargo.toml](https://github.com/flxzt/rnote/blob/e507fcea95368d221cb68322b4c9bf97a8bf9205/Cargo.toml)。源码快照 `e507fcea9536`。

### 15. Czkawka / Krokiet

Krokiet 官方 README 指向同仓 Releases 的多平台预编译包及 cargo 安装；所查 GUI 源码目录与 about 入口未见完整的应用内下载/安装器。

对本项目的启示：作为手动更新对照；Czkawka 与 Krokiet 是同一产品家族，仅计一项。没有检出模块不代表证明所有第三方发行版均无检查通知。

证据：[krokiet/README.md](https://github.com/qarmin/czkawka/blob/969617bbb6504f9c7a1699b4a576ea2c494e2e46/krokiet/README.md)；[czkawka_gui/src/connect_things/connect_about_buttons.rs](https://github.com/qarmin/czkawka/blob/969617bbb6504f9c7a1699b4a576ea2c494e2e46/czkawka_gui/src/connect_things/connect_about_buttons.rs)。源码快照 `969617bbb650`。

### 16. EasyTier GUI

官方 README 提供 Releases 与 GUI 安装入口；GUI tauri.conf.json 未配置 updater 且 createUpdaterArtifacts=false，About 页只显示版本。

对本项目的启示：网络核心、Android 第三方壳、服务安装脚本的升级不等于本仓 GUI 的自动更新；此项列为未内置的对照。

证据：[README.md](https://github.com/EasyTier/EasyTier/blob/2abb1676b475947e46c527b57acc5f296cb48482/README.md)；[easytier-gui/src-tauri/tauri.conf.json](https://github.com/EasyTier/EasyTier/blob/2abb1676b475947e46c527b57acc5f296cb48482/easytier-gui/src-tauri/tauri.conf.json)；[easytier-gui/src/components/About.vue](https://github.com/EasyTier/EasyTier/blob/2abb1676b475947e46c527b57acc5f296cb48482/easytier-gui/src/components/About.vue)。源码快照 `2abb1676b475`。

### 17. HuLa

桌面 hook 检查更新，配置多个端点/公钥，更新窗口调用 downloadAndInstall 并展示进度；代码还区分普通提示与版本级别的强提示。

对本项目的启示：本项目借鉴进度与稍后处理，不采用字符串版本比较和强制退出策略。仓库含 Android 工程不代表桌面 updater 插件支持 Android。

证据：[src/hooks/useCheckUpdate.ts](https://github.com/HuLaSpark/HuLa/blob/16871546d8f42252ed8b475540b0f74f1ea44fd6/src/hooks/useCheckUpdate.ts)；[src/views/Update.vue](https://github.com/HuLaSpark/HuLa/blob/16871546d8f42252ed8b475540b0f74f1ea44fd6/src/views/Update.vue)；[src-tauri/tauri.conf.json](https://github.com/HuLaSpark/HuLa/blob/16871546d8f42252ed8b475540b0f74f1ea44fd6/src-tauri/tauri.conf.json)。源码快照 `16871546d8f4`。

## 4. 跨样本结论

以下是本次调研的归纳和本项目的取舍，不是上游统一承诺：

1. Tauri 项目普遍复用官方安装/签名能力，在它之上补状态机、缓存、权限与业务退出屏障；没有必要重写完整的 Windows 安装器。
2. Linux 的关键是安装渠道。GitButler、Yaak、Warp 都在代码中区分渠道；AppImage 自更新、DEB/RPM 系统管理、Flatpak 更新不是一种操作。
3. WezTerm、Alacritty、Rnote 等说明“成熟软件”并不必然选择应用内静默安装；必须说明承诺究竟到哪一步。
4. RustDesk、Jan 的业务空闲/关闭过程，以及 Yaak 的任务互斥，说明更新器必须融入应用生命周期。对备忘录尤其要先保全输入法组合文本、编辑缓冲、事务和离线 outbox。
5. GitHub Release 资产、SHA256SUMS、包签名、操作系统签名、签名元数据承担不同职责；不能用公开下载链接或哈希文件替代真实性验证。
6. 样本多数聚焦桌面，不能用它们推断本项目原生 Compose Android 的实现。Android 的边界另以官方系统 API 核实。

## 5. 平台依据与本项目建议

| 平台 | 已核实的官方依据 | 本项目建议 |
|---|---|---|
| Windows | [Tauri Updater](https://v2.tauri.app/plugin/updater/)：支持 NSIS/MSI、要求更新资产签名 | 复用 currentUser NSIS；后台下载，保存后由用户确认重启 |
| Linux | 同一官方说明的 Linux 更新资产为 AppImage；[Flatpak](https://docs.flatpak.org/en/latest/using-flatpak.html#updating) 通过自身渠道更新 | 新增 AppImage 自更新；保留 DEB 为外部管理，后续若要求 DEB 自动安装再建设签名 APT 仓库 |
| Android | [Play In-App Updates](https://developer.android.com/guide/playcore/in-app-updates) 属于 Play 分发；[PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)) 明确用户操作与特定免确认条件 | GitHub APK 使用原生安装会话；首版显式要求系统确认，不承诺所有设备无感安装 |

[Tauri 2.14.1 固定源码](https://github.com/tauri-apps/plugins-workspace/blob/updater-v2.14.1/plugins/updater/src/updater.rs)还提供 `restore_update`、包验证和绑定版本的签名校验。本次实际执行本机已有 CLI 的 `signer sign --help`，确认支持 `--app-version`；没有生成或使用真实私钥，也没有进行签名安装试验。

后续设计见[自动更新方案](../automatic-update-design.md)、[提案规范](../../spec/automatic-updates.md)与[ADR-0016](../adr/0016-automatic-updates.md)。

## 6. 可复核性与许可

[证据清单](rust-gui-auto-update-sources.json)保存所读文件的仓库、提交、官方永久链接、字节数及 SHA-256；摘要便于后续复核取样，不是上游发行签名或安全认证。星数与默认分支元数据来自 GitHub 官方 API 的本次快照。原始源码下载仅留在本机忽略目录，不进入仓库。

本文为原创分析与链接索引，没有复制上游实现、公钥、AccessKey 或签名私钥；GPL/AGPL 等项目的更新器仅作行为参考，不移植进本项目的 MIT 源码。
