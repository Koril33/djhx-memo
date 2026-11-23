# 0.1.10 安全、架构与 UI/UX 综合调研报告

- 原始日期：2026-11-15；事实更正：2026-11-16。
- 对象：`v0.1.10`（commit `6e5373e`）——客户端/core 0.1.10；server 0.1.4、协议 2，客户端 schema 3、server schema 2。
- 性质：一次性调研，不改变产品行为；本次只修正文档。原始版本保留于 Git 提交 `97743e0`。
- 证据依据：[PR #27 独立事实核验](quality-audit-0.1.10-verification.md)。原报告自述的分路审查、API36 与 Windows VM 操作未附原始产物，本次不将其视为已独立证实的执行记录。
- 后续 0.2.0 范围讨论已归档；本文仍为提案，不是已接受契约或实施授权。
- 2026-11-19 追加：连续 7 天试用已由维护者确认完成；本报告正文保留 2026-11-16 事实更正时的状态。

## 1. 结论总览

- **M2 大笔记 UI 停顿已独立复现，应优先修复。** API35 隔离 debug 包打开 937.5 KiB／20,000 行合成笔记，至界面空闲约 135.6 秒，主线程心跳最大间隔约 131.7 秒。它证明问题存在，不验证原报告 API36 的 40–60 秒等历史数值。
- **M1 所述 NOTE_PURGED 永久死锁结论撤回。** 同步前后会 pull，安装远端永久删除标记会清理 blocked outbox 和待删除请求。其他永久拒绝下的恢复性问题保留为候选，需要补实际可达场景。
- 若干 Low/Info 项有局部代码依据，但尚未全部形成真实操作复现，不能与已复现缺陷等同排序。
- 账号、事务、更新验签及 Markdown 安全边界有相应实现；本次不是全面漏洞审计，不提供“无 Critical/High 漏洞”保证，也没有项目间统计依据支持“高于个人项目平均水平”的比较。
- 分层和跨端契约清楚；视觉取舍、组件拆分及品牌收尾是建议，不代表对应验收已经完成。

## 2. 漏洞与 BUG 的核查

### 2.1 安全面的实现依据

以下是相关机制的代码核对，不表示已穷尽所有攻击面。

| 边界 | 实现依据与限制 |
|---|---|
| 密码 | `server/auth.go` 使用 Argon2id，校验参数上界并进行常量时间比较 |
| 用户枚举 | 不存在用户执行 dummy hash，登录统一返回 AUTH_INVALID_CREDENTIALS |
| 会话/吊销 | 服务端存 token 的 SHA-256，鉴权检查有效期/吊销；写事务内复验操作者身份 |
| 幂等/越权 | push/purge 按账号范围和 op_id 处理指纹，事务内 validIdentity 复验 |
| SSE | 周期复验身份，流与设备绑定，注销/吊销关闭流 |
| 自动更新 | core 验签、sequence 防回放、URL/大小/哈希/版本绑定；Android 再核包名、签名、versionCode、ABI/minSdk |
| 本地凭据 | Android Keystore AES-GCM；桌面 keyring；平台删除失败时 core 仍有本地会话隔离保护 |
| Markdown | Android Compose 展示原始 HTML 文本；桌面 CSP、HTML 转义、图片禁用及链接白名单；不能据此授予 Markdown 原生命令权限 |
| 文件 | 桌面临时文件 + create_new + hard_link 发布新文件；原始导入读取有界，规范化正文上限 1 MiB；文件名过滤非法字符，不等于隐去标题中的全部个人信息 |
| 日志/隐私 | 相关运行错误采用固定脱敏提示；`invite-create` 按设计仅向管理员标准输出一次性邀请码，不能声称“全仓无邀请码输出”；没有重新执行全面隐私审计 |

### 2.2 M1：永久错误的恢复性候选，原 NOTE_PURGED 场景结论已更正

- 相关位置：`core/src/db.rs` 的 freeze/acknowledge、`core/src/sync.rs` 的 sync_round、`core/src/purge.rs` 的 request/next/install。
- 局部机制确实存在：不可重试的单项错误设置 blocked；freeze 跳过该操作，也不会为同一笔记生成新快照。未确认的普通删除会令 purge 等待原 outbox。
- 原推导遗漏：sync_round 在发送前及结束时拉取变化；apply_page 安装永久删除标记时，purge::install 删除对应 outbox（含 blocked 行）和 purge_requests，清空正文并写入最终状态。
- 因此，远端先永久删除通常在首次 pull 即收敛；若删除恰好发生在 pull 与 push 之间，NOTE_PURGED 后仍能通过末尾或后续成功 pull 收敛。不能称为“无任何解除路径”或“必然永久死锁”。
- 独立验证：既有 Go/Rust 永久删除 HTTP 场景通过；另一个隔离探针显式进入 blocked/pending 后应用永久删除标记，确认两类待发记录被清除，最终为 purged。
- 仍待调查：注入 INVALID_REQUEST 永久错误且没有远端删除标记时，blocked 不会因空页拉取消失，purge 可持续 pending。注入证明状态处理存在缺口，但尚未证明正常客户端操作能触发这一响应。
- 后续建议：先确定错误类型及真实触发步骤，再设计明确处理入口。永久字段错误不应无限自动重试；NOTE_PURGED 不得复活原 ID，也不得自动生成副本。不能仅因 shadow 更新就提高旧编辑的 draft_base_rev 并重发，以免绕过冲突保全。

### 2.3 M2：大笔记 UI 停顿，独立复现

- 位置：Android `MarkdownPreview.kt` 的非惰性 Column、`SourceEditor.kt` 的 afterTextChanged/highlight/update。
- 2026-11-16 独立样本：API35 x86_64、4 核、3072 MiB、隔离 0.1.10-debug 包，沿用本机 JNI。20,000 行标题，960,000 UTF-8 字节；公共桥接导入 228 ms，实际源码页打开至空闲 135,561 ms，主线程 50 ms 心跳最大间隔 131,692 ms；恢复后正文精确一致，测试 1 项通过。
- 原报告自述 API36／932 KiB／40–60 秒／CPU≈150%／内存 275→449 MiB／无 crash 或 ANR；缺原始 fixture、包哈希和采样资料，保留为未独立证实的历史自述，不能用上述新结果回填。
- 可疑耗时路径有代码依据：主线程对全文做四组正则与大量 span 操作；预览解析/非虚拟化组合；update 可能重复装饰。独立样本没有切换预览，说明仅修预览不足以解决已复现问题；尚未用 profiling 分摊各路径耗时。
- 建议讨论：先限制大文本全量高亮及装饰数量，保留编辑、保存、IME 和撤销；再后台解析并分块惰性预览。仅添加防抖不能消除首次打开的昂贵工作；大段落/大代码块也不能只靠 LazyColumn 解决。
- 详细候选方案、数据不变量与拟验收条件见已归档的下一版讨论稿。本轮没有修改实现或宣布性能达标。

### 2.4 Low 级候选

| 编号 | 位置 | 已核对事实与证据边界 | 后续建议 |
|---|---|---|---|
| L1 | `core/src/sync.rs` | 非法本地 cursor 的 counter 返回 INVALID_REQUEST，恢复分支只匹配 CURSOR_INVALID/SYNC_EPOCH_MISMATCH；未做损坏数据库端到端注入 | 补复现，再评估专门映射与保全恢复 |
| L2 | `core/src/purge.rs` | install 只删 source_id 侧 redirect，target 侧映射可残留；已有目标删除屏障测试通过，不证明可复活 | 评估清理与迟到编辑反馈，不绕过代次检查 |
| L3 | `desktop/src-tauri/src/main.rs` | core_invoke 透传 JSON，但 core dispatch 已枚举动作并拒绝未知动作 | 外壳白名单属于纵深建议，不是已证实的任意能力执行漏洞 |
| L4 | `desktop/src/App.svelte` | rescue 未显式检查 composing；未验证真实 IME 点击、失焦、compositionend 顺序 | 补原生复现，保留救援缓冲 |
| L5 | `desktop/src-tauri/src/main.rs` | 导出依赖 hard_link，FAT32/exFAT 存在兼容限制；SMB 取决于共享后端，不能一概否定 | 补文件系统测试；回退须保证不覆盖，通用 rename 本身不保证跨平台不覆盖 |
| L6 | Android `NoteExport.kt` | 单篇失败无删除目标的清理，ZIP 路径有尽力清理；是否残留取决于 SAF provider 失败方式 | 注入半写入故障后设计清理，不声称所有失败都留下半文件 |

### 2.5 Info 与措辞修正

- `counter` 用 i64，server 用 ParseInt(..., 64)；`spec/data-model.md` 写的是“非负 64 位整数”，没有 u64 声明。可以补明确上界，不能直接记为现有 i64/u64 契约冲突。
- 建议文件名对 Windows 保留设备名尾随空格变体（如 CON .txt）的处理需补可移植性测试；不能将文件名过滤称作完整隐私脱敏。
- AppUpdates 的 confirmation_pending 只写不读，但恢复仍依据 session/compatibility 并允许取消；这不等于整个安装恢复缺失。
- UpdateInstaller verify→submit 间二次哈希属于纵深建议，需结合 app-private 存储和系统签名验证评估。
- debug 允许明文 HTTP，release 不允许；符合现有调试边界，不是正式包明文漏洞结论。

## 3. 架构与工程评估

### 3.1 已有机制

- core 管理本地 SQLite、待发与同步；server 管理权威版本与账号；平台处理交互和安全凭据，不在 UI 重做冲突规则。
- spec 提供行为契约与共享一致性场景；迁移有版本和校验，并有升级/高版本保护测试。
- 本地入口包括 clippy、Go 检查、桌面/Android 测试；“存在检查入口”与“本轮已执行”分开记载。当时没有 CI/Runner（构建/测试自动化现由 [ADR-0017](../adr/0017-github-actions-ci.md) 采用 GitHub Actions）。
- 生产路径存在内嵌配置和静态 scope 的 expect，不声称完全没有 panic 路径；未据此证明外部可利用崩溃。
- 数量口径：core 49 项单元、10 项默认 ignored 的联调；Android 22 个仪器测试类、60 个 @Test 方法；桌面当前 6 文件/34 项。数量不等于全部执行或充分覆盖。

### 3.2 建议与限制

1. 按现有本地检查纪律记录真实结果；CI/Runner 的暂缓已由 [ADR-0017](../adr/0017-github-actions-ci.md) 变更为采用 GitHub Actions。
2. App.svelte 1978 行、i18n.ts 994 行、MainActivity.kt 896 行，可按职责渐进拆分；不为修 M2 顺带重写全 UI。
3. **两套平台 Markdown 渲染 + core 共享代码高亮**：Android/桌面分别渲染，core highlight.rs 定位代码块并输出 span，不是第三套完整渲染器。对齐两端的任务/链接/安全场景，不由此引入暂缓的独立 Web。
4. server0.1.4 与 client/core0.1.10 是已说明的模块版本差异，可完善版本表，不直接当作兼容缺陷。
5. 异常恢复候选按可达性与证据分级；M1 的原场景不能作为已确认数据完整性缺陷。

## 4. UI/UX 建议

原报告未逐像素核验目标平台，以下主要依据源码与设计规范，不能当作设备矩阵已通过。

- 现有纸白/炭灰、品牌色、Lucide 图标和点击区域有设计依据，需继续原生复核。
- 本地保存和云同步状态应独立表达；不能笼统称“状态反馈到位”。维护者现已报告 Android 离线打开 Devices 等待后模态报错，已纳入后续 0.2.0 离线交互范围。
- 大笔记以保持可输入和可保存为优先；不牺牲正文、组合输入、选区、撤销或同步基准来换取速度。
- 品牌与安装收尾遵循[固定流程](../brand-change-workflow.md)，未核查项保留待测。下一版讨论包含桌面图形占比、墨绿候选和新安装目录命名；现行资源与身份尚未改变。
- 单篇导出重点是快照正确、写入失败可见及残留清理；文件导出不是删除操作，缺少“撤销导出”本身不作为缺陷。
- 新手提示、键盘可达、焦点与读屏可继续评估，避免在未做真实操作时宣称体验完全通过。

## 5. 测试记录与证据状态

### 5.1 原报告记录的更正

| 原记录 | 核验与修正 |
|---|---|
| `cargo test --locked core` → 49 passed | core 是测试名过滤器；独立执行为 0 passed、49 单元及 10 联调 filtered out。原命令不能支撑原结论 |
| `go test -race ./...` → ok | 原始输出未附，本轮未重跑完整 Linux 流程，保留未独立证实 |
| 10 项联调、SSE p95≈610ms | 原始输出与样本未附，本轮只重跑其中永久删除真实 HTTP 场景，不将其当作 10 项通过 |
| API36 安装、HTML/导出、大笔记测量 | 未附完整执行资料；M2 问题由另一 API35 样本独立确认，其余历史流程未重演 |
| Windows10 VM 安装、版本/标题、100KiB 数据库、包哈希一致 | 保留为原报告自述；缺可追溯收据，本轮未独立重演 |

正确的根目录 core 命令为 `cargo test --locked --manifest-path core/Cargo.toml`。完整联调另需 Linux 构建的真实服务端并显式执行 ignored 测试，入口见[本地验证](../validation.md)。

### 5.2 独立核验的实际结果（2026-11-16）

| 项 | 实际结果 |
|---|---|
| 正确 core 命令 | 49 passed，10 ignored |
| Windows Go 显式文件集 + DJHX_CORE_PURGE_INTEGRATION=1 | 4 个 Go 测试通过，包含 1 项 Rust 真实 HTTP 永久删除联调；Android HTTP 项跳过，不覆盖 Unix epoch/CLI |
| 隔离源码副本的 blocked/purge 探针 | 2 passed；NOTE_PURGED 标记可清理，另一永久错误注入可持续 pending |
| 桌面 npm test | 6 文件、34 项通过 |
| API35 独立大笔记样本 | 1 项通过并记录严重停顿；不是性能验收通过 |

命令、平台、fixture、测量输出和清理边界见[独立核验全文](quality-audit-0.1.10-verification.md)。这些新执行结果不回填为原报告日期的验证。

### 5.3 Windows 交互的边界

原报告自述 headless VM 中可注入键盘，但未完成应用“新建→输入→保存”交互。此限制是当次操作条件，不应泛化为本机永远没有鼠标控制能力。共用 core/Android 测试与 bridge 静态检查不能代替 Windows 原生端到端验收；既不据此判定该流程失败，也不宣称已经通过。

## 6. 局限与后续优先级

- 完整三端设备矩阵、物理 arm64/OEM、连续七天试用仍未完成。
- M2 是本轮最明确的修复项；先做性能剖析和受控降级，再评估预览分块，方案尚待讨论。
- M1、L1–L6 按各自证据补复现/边界测试，不能直接套用原来的统一重发或全面加白名单建议。
- 原历史环境的测试和分路独立审查没有补齐产物；本次不把文档修订、单元测试或版本号当作完整安全/平台验收。

任何后续行为变更均先更新 spec，再实现并补有效测试；同步、迁移、鉴权变更仍须独立交叉评审。下一版讨论不构成编码或发版授权。
