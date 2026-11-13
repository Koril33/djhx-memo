# 0.1.5 更新器平台独立交叉评审

- 日期：2026-11-13。
- 授权：维护者明确允许本轮调用独立子代理审查 PR #10 的并发、保存和平台安装逻辑；本记录由未编写原始更新器及本轮修复的独立平台评审协作者整理。
- 基线：更新器提交 `9d52aea`；对比已发布 0.1.4 的主分支 `1c9a102`。本轮整合分支为 `codex/0.1.5-release-integration`，已包含文档整合提交 `31f7874`；修复复核对象为该整合分支工作树中的平台代码，最终交付提交由整合者记录。
- 行为依据：[自动更新规范](../../spec/automatic-updates.md)、[发布工具与签名边界](../update-publishing.md)。原实现检查记录见 [0.1.5 验证](../client-updates-0.1.5-validation.md)，不将其中历史结果当作本评审者本轮重新执行的结果。

## 范围与方法

本次只读审查下列实现及相关调用链，没有读取签名私钥、维护者账号或生产笔记；除本记录外未修改文件，未提交代码。

| 范围 | 审查内容 |
|---|---|
| Windows 原生 | [update.rs](../../desktop/src-tauri/src/update.rs)、[main.rs](../../desktop/src-tauri/src/main.rs)：固定更新意图、候选绑定、PE 版本、安装屏障、生命周期和失败恢复 |
| Windows 前端 | [App.svelte](../../desktop/src/App.svelte)、[Updates.svelte](../../desktop/src/Updates.svelte)、[EditorSession](../../desktop/src/lib/editor-session.ts)：输入缓冲、组合输入、保存回执、安装期间锁定和异常返回 |
| Android | [AppUpdates](../../android/app/src/main/java/app/djhx/memo/AppUpdates.kt)、[UpdateInstaller](../../android/app/src/main/java/app/djhx/memo/UpdateInstaller.kt)、[UpdateSettings](../../android/app/src/main/java/app/djhx/memo/UpdateSettings.kt)、[MemoModel](../../android/app/src/main/java/app/djhx/memo/MemoModel.kt)、[CoreGateway](../../android/app/src/main/java/app/djhx/memo/CoreGateway.kt)、[MainActivity](../../android/app/src/main/java/app/djhx/memo/MainActivity.kt)：身份预检、系统会话、取消、回调、进程恢复与前后台状态 |
| 依赖与测试代码 | 精确锁定的 `tauri-plugin-updater 2.14.1` 本机 registry 源码；[UpdatePlatformTest](../../android/app/src/androidTest/java/app/djhx/memo/UpdatePlatformTest.kt) 的现有断言和新增恢复回归 |

没有运行 cargo、Gradle、Windows 安装器或 Android 仪器测试；构建与真实目标检查由整合者另行执行并记录。本评审不代替共享 Rust 目录验证器和 core 并发入口的独立审查。

## 发现及修复复核

### P1：未提交的 Android 安装会话可使笔记永久锁定

原 `UpdateInstaller.submit()` 先将 session ID 持久化到私有 journal，再调用系统 `session.commit()`。若进程在这两步之间被结束，重启时 session 仍存在，但尚未提交，也不会产生用户确认回调。

原 `AppUpdates` 根据 journal 进入 `installing=true`，恢复检查只清理不存在的 session；同时取消入口依赖 `needsConfirmation`。这种遗留状态既不能自动结束，也没有取消按钮，使编辑和普通操作持续被锁定。对应原基线位置为 `UpdateInstaller.kt:74–77`、`AppUpdates.kt:41/63/184`。

本轮修复增加 `reconcileInstallation()`，在启动及重新前台时核对 session，清理不存在或尚未提交的会话；`submissionActive` 保护当前正在提交的尝试，避免正常提交间隙被误判为遗留状态。`canCancelInstallation` 让已记录且不在提交中的会话可以取消，不再要求确认 Intent 已收到。

新增回归使用真实 `PackageInstaller` 创建未提交 session，写入 journal 后重建控制器，断言解除锁定、清理 journal、放弃会话及 core 恢复可访问。**修复实现和测试断言已独立复核；实际仪器运行结果尚由整合者记录。**

### P2：解除安装锁后可能丢失正确的前后台状态

`CoreGateway` 初始化时主动将 core 设为后台。带安装 journal 重启时，`MemoModel.foreground()` 因安装锁跳过 `onStart`；原结束逻辑仅释放 core 屏障，没有恢复当前 Activity 的状态。因此取消安装后可编辑，但可能仍缺少正常前台同步和 SSE，直到再次切换前后台。

修复在 `finish()` 释放屏障后恢复前台状态，并在每次异步调用返回后重新核对当前 Activity 是否存在；若期间发生 `onStart/onStop` 则再次提交，状态一致后在同一主线程同步解除安装锁。`finishing` 防止重复结束回调和迟到释放相互干扰。**上述修复已独立复核。**

另复核了恢复回调：`receive()` 仅在系统回调的 session ID 与私有 journal 匹配后设置 `installing=true`，使控制器可接管有效会话。该调整保持会话身份校验和取消保护，未为测试放宽安装条件。

## API 兼容与其他结论

本机 Android SDK Platform 36 的 `data/api-versions.xml` 元数据已实际读取：`PackageInstaller$SessionInfo.isCommitted()Z` 的 `since=29`，`isSealed()Z` 的 `since=26`。当前项目 minSdk 为 29，因此修复使用 `isCommitted` 不需要额外版本分支；同时核对了 [Android 官方 API](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionInfo#isCommitted())。此前关于该方法需要更高系统版本的初步判断已更正，不作为问题保留。

- Windows：编辑缓冲提交成功后进入 core 安装屏障，再交给固定候选安装；返回错误路径恢复屏障和编辑状态。锁定的 Tauri updater 源码显示 `install(bytes)` 在执行安装器前再次验证签名，并检查 `ShellExecuteW` 的失败返回值。
- Android：包名、原签名证书、版本名、严格递增的 versionCode、实际 minSdk 和 ABI 预检，以及系统最终 APK 验签路径合理；安装状态 receiver 不导出。取消和失败路径经过本轮修复复核。
- 修复后的本次平台审查未发现其他确定的阻断项。此结论仅覆盖已列明的源码审查范围，不表示所有目标系统或安全边界已验收。

## 未覆盖与发布边界

- 未亲自执行本轮构建、原生界面、安装或进程中断试验，不预写检查通过。
- 原 Android 发布证书当前不可用；debug 与 unsigned release 不能证明正式签名连续性。原证书覆盖安装、arm64 真机、API29 和厂商系统确认/权限页面仍需实际检查。
- Windows 实际升级身份、文件占用、安装失败、杀毒拦截、组合输入和最终界面矩阵不因本评审自动通过。
- Linux 本轮只构建和验证 DEB，不据此认定 Linux 自动更新已实现；Linux 平台实际测试由整合者另记。
- 正式目录部署、GitHub Release 下载、真实完整更新链路、签名密钥备份及连续 7 天试用不属于本次源码评审的通过结果。
