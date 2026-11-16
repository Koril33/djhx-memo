# PR #27 调研报告事实核验

- 日期：2026-11-16。
- 被评审提交：`97743e0fe04ef7a28432ef8b40091759cd63bd25`；已通过 PR #27 合入 `8e4f46e8a939001c27ac15f562e550eae62ca8e1`。
- 原报告：[0.1.10 安全、架构与 UI/UX 综合调研](quality-audit-0.1.10.md)。本记录评估其准确性，不改写原始审计记录，不修改产品行为或 spec。
- 对照源码：最新 main `8e4f46e`；与报告注明的 `6e5373e` 比较，core/server/Android/desktop 的产品源码无差异，两个模块 README 有后续发布说明变化。
- 本轮平台：Windows 原生 Rust 1.99.1/MSVC、Go 1.27.3、Node 22；另建 API35 x86_64 模拟器运行隔离 Android 包。没有使用真实账号、笔记或生产服务。

后续说明（2026-11-16）：维护者现已要求修正原报告并讨论下一版。[原报告](quality-audit-0.1.10.md)已依据本文更正；本文保留核验时的发现与测量，以下“原报告第 N 行”均指提交 `97743e0` 的原始版本，不是更正后的行号。新增讨论见[下一版客户端讨论稿](../client-next-discussion.md)，本轮后续修订未重跑这些产品测试。

## 结论

报告有参考价值，但不能将全部缺陷和执行记录直接当作已核实事实。**M2 大笔记卡顿属实，已独立复现；M1 的永久错误恢复性担忧部分成立，但所列 NOTE_PURGED 场景及“无任何解除路径”的结论遗漏现有处理。** 测试命令、架构职责、规范引用和若干绝对化措辞也需修正。

## 需要修正的主要内容

### 1. P1：M1 遗漏永久删除的收敛路径，修复建议必须按错误类型区分

原报告第 37–46 行声称，另一设备永久删除后，本设备旧编辑收到 `NOTE_PURGED`，会永久 blocked，继而永久卡在 purge pending；第 45 行建议按 shadow 提高基准并重新冻结新 op。

这段推导没有覆盖完整同步轮次：

- [sync.rs](../../core/src/sync.rs) 的 `sync_round` 在发送前先 `pull`，处理完 push/purge 后再次 `pull`。已经发生的远端永久删除通常在首次 pull 即被安装。
- [db.rs](../../core/src/db.rs) 的 `apply_page` 对 `wire.purged` 调用 [purge.rs](../../core/src/purge.rs) 的 `install`。后者明确删除该笔记的 **outbox（含 blocked 行）与 purge_requests**，并写入正文为空、代次已确认的 `purged` 状态。
- 如果永久删除恰好发生在首次 pull 与 push 之间，单项 `NOTE_PURGED` 可暂时设置 blocked，但末尾 pull 仍有上述清理路径。若该次 pull 网络失败，后续手动同步成功拉取标记也可收敛，不能称为永久死锁。

本轮两类证据：

1. 既有真实 Go/Rust `permanent_delete_http_conformance` 通过，包括另一端永久删除后提交旧编辑的场景。
2. 隔离源码副本中的探针显式执行 `freeze → acknowledge(NOTE_PURGED, retryable=false) → purge::request`，确认中间确有 blocked/pending；再 `apply_page(永久删除标记)`，确认正文清空、`purge_state=purged`，outbox 与 purge_requests 总行数为 0。探针通过。

另一个探针注入 `INVALID_REQUEST` 永久错误，随后仅拉取空页，确实仍然 blocked，申请 purge 后也无法由 `freeze/next` 推进。这支持较窄的结论：**未获得远端永久删除标记、且原操作遭永久拒绝时，当前缺少明确的用户修复流程，purge 可能长期 pending。** 该探针不是正常客户端能产生这一服务端响应的完整复现，触发条件仍须补证。[同步规范](../../spec/sync-protocol.md)本身也要求永久错误停止自动重试、保留内容并提示处理。

修复建议不能将 `NOTE_PURGED` 与其他错误统一重发。[永久删除规范](../../spec/permanent-deletion.md)规定原 ID 永不复用、永久删除优先于旧 outbox，旧编辑不得复活或自动生成冲突副本。一般 stale edit 的 `draft_base_rev` 也不能仅因新 shadow 到达而提高。后续如设计人工救援/重试，应分别定义错误类型、原 ID 的最终状态和用户确认语义，先改 spec 并独立评审。

### 2. P2：测试表中命令实际运行 0 项，不能支撑 49 passed

原报告第 117 行的 `cargo test --locked core` 将 `core` 作为测试名称过滤器。

本轮在 `core/` 对应的 manifest 上原样增加该过滤器执行：库测试 **0 passed，49 filtered out**；联调 **0 passed，10 filtered out**。若直接从仓库根目录照抄，因根目录无 Cargo.toml 还会失败。

