# djhx-memo 项目计划

- 版本：v0.2，v1 实施基线
- 日期：2026-11-03
- 状态（2026-11-04）：0.1.0 自用候选实现与本地验收中；v1 未发布，7 天日常试用待完成
- 客户端增量（2026-11-08）：0.1.1 统一界面预览，服务端与 core 保持 0.1.0 行为；实际检查见[界面设计与验证入口](docs/ui-design.md)。
- 后续修正（2026-11-08）：0.1.2 验证并修正桌面原生下拉文字裁切及 Android 源码滚动，实际结果见[增量验证](docs/client-fixes-0.1.2-validation.md)。
- 本文替代 v0.1 草案；行为只在 [spec](spec/README.md) 中定义，历史草案可通过 Git 查看。

0.1.4 增量（2026-11-11）：维护者批准[单条彻底删除及桌面操作契约](spec/permanent-deletion.md)，先更新规范再实施；本轮只验证 Windows/Android，Linux 测试和打包交其他主机。独立同步/迁移评审另行完成。 后续发布轮的三端实际测试及已完成的独立评审见[0.1.4 发布验证](docs/releases/0.1.4-validation.md)，完整设备矩阵与 7 天试用状态保持不变。

## 1. 目标与范围

让项目负责人在 Windows、Android、Linux 上日常记录 Markdown 笔记，本地断网可用，联网自动同步到自有服务器。面向个人与少数受邀朋友，不做公开社区服务。

v1 必须交付：三端客户端、桌面托盘、本地保存、邀请码注册登录、云同步、Markdown 源码编辑与预览、任务勾选、搜索、冲突副本、回收站、设备吊销、本地导出，以及服务端部署和恢复说明。具体流程和边界见 [产品规范](spec/product-v1.md)。

暂缓：独立 Web 客户端、Apple 客户端、全局快捷键、系统分享、开机自启、附件、多人协作、端到端加密、自动墓碑清理、完整版本历史 UI、CI 与 Runner。独立 Web 暂缓不影响 Tauri 使用本地 WebView 前端。

自动升级（2026-11-11）已按维护者要求进入[三端方案设计](docs/automatic-update-design.md)，完成[17 个 Rust GUI 软件调研](docs/research/rust-gui-auto-update-survey.md)。2026-11-12 已形成 [0.1.5 Windows/Android 实现](spec/automatic-updates.md)及[本机验证](docs/client-updates-0.1.5-validation.md)，2026-11-13 已完成[公开发行及 Windows 目录部署](docs/releases/0.1.5-validation.md)，Android 原证书正式包待后补；Linux DEB 已构建，自动安装仍为后续范围。

下一版本（2026-11-13）：0.1.6 将加入[多选、左滑、普通删除撤销与单个 Markdown 导入](spec/client-0.1.6.md)。本轮只固化规范，不实施这些功能；Web 继续暂缓。

## 2. 已确定的技术方案

| 模块 | 方案 | 职责 |
|---|---|---|
| core | Rust + rusqlite + 随包 SQLite；UniFFI | 本地数据库、待发送操作、同步状态机、搜索、导出 |
| server | Go + SQLite WAL；SSE | 账号、设备凭据、权威版本、幂等、变化日志、通知 |
| Android | Kotlin + Jetpack Compose | 原生 UI、生命周期与后台任务适配 |
| Windows / Linux | Tauri 2 + Svelte + TypeScript + Vite；CodeMirror 6 | 编辑、预览、托盘、文件与窗口集成 |
| 协议 | HTTP JSON `/v1` + OpenAPI 3.1 | 跨语言行为契约 |
| 质量 | 本地自动检查 + 三端真机/系统验收记录 | 无远程 CI 依赖 |

前端代码在 `desktop/` 内组织，v1 不提前建立共享 Web 包。Go module 使用 `git.djhx.site/koril/djhx-memo/server`，Rust crate 使用 `djhx-memo-core`，Android application ID 使用 `app.djhx.memo`。

关键取舍及依赖引入流程见 [ADR-0001](docs/adr/0001-v1-baseline.md)。具体依赖版本在工程初始化时复核、锁定；尚未选定的底层实现库不阻塞已确定的产品行为。

## 3. 实施顺序与验收关口

