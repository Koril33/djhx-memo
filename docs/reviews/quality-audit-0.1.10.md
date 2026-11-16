# 0.1.10 安全、架构与 UI/UX 综合调研报告

- 日期：2026-11-15
- 对象：`v0.1.10`（commit `6e5373e`）——客户端/core 0.1.10 品牌统一与单笔记导出；server 仍 0.1.4、协议 2 / schema 3 不变
- 性质：一次性调研/测试/分析，未改动任何代码与文档；本文供后续开发人员参考优化
- 方法：静态审查（server Go 全量 + core/Android/桌面 各一路独立评审 + 本人交叉）+ 本机动态测试（core/server/联调 + Android API36 模拟器 + Windows 10 VM 实机）

## 1. 结论总览

- **未发现 Critical/High 级可利用安全漏洞。** 安全工程水准明显高于个人项目平均水平（详见 §3.1）。
- 最值得修的是两项：
  - **M1 数据完整性缺陷**：outbox 一旦进入 `blocked` 状态无任何解除路径，笔记会「本机已保存但永久不同步」，并可能陷入彻底删除死锁。
  - **M2 性能缺陷（实测复现）**：约 1 MiB（2 万行）笔记的导入/打开在 Android 上造成约 40–60 秒 UI 冻结，CPU 约 150%，接近 ANR。
- 其余为 Low/Info 级的防御性与一致性改进项。
- 架构整体清晰、测试覆盖充分；相对最弱的是「异常路径可恢复性」与「UI 大文件分解」。
- UI/UX 视觉体系克制规范；0.1.10 由 paper-v1「绿纸」切换为 sunrise「橙渐变 + 阳墨随记」是一次品牌身份切换，存在收尾成本。

## 2. 目标一：漏洞与 BUG

### 2.1 安全面核查（正面结论）

| 边界 | 实现要点 | 结论 |
|---|---|---|
| 密码存储 | `server/auth.go` Argon2id，`passwordOK` 校验内存/迭代/线程上界，`subtle.ConstantTimeCompare` | 良好 |
| 防用户枚举 | 登录对不存在用户用 dummy hash，统一返回 `AUTH_INVALID_CREDENTIALS` | 已防 |
| 会话与吊销 | token 仅存 SHA-256；`authenticate` 校验 `revoked_at IS NULL AND expires_at>`；`revoke` 在事务内重新校验操作者身份 | 良好 |
| 幂等/越权 | push/purge 按 `user_id+op_id` 指纹去重，每次写事务内 `validIdentity` 复验 | 良好 |
| SSE | 每秒复验身份，`stop` 通道与设备绑定，注销/吊销关闭流 | 良好 |
| 自动更新 | `core/src/update_catalog.rs` minisign 验签 + sequence 回放防护 + URL 白名单 + 大小/SHA256 + 版本绑定；Android `UpdateInstaller` 再验签名/包名/versionCode/ABI/minSdk | 良好 |
| 本地凭据 | Android Keystore AES-GCM（每 profile 独立 key）；桌面 keyring；`remove` 双路撤销 | 良好 |
| XSS | Android 无 WebView（Compose Text，HTML 按字面显示）；桌面 CSP 无内联脚本 + `markdown.ts` 全量转义、图片禁用、链接白名单 | 良好 |
| 文件边界 | 桌面导出「临时文件 + `create_new` + hard_link」原子不覆盖；导入 2 MiB 有界；文件名脱敏 | 良好 |
| 日志/隐私 | 全仓无正文、token、邀请码、密码输出；错误码固定脱敏 | 良好 |

### 2.2 缺陷清单

#### M1（Medium，数据完整性）outbox `blocked` 无解除路径 → 笔记永久不同步 + purge 死锁