正确的根目录命令为：

```powershell
cargo test --locked --manifest-path core/Cargo.toml
```

本轮该命令得到 **49 passed**；10 项联调默认 ignored。真实联调必须另外显式启用。测试数量确实存在且本轮通过，但原命令与“当时执行通过”的记录不相符，应纠正命令并补原始输出，而不能将本轮结果回填为 11 月 15 日的证据。

### 3. P2：三套完整 Markdown 渲染的描述不符实现

原报告第 91 行将 `core/src/highlight.rs` 列为第三套 Markdown 渲染器。实际它用 pulldown-cmark 定位代码块，用 syntect 生成高亮 span，向 Android/桌面提供共享结果；超过 128 KiB 时降级为空高亮。它不渲染完整文档，不承担 HTML、图片和链接安全策略。

应描述为“两套平台 Markdown 渲染 + 共享代码高亮服务”。两端解析器/任务标记和链接规则仍有一致性维护成本，但不能据此认定核心库重复实现了整套渲染与安全策略，或直接推动暂缓的共享 Web 前端。

### 4. P2：规范引用和绝对化描述需收窄

- 第 71 行称 `spec/data-model.md` 声明 u64，原文实际是“非负 64 位整数”，没有 u64 声明；core 使用 `i64`、server 使用 `ParseInt(..., 64)`。可以建议明确最大值，但不能将当前文本直接记作 i64/u64 契约冲突。
- 第 33 行“全仓无……邀请码……输出”不成立：`server/cmd/memo-server/main.go` 的 `invite-create` 按设计向管理员标准输出一次性邀请码。应限定为应用日志、错误和 UI 不泄漏秘密，排除明确授权的管理命令输出；不要为符合此措辞删除正常管理功能。
- 第 84 行“无生产路径 panic”过强：`core/src/update_catalog.rs` 解析内嵌信任配置和 `highlight.rs` 创建静态 scope 时使用 `expect`。这不是已证实的外部可利用崩溃，但应说明这些内部静态不变量，不能将其写成完全没有 panic 路径。
- 第 66 行的 FAT32/exFAT 硬链接限制有依据，但“SMB 不可用”不能一概而论，取决于共享服务端和文件系统。候选回退方案也须保证不覆盖已有文件；Rust 的通用 rename 本身不提供跨平台的“不覆盖”承诺。

## M2 的独立 Android 实测

本轮新建 `codex_pr27_api35`，Android 15/API35 x86_64、4 核、3072 MiB、SwiftShader；应用为本轮 Kotlin 源码构建的 `0.1.10-debug` 隔离包 `app.djhx.memo.validation014`，沿用本机已有 JNI 产物。没有覆盖原 AVD 或普通 debug/release 应用。

通过真实 `import_markdown` 公共桥接导入合成文件，然后通过 `MemoModel.open` 打开实际 `MainActivity` 的 SourceEditor，未切换预览。fixture 为以下 20,000 行，UTF-8 总计 960,000 字节（937.5 KiB）：

```text
对 i=1..20000："# Heading " + i 的五位十进制表示 + " " + 31 个 x + "\n"
```

| 指标 | 单次观测 |
|---|---:|
| 导入公共桥接调用 | 228 ms |
| 打开至界面空闲 | 135,561 ms |
| 主线程 50 ms 心跳的最大间隔 | 131,692 ms |
| 中途 top 的应用 CPU 样本 | 138% |
| 同期进程内存样本 | PSS 168,541 KiB；RSS 304,224 KiB |
| 仪器测试 | 1 passed，正文精确相等，最终恢复并完成合成笔记回收 |

直接执行的入口与结果摘录（构建和安装在先）：

```powershell
adb -s emulator-5580 shell am instrument -w -r -e class app.djhx.memo.Pr27LargeNoteTest app.djhx.memo.validation014.test/androidx.test.runner.AndroidJUnitRunner
```

```text
PR27 imported bytes=960000 lines=20000 elapsedMs=228
PR27 opened elapsedMs=135561 maxMainGapMs=131692
Time: 140.326
OK (1 test)
```

完整输出本机路径为 `.local/pr27-verification/android-large-note.txt`，SHA256 为 `222b9185e5eb73ff67d5b934974a145bd48cf9cbdb66b0c69dcfb16a9b901f35`；探针源码为同目录 `Pr27LargeNoteTest.kt`。这两份临时资料没有进入发行包或本次 Git 提交。

这足以确认**允许大小内的大笔记存在严重 UI 停顿，且仅源码页即可发生**。`SourceEditor.highlight` 在主线程全量正则匹配、创建大量 span，以及非虚拟化预览，均有源码依据。未做性能剖析，本轮不将耗时按这些因素分摊，也不宣称已经测试预览或每次按键。