| 阶段 | 交付内容 | 通过条件 |
|---|---|---|
| M0 文档与技术验证 | 本轮文档基线；下一轮完成契约评审、工具链、绑定/托盘小验证与本地检查入口 | 明确失败用例；Android 调 core 读写 SQLite；Windows/Linux 显示可恢复窗口的托盘；记录工具版本 |
| M1 本地垂直流程 | core 本地 CRUD、迁移、搜索、回收站、导出；桌面与 Android 最小 UI | 三端未登录即可编辑；正常重启保留已提交内容；保存失败不显示成功 |
| M2 账号与同步 | Go 服务端、邀请注册/登录、设备管理、变化日志、core outbox、SSE 与重试 | 一致性场景通过；越权隔离通过；本机笔记导入可恢复、可重复执行 |
| M3 三端体验完成 | Markdown 预览与任务勾选、冲突提示、Windows/Linux 托盘、Android 后台恢复 | 三端功能对齐；中文输入法、关闭/退出、离线与登录过期流程通过 |
| M4 自用候选版本 | 安装包、Debian 部署、异地备份、恢复演练、全链路测试 | Windows 安装包、Linux `.deb`、Android APK 可安装；三端互通；恢复流程验证 |
| M5 日常试用与 v1 发布 | 连续 7 天实际使用，修复影响记录和同步的问题 | [验收清单](docs/validation.md) 完成，无未解决的数据丢失、串账号或不可恢复保存故障 |

M1 与 M2 可在契约评审后按模块分工推进；先贯通本地保存，再联调同步。Windows 的早期调试顺序不降低 Android、Linux 的首版验收要求。不得把空测试框架通过作为同步实现验收。

## 4. 当前实施进展（2026-11-04）

Go 服务端、Rust 核心、Tauri 桌面与 Android 工程已经实现，提交锁文件和本地检查入口。Go 自动检查与本地 Debian 13 VM 部署/重启/恢复演练通过；核心单元与真实 Go 多库联调通过，独立评审发现的凭据竞态已修复。2026-11-05 恢复后已产出签名 Android APK、Linux deb 和 Windows 原生 NSIS 安装包，并完成模拟器/实际桌面的部分安装、编辑及重开验证；完整设备矩阵仍待验收。

实际证据以 [验证索引](docs/validation.md) 与各模块记录为准。M4 的三种安装包必须逐一安装；M5 的连续 7 天使用、物理 arm64 Android、未覆盖桌面输入法/系统组合仍不能提前通过。2026-11-07 按项目负责人授权完成 `memo.djhx.site` Debian 12 候选服务部署与公网 TLS/同步/SSE 检查，证据见 [云部署记录](docs/cloud-deployment.md)；异地副本按其选择定时拉取到本机，关机限制见 [备份说明](docs/backup-local.md)。

## 5. 最初实施建议（保留为阶段顺序）

1. 逐条评审 [同步协议](spec/sync-protocol.md) 和 [一致性场景](spec/conformance/scenarios.md)，重点检查并发编辑、删除和备份恢复。
2. 验证本机 Rust/Go/JDK/Android SDK/Node 工具链，建立最小 Cargo、Go、Android 与 Tauri 工程，记录锁文件。
3. 优先验证三个风险：UniFFI 在 Android 上的 SQLite 读写与事件回调；Linux 实际桌面的托盘与中文输入法；Windows 关闭到托盘及退出落盘。
4. 再实现一个完整本地流程：未登录创建笔记 → 本地提交 → 退出重开 → 搜索 → 删除恢复 → 导出。此流程稳定后接入账号和同步。

## 6. 决策与实施边界

v0.2 文档基线轮次仅完善文档；随后已完成本机开发环境安装、仓库外探针与业务实现，见 [Linux 开发说明](docs/dev-setup-linux.md) 和验证记录。规范中的“必须”仍是验收要求，代码存在不等于所有目标设备已通过。

后续以独立分支提交；无自动 CI 时仍需本地检查和交叉评审。同步、迁移、鉴权的高风险实现需独立评审后才能合并或投入真实数据使用。暂不需要项目负责人继续选择产品方案；实现中遇到与既定范围不兼容的限制时记录 ADR 并说明。