- 位置：`core/src/db.rs:166,170,341`、`core/src/purge.rs:52`
- 机制：`acknowledge` 收到不可重试错误时执行 `UPDATE outbox SET blocked=?`（db.rs:341），但全仓没有任何代码清除 `blocked`。`freeze` 的两个查询分别「跳过 blocked 行」（db.rs:166 `WHERE blocked IS NULL`）与「排除有 outbox 行的笔记」（db.rs:170 `NOT EXISTS(SELECT 1 FROM outbox WHERE note_id=n.id)`）。
- 触发场景（多设备真实可达）：设备 A 彻底删除笔记 → 设备 B 离线编辑 → B 同步收到 `NOTE_PURGED`（不可重试）→ B 的 outbox 进入 `blocked`。
- 后果：
  1. 该笔记后续所有编辑只落本机、**永不再冻结同步**（跨设备/重装即丢失这些增量）。
  2. 若此时再请求彻底删除：`purge::request` 因 `generation>acknowledged` 写入 `base_rev=NULL` 的 pending 行，而 `purge::next`（purge.rs:52）要求 `base_rev IS NOT NULL AND NOT EXISTS(outbox)`，永远取不到 → 笔记卡死在 `purge_state=pending`：不能编辑、不能导出、不能彻底删除、不能同步。
- 建议：增加显式「解除 blocked / 重试」路径——在确认服务端权威状态（先 pull 或按最终响应）后删除 blocked 行并以 shadow 重定 `base_rev`，让 `generation>acknowledged` 的新内容重新冻结为新的 op；至少保证有 blocked outbox 的笔记在 `purge::request` 时走可完成路径。
- 验证状态：代码路径确认；未动态构造服务端错误复现。

#### M2（Medium，性能，动态实测）大笔记 UI 冻结约 40–60s

- 位置：`android/.../MarkdownPreview.kt`（非惰性 `Column` 全量组合，约 34–47 行）、`android/.../SourceEditor.kt:93-99,197-203`
- 实测：API36 模拟器导入 932 KiB / 2 万行 Markdown，UI 无响应约 40–60s、CPU≈150%、内存 275→449 MiB，之后恢复；无 crash/ANR 记录。
- 根因：
  1. 预览用 `Column(verticalScroll)` 一次性 Compose 全部块（无 `LazyColumn`/虚拟化）。
  2. `afterTextChanged` 每次按键在主线程对**全文**跑 4 组正则 `findAll` 并逐个 `setSpan`（无阈值/防抖）。
- 建议：预览改 `LazyColumn`（或按块分段渲染）；编辑器高亮按字数阈值降级（如 >128 KiB 与 core `highlight_code` 阈值对齐）并加防抖；避免 `update` 中重复 `highlight`。
- 验证状态：已在模拟器复现。

#### Low 级

| 编号 | 位置 | 问题 | 建议 |
|---|---|---|---|
| L1 | `core/src/sync.rs:483` | 本地 `cursor` 被破坏为非法字符串时 `counter` 返回 `INVALID_REQUEST`，而 `synchronize` 恢复分支只匹配 `CURSOR_INVALID`/`SYNC_EPOCH_MISMATCH`，不进入保全恢复 | 把 cursor 解析失败统一映射为 `CURSOR_INVALID` |
| L2 | `core/src/purge.rs:107` | `purge::install` 只删 `source_id` 侧 redirect，不删以该 ID 为 `target_id` 的残留映射 | 同时 `DELETE FROM redirects WHERE target_id=?` |
| L3 | `desktop/src-tauri/src/main.rs:69` | `core_invoke` 无动作白名单，前端 JSON 原样透传 core（纵深防御缺口） | 入口加允许动作集合校验 |
| L4 | `desktop/src/App.svelte:745` | `rescue()` 未检查 IME 组合输入，可能捕获组合中的中间文本 | 捕获前检查 `editor.composing` |
| L5 | `desktop/src-tauri/src/main.rs:288-297` | 导出依赖 hard_link，FAT32/exFAT/SMB 不可用 | 检测 `Unsupported/CrossesDevices` 时回退不覆盖重命名 |
| L6 | `android/.../NoteExport.kt:64-69` | 单笔记导出写失败残留半截 `.md`（ZIP 路径会清理） | 失败时尽力 `deleteDocument` |

#### Info 级