原报告的 API36、932 KiB、40–60 秒、CPU/内存区间没有提供原始 fixture、APK 哈希、采样输出或时间界定。本轮结果支持问题类别，不验证那些历史数值；本轮 debug/API35 的数字也不能当作 release/API36 的替代值或 P95。没有通过注入用户输入触发系统 ANR，因此不从本轮结果推断系统一定弹出或不弹出 ANR。

## 其他断言的核对边界

| 原断言 | 本轮判定 |
|---|---|
| Argon2id、dummy hash、token hash、事务内鉴权复验、更新验签、桌面 Markdown 转义/链接约束 | 相关实现存在，支持具体机制描述；不据此给出全面安全保证或“高于个人项目平均水平”的比较结论 |
| L1 非法本地 cursor 不触发保全 | 有静态依据：counter 返回 INVALID_REQUEST，恢复分支匹配 CURSOR_INVALID/SYNC_EPOCH_MISMATCH；未注入损坏数据库做端到端测试 |
| L2 target 侧 redirect 未删 | SQL 描述属实；已有 `redirects_cannot_bypass_target_deletion_barrier` 通过，写入目标仍检查 pending/purged；不能把残留映射等同于可复活 |
| L3 core_invoke 没有独立动作白名单 | 透传属实；core dispatch 已枚举允许动作，未知动作返回 INVALID_REQUEST。报告的新增外壳白名单属于纵深建议，尚未证明可调用任意核心外能力 |
| L4 rescue 未检查 composing | 局部代码描述属实；真实 IME 点击/失焦/事件顺序未测，保留为待复现风险 |
| L6 单篇失败不清理、ZIP 失败尽力清理 | 有代码依据；是否残留及残留内容还依赖 SAF provider 的失败方式，本轮未注入半写入故障 |
| confirmation_pending 只写不读 | 静态检索属实；恢复仍读取 session/compatibility 状态并支持取消，不能泛化为整个更新恢复缺失 |
| 大组件行数、模块版本、暂缓 CI/Web、v1/七天试用未完成 | 与仓库相符；保持既有验收边界 |
| Android 22 个仪器测试 | 当前有 22 个测试类、60 个 @Test 方法；建议明确计数口径及实际执行/跳过数量 |
| Windows 安装、API36 安装/导出、10 项联调与 SSE p95≈610ms、分路独立审查 | 原报告未附可追溯执行产物；本轮未重演这些历史流程，标记为未独立证实，不断言没有发生 |

## 本轮执行与交付

| 检查 | 结果 |
|---|---|
| `cargo test --locked --manifest-path core/Cargo.toml core` | 0 项执行；49 单元与 10 联调被过滤，证实报告命令问题 |
| `cargo test --locked --manifest-path core/Cargo.toml` | 49 passed；10 ignored，未将 ignored 当作联调通过 |
| Windows README 的 Go 显式文件集，设 `DJHX_CORE_PURGE_INTEGRATION=1` | 4 个 Go 测试通过（含调用 1 项 Rust 真实 HTTP 联调）；Android HTTP 测试未设设备参数，1 项跳过 |
| core 源码副本的两项 blocked/purge 探针 | 2 passed；仅增加探针，未修改原有测试及产品实现 |
| `npm.cmd --prefix desktop test` | 6 个文件、34 项通过 |
| Gradle 离线 `-PisolatedValidation assembleDebug assembleDebugAndroidTest`，直接 adb instrumentation | 构建通过；上述 Android 性能/正文断言测试 1 项通过 |
| `python scripts/check-docs.py`、`git diff --check` | 通过 |

源码副本、临时仪器测试、构建及观测输出保留在本机 Git 忽略目录 `.local/pr27-verification/`；它们不是原报告的历史执行凭据。临时测试源已从工程移除；新建模拟器已关闭、AVD 注册已移除，`C:/Users/ABC/.android/avd/codex_pr27_api35.avd` 残留目录因自动审批策略拒绝清理而保留（仅返回策略拦截，未给出具体原因）。本轮启动的 adb 服务已停止，没有改动原设备、显示/输入设置或用户数据。工具输出 APK 为隔离 debug 产物，不是新发行包。

未运行全量 Linux 服务端 `go test -race ./...`、完整 10 项 CLI 联调、Windows VM 交互、arm64/OEM 真机和完整设备矩阵；Windows 显式文件集不覆盖 Unix epoch/CLI。未复做整体漏洞审计，也不将“本轮未发现”扩展为没有漏洞。

建议后续开发先处理已复现的大笔记可用性问题；M1 先补充实际可达的其他永久错误场景并设计恢复契约。原报告应同步纠正上述事实及证据表述，避免后续按错误场景重做同步或提高旧编辑基准。