- `core/src/model.rs:177` `counter` 用 `i64`，与 `spec/data-model.md` 的 u64 声明有文字偏差（无现实影响，不建议 0.1.x 改 schema）。
- `core/src/markdown_export.rs:28-30` Windows 保留设备名「尾随空格」变体（如 `CON .txt`）未拦截（仅影响建议文件名可移植性）。
- Android `AppUpdates.kt` `confirmation_pending` 只写不读（死状态，进程死亡后待确认只能取消）。
- Android `UpdateInstaller` verify→submit 之间未二次哈希（app-private 目录 + 系统签名已闭合，属纵深建议）。
- debug 包允许明文 HTTP（符合 spec 的 `BuildConfig.DEBUG`，发布包传 false，仅提示勿用真实凭据连明文服务器）。

## 3. 目标二：架构与软件工程评估

### 3.1 优点

- **分层清晰**：core(Rust+SQLite，本地数据与同步状态机) / server(Go，权威版本+SSE) / Android(Compose) / Desktop(Tauri+Svelte)；UI 层不实现同步/冲突规则，凭据经平台适配接口，笔记正文不交 WebView 直连数据库。
- **spec-as-truth**：产品行为、数据模型、同步协议、错误码均为规范先行，跨语言以同一组声明式一致性场景验证。
- **迁移严谨**：server 版本化迁移带 checksum 校验、拒绝篡改；core 只追加迁移并验证升级路径。
- **工程质量**：无 TODO/FIXME 标记、无生产路径 panic；`clippy -D warnings`、`gofmt`/`go vet`/`go test -race`；Conventional Commits、独立分支工作流；依赖经 ADR 记录。
- **测试覆盖充分**：core 49 单元 + 10 联调；server `-race`；Android 22 个仪器测试 + 单元测试；桌面单测（editor-session/markdown/presentation 等）。

### 3.2 短板与建议

1. **无 CI/Runner（有意）**：质量完全依赖本地验证纪律与交叉评审，存在回归风险。若未来引入，建议至少覆盖 `validate-local.sh` 各模块与迁移升级路径（不改变当前「不新增工作流」的决定）。
2. **超大单文件**：`desktop/src/App.svelte`（1978 行）、`desktop/src/lib/i18n.ts`（994 行）、`android/.../MainActivity.kt`（896 行）建议按「列表/编辑/设置/更新/账号」拆分，降低认知负担与合并冲突面。
3. **Markdown 渲染三处重复实现**：Android Compose、桌面 TS（`markdown.ts`）、core Rust（`highlight.rs`）各实现一份，一致性与安全规则需三处同步维护；若未来抽共享 Web 前端时再收敛，至少应有一份「渲染安全规则清单」对照。
4. **版本偏移**：server 仍 0.1.4，client/core 已 0.1.10；monorepo 内版本语义可更清晰（README 已注明，但建议在 `spec/README.md` 顶部给一张「模块→版本→协议/schema」对照表）。
5. **异常路径可恢复性**是相对最弱一环：M1（blocked 无解除）、L1（非法 cursor 不保全）都属于「正常流程正确、边界恢复不闭环」。
6. **`core_invoke` 任意 JSON 透传**：与「平台 UI 只提交固定意图」的纵深要求有差距（见 L3）。

## 4. 目标三：UI/UX 与产品审视

### 4.1 优点

- 视觉体系克制规范：纸白/炭灰 + 品牌色，4/8 倍数间距，正文优先可读；危险操作用强危险色 + 配对前景 + 红底留白；统一 Lucide 图标（桌面 20px/移动 24dp）、按钮点击区桌面 ≥36px/移动 ≥48dp。
- 信息架构清晰：列表（搜索 + 笔记 + 时间）→ 编辑（正文居中、返回/空间名/预览切换/更多）→ 设置（展示/空间/设备/数据/更新分 Tab）。
- 状态反馈到位：Draft/Saved locally/同步状态/冲突/凭据失效明确显示，不隐藏为普通成功图标；删除提供撤销 Snackbar。

### 4.2 问题与建议（按优先级）

1. **大笔记虚拟化 + 高亮限流**（=M2，最高优先级）：预览 `LazyColumn`，编辑器高亮按阈值降级/防抖。
2. **品牌切换收尾**：0.1.10 由 paper-v1「绿纸」改为「sunrise 橙渐变 + 阳墨随记」是一次身份切换，需核查官网 favicon/logo、三端图标、托盘、Windows 旧快捷方式迁移、旧包与新包的并存与卸载路径，避免新旧视觉并存割裂（流程见 `docs/brand-change-workflow.md`）。
3. **桌面超大组件拆分**：提升渲染粒度与可维护性（同 §3.2-2）。
4. **导出反馈一致性**：Android 单笔记导出成功仅 Toast 且无撤销；单笔记写失败残留半截文件而 ZIP 路径会清理（L6），行为应统一。
5. **新手引导**：`Sign in / Register with an invite` 表单对非技术朋友理解成本偏高（spec 有意不做常驻教程，可考虑一次性引导或更清晰的占位提示）。
6. **无障碍与键盘导航**：桌面多选上下文栏、菜单焦点恢复（`closeMenus(restoreFocus)`）已有处理，建议补全「全键盘可达」验收（Tab 序、焦点环、aria 状态）。

## 5. 测试执行记录（证据）

| 项 | 命令/环境 | 结果 |
|---|---|---|
| core 单元测试 | `cargo test --locked core` | 49 passed / 0 failed |
| server 测试 | `go test -race ./...` | ok |
| core-server 联调 | `DJHX_SERVER_BIN=… cargo test --test server_integration -- --ignored` | 10 passed（SSE 收敛 p95≈610ms） |
| Android 实机 | djhx_dev_api36 模拟器（Android 16），0.1.10 APK | 安装成功；`<script>alert(1)</script>` 预览为纯文本（无执行）；单笔记导出 `script_script_alert(1)__script_.md` 内容一致、文件名已脱敏；大笔记 40–60s 冻结复现；全程无 crash |
| Windows 实机 | vm-windows10（Windows 10 22H2，Guest Additions 7.2.2），0.1.10 安装包 | 静默安装成功（`djhx-memo.exe` 版本 0.1.10）；启动后窗口标题「DJHX MEMO」；数据目录 `%APPDATA%\app.djhx.memo\` 生成 `local.sqlite`(100KiB)/`profiles.json`/`.instance.lock`/`updates`，`profiles.json` 默认 `selected=local`；安装包 SHA256 与发布清单一致 |

### Windows 实机交互限制（记录）

- 环境无 RDP 客户端；`VBoxManage guestcontrol` 以 `--username koril` 运行在非交互窗口站，`SendKeys`/`SetForegroundWindow` 无法操作交互桌面。
- 交互改用 `VBoxManage controlvm keyboardputscancode/keyboardputstring`（物理键盘注入）：已确认 Win 键、Win+R 运行、Notepad 启动与文本键入均正常，即输入管线可达交互桌面。
- 未能在无鼠标注入的 headless 环境里点击 DJHX MEMO 窗口内的编辑器完成「新建→键入→保存」的端到端交互；该流程由共用 core（59 项测试 + Android 实测）与桌面 bridge 静态审查覆盖，不作为「桌面未通过」结论，也不宣称已完成该交互路径的真机点击验收。

## 6. 局限与未验证项

- 真实多设备、物理 arm64 Android、连续 7 天试用未做（项目自身亦承认未完成）。
- M1 为代码路径确认，未动态构造服务端不可重试错误复现。
- 调研期间当前模型不支持读图，UI 视觉细节依赖 Android uiautomator 文本层次、Windows 截图 OCR 与代码/设计规范，未做逐像素视觉比对。
- Windows 端到端「新建笔记」交互因 headless 无鼠标注入未完成（见 §5）。

## 7. 优先级建议清单（供后续开发参考）

1. **P0** M2 大笔记虚拟化 + 高亮限流（可用性/ANR 风险，已实测复现）。
2. **P1** M1 blocked outbox 解除/重试路径 + 彻底删除死锁修复（数据完整性，需配套 spec 变更）。
3. **P2** L1 非法 cursor 保全恢复、L2 redirect 清理（异常路径闭环）。
4. **P2** 桌面 `core_invoke` 动作白名单、L4 rescue IME、L5 硬链接回退（防御与兼容）。
5. **P3** 品牌切换收尾核查、超大组件拆分、渲染安全规则清单、模块版本对照表。
6. **P3** L6 导出失败清理、Info 项按需处理。

> 注：本文发现的任何需要改行为的项，按项目规范应先更新 `spec/` 对应契约、再实现并补测试，同步/迁移/鉴权变更须经交叉评审；本文不构成「已通过」任何验收。
