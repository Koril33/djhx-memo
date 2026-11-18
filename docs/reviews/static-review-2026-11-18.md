# 0.1.10 全端静态评审与调研分支意见

- 日期：2026-11-18
- 对象：main `8e4f46e` 加 `codex/docs-client-020-predevelopment`（产品源码与 0.1.10 相同），以及 `origin/research/cli-cross-platform`。
- 性质：一次性只读评审，不改变 spec 或产品行为。非技术摘要见[项目现状报告](../project-status-2026-11-18.md)。
- 方法：先分模块阅读源码并对照 spec 和 [0.1.10 审计](quality-audit-0.1.10.md)、[核验](quality-audit-0.1.10-verification.md)，再由汇总者抽查关键结论。"已核实"指汇总者本人在源码中重新确认过的条目。
- 实际执行的检查：
  - desktop：`npm run check`（0 错 0 警告）、`vitest run`（6 个文件 34 项通过）、`eslint --max-warnings 0`（通过）。
  - server：`gofmt -l .`（无输出）、`GOOS=linux GOPROXY=off go vet ./...`（无输出）。
  - 文档：`python -I scripts/check-docs.py`（PASS）。
- core：`cargo clippy --offline --all-targets`（无告警，使用缓存产物）。
- 未执行：`go test -race`、联调、cargo test、Gradle、模拟器、真机、虚拟机、网络故障注入。"可能"类条目需要补复现才能定级。

严重度说明：**高** 指可能导致数据丢失或静默覆盖、全体用户不可用；**中** 指明显影响使用或安全纵深；**低** 指边缘路径或体验问题；**建议** 指整洁度或可维护性改进。

---

## 1. 优先处理（跨模块）

| ID | 严重度 | 问题 | 位置 | 核实 |
|---|---|---|---|---|
| X1 | 高 | push 时身份复验失败，单项返回 `AUTH_REQUIRED`、retryable=false，core 随即把这条 outbox 永久标为 blocked，全仓没有任何清除 blocked 的路径 | server/store.go:200-202、server/http.go:405-408、server/types.go:65、core/src/db.rs:338-343 | 已核实 |
| X2 | 中-高 | 桌面远端整篇替换会进入 CodeMirror 撤销栈；用户 Ctrl+Z 会恢复旧正文，随后按最新 generation 正常保存，静默覆盖另一端的修改 | desktop/src/Editor.svelte:119-126 | 已核实 |
| X3 | 中 | 登录限流表在 add=false 时也写入；超过 1 万条且清理过期项后仍超限，就全局返回 RATE_LIMITED | server/http.go:257-283 | 已核实 |
| X4 | 中 | core 先把响应体按 JSON 解析，后检查 HTTP 状态。nginx 在上游不可用时返回 HTML 502/504，解析失败后被映射为 `INVALID_REQUEST`、retryable=false，随即写入 `auto_blocked`，后台自动同步停止，直到用户手动同步。服务端重启或部署即可触发 | core/src/sync.rs:92、model.rs:47-50、sync.rs:427-429 | 已核实 |

**X1 详述**
- 触发条件：设备 A 已通过 authenticate、正在 push，此时另一端吊销 A、管理员执行 password-reset，或 token 恰好到期。
- 后果：
  - 当前这条笔记被永久 blocked。freeze 会跳过 blocked 行，之后对该笔记的编辑都不再上传，重新登录也不会解除。
  - 依赖该条的 purge 会一直 pending。
- 这正是审计 M1 一直缺少的"正常操作可达的永久错误"路径，建议把 M1 提为已复现候选并优先补测试。
- 修复方向（需先改 spec，并按 AGENTS.md 交叉评审），二选一或叠加：
  1. 服务端身份复验失败时中止整批，外层返回 401 `AUTH_REQUIRED`，这与 error-codes 的会话语义一致。
  2. core 把单项鉴权类错误视为会话失效，不设 blocked。
- 另外，blocked 需要一条在 spec 中定义的用户可见恢复入口（例如"重新提交/另存为副本"），不能只靠自动清除。

**X2 修复**
- 远端替换加 `Transaction.addToHistory.of(false)`。
- 改为最小差异 changes 以保留选区，必要时重建 history。
- 补 Editor 组件测试，覆盖"远端替换后撤销"。

**X3 修复**
- 只在失败时写入限流表；按维度设上限并采用 LRU 淘汰，表满时不做全局拒绝；IPv6 按 /64 聚合。
- 补饱和测试。

**X4 修复**
- 先判断状态码，再解析响应体。
- 非 2xx 且响应体无法解析时：5xx/429 视为 `SERVER_BUSY`（可重试），401 视为 `AUTH_REQUIRED`。

---

## 2. core（Rust）

`cargo clippy --offline --all-targets` 无告警（使用缓存产物，由分路评审执行）。

**总体评价**
- 同步状态机写得严谨：快照冻结、旧代次确认不覆盖新输入、冲突分支保全、事务边界都正确；锁顺序统一为 registry → 其余，没有发现死锁。
- 主要风险在错误分类：默认不可重试，非 JSON 错误体和 SQLITE_BUSY 都会停掉后台同步，blocked 也没有出口。
- 其次是性能放大：FTS 触发器无条件重建、频繁 `db::open`、全量读取正文。

| # | 严重度 | 位置 | 问题与场景 | 建议 | 置信度 |
|---|---|---|---|---|---|
| C1 | 高 | db.rs:338-343 | 见 X1：任何不可重试的单项错误都会设 blocked，且没有出口 | 见下方"X1 的 core 侧方案" | 确认 |
| C2 | 中 | sync.rs:92-108 | 见 X4 | 见 X4 | 已核实 |
| C3 | 中 | model.rs:31-38 | 所有 rusqlite 错误（包括超过 5s busy_timeout 的 SQLITE_BUSY）都被映射为不可重试的 `LOCAL_STORAGE_FAILED`。同步线程 apply_page 写大笔记并重建 trigram 索引时，UI 保存可能超时；反过来，同步线程遇到 BUSY 会被 auto_blocked | DatabaseBusy/Locked 单独映射为可重试错误 | 代码确认，发生概率"可能" |
| C4 | 中 | migrations/0002_search.sql `notes_update`、db.rs:117 | 触发器是 `AFTER UPDATE OF content`，而 `put()` 的 UPSERT 每次都 SET content，只改元数据（ack、set_deleted、purge_state）也会整篇删除并重建 FTS 索引，1 MiB 笔记每轮同步多做 1-2 次 | 追加迁移 0004 重建触发器，加 `WHEN old.content IS NOT new.content`（不改已发布迁移） | 已核实 |
| C5 | 中 | db.rs:~100-110 `list` | 列表和搜索返回每篇笔记的完整 `content`；安卓每次 flush 后和搜索每个字都会调用，整库正文经 FFI 序列化 | 列表只返回 title/excerpt/元数据，打开笔记时再取正文 | 已核实 |
| C6 | 低 | db.rs:105-109 | FTS 用法本身正确（trigram 候选加外层 LIKE 精确过滤），但不足 3 个字符时退化为全表 LIKE，中文最常见的 2 字搜索总是全表扫描并读出全部正文 | 短查询只投影 id 和摘要 | 确认 |
| C7 | 低 | sync.rs:62-71、451-460、761-770 | `Retry-After` 没有上限；服务器或代理误配置一个很大的值，手动同步也会持续被拒，直到进程重启 | 上限 5-10 分钟，手动同步可越过 | 确认 |
| C8 | 低 | db.rs:18-63 | `user_version` 在 IMMEDIATE 事务外读取，两个线程同时打开一个未迁移的空间时，后到者会重复 `ALTER TABLE` 并报错一次 | 在事务内重新读取版本 | 可能 |
| C9 | 低 | sync.rs:337-365、400-414 | 客户端本地分页校验失败也报 `CURSOR_INVALID`，从而触发 recover；服务器分页 bug 会把整个账号空间转为只读恢复空间 | 本地协议违例用单独错误码，不触发恢复 | 确认（属策略判断） |
| C10 | 低 | sync.rs:380、update_barrier.rs:40-56 | synchronize 在整轮同步期间持有 activity lease，包括每次最长 35s 的网络请求，而安装屏障只等 40s；0.1.7 只修了 SSE 路径，积压多时安装屏障可能反复超时 | 网络等待期间不持有 lease，只在本地事务前 enter | 代码确认，影响可能 |
| C11 | 低（性能） | db.rs open、lib.rs:398、574-588、list_actions.rs:27 | 每次 `db::open` 都计算迁移 SHA-256 并执行 pragma，而每个 dispatch、SSE 每行、批量操作每项都会调用一次；`import_count` 每次读出 local 全部正文；list 逐条查询 blocked（N+1） | 按空间缓存"已迁移"标记或复用连接；import_count 只取 id；blocked 用 JOIN 一次取回 | 确认 |
| C12 | 建议 | sync.rs:495-534、469-480、db.rs:153-160 | push 每次只发 1 个 op（协议支持批量）；服务器下发的 epoch 未经 `valid_id` 校验就拼进 URL 并落库；`db::blocked` 用 `unwrap_or(false)` 吞掉错误，出错时 UI 显示"已同步" | 分批 push；校验 epoch；错误向上传递 | 确认 |

**X1 的 core 侧方案**（会改变行为，需先改 sync-protocol 并经过同步交叉评审）
1. 只对确属单条内容的永久错误设 blocked，用白名单，如 `NOTE_TOO_LARGE`、`INVALID_REQUEST`。`AUTH_REQUIRED`、`SERVER_BUSY`、`RATE_LIMITED` 改为整轮级处理：保留 op，进入 auth_required 或稍后重试。
2. 自愈：打开数据库或登录成功后，清除已存储的会话类 blocked 记录（DML，不改 schema），恢复已经卡住的用户。
3. 用户出口：手动同步时重试 blocked op，op_id 幂等，真正的永久错误会被服务器再次拒绝；或者在用户再次编辑时，以新快照替换已被明确拒绝的旧 op。具体语义需在 spec 中定义，并与 permanent-deletion"不复活原 ID"的规则核对。

**测试缺口**：非 JSON 错误体；SQLITE_BUSY 下同步与保存竞争；超大 Retry-After；元数据更新不触发 FTS 重建；并发迁移；blocked 解除。

**其他分路或审计已涉及的 core 条目**

| 严重度 | 位置 | 问题 | 建议 | 置信度 |
|---|---|---|---|---|
| 低 | update_catalog.rs:552 | `Version::parse(..).ok() >` 比较的是 Option；本地版本解析失败为 None 时，任何候选都被判为更高 | 本地版本解析失败直接报错 | 确认（当前版本串是编译期常量，实际风险低） |
| 低 | update_catalog.rs:403 | 更新状态损坏时 `unwrap_or_default()`，sequence 归零，防回放随之失效 | 状态损坏时不降低已接受的 sequence 下限 | 可能 |

---

## 3. server（Go）与协议

| # | 严重度 | 位置 | 问题与场景 | 建议 | 置信度 |
|---|---|---|---|---|---|
| S1 | 高 | 见 X1 | 单项 AUTH_REQUIRED 不可恢复 | 见 X1 | 确认 |
| S2 | 中 | 见 X3 | 限流表饱和导致全局拒绝登录和注册 | 见 X3 | 确认 |
| S3 | 低 | store.go:340-358 + migrations/002 | 分页字节预算按库中原始快照长度累计；0.1.4 前写入的快照没有 `purged` 字段，重新序列化后每条多约 15 字节，50 条可能超过 512 字节预留。命中时 core 拒收同一页，首次全量同步永久失败 | 按重新序列化后的长度计算，或放大预留；补测试 | 可能（概率低，命中即确定性失败） |
| S4 | 低 | http.go:148-151、store.go:173-185 | INTERNAL_ERROR 丢弃原始错误，request_id 不写日志，与 error-codes"以 request_id 排查"不符 | 用 request_id 加错误类别写结构化日志，不含正文和凭据 | 确认 |
| S5 | 低 | store.go:62、export.go:61-98 | `SetMaxOpenConns(1)`，等待连接无超时。云导出在事务内完成整个 ZIP 压缩，期间阻塞 healthz、push 和 SSE 每秒复验；设备数、SSE 流、导出并发都没有上限 | 限制导出并发，查询加超时 context，设置每账号设备和流上限 | 确认（影响取决于数据规模） |
| S6 | 低 | main.go:129、store.go:81-129 | `backup`/`check` 管理命令会执行未应用的迁移。若已替换二进制但未重启，定时备份就会在旧进程运行时迁移生产库 | 管理命令只读打开，版本不一致时拒绝 | 确认 |
| S7 | 低 | http.go:333-366 | 先检查、后计数，加上 4 个 Argon2 槽位，一个窗口实际约可尝试 8 次；登录成功不清零 | 检查时原子预占计数 | 确认 |
| S8 | 低 | purge.go:238 | purge 用普通 decode（键名不区分大小写、接受重复键），与 openapi 的 additionalProperties:false 不一致 | 复用 push 的精确键集合校验 | 确认 |
| S9 | 低 | deploy/nginx.conf | 无 HSTS、nosniff、Referrer-Policy、limit_conn/limit_req；auth.md 要求反代协同限制连接数 | 补安全头和连接限制；CSP 需处理 website/index.html 的内联主题脚本 | 确认（仓库配置；线上实际配置未核） |

**spec / openapi / 实现不一致**（建议补进契约，不改行为则只改文档）
1. sync-protocol §5 规定 cursor/until 类型错误返回 `CURSOR_INVALID`，实现返回 400 `INVALID_REQUEST`，conformance SYNC-20 两者都接受。这与审计 L1 的 core 侧问题相关。
2. push 单项结果可能出现 `AUTH_REQUIRED`、`SERVER_BUSY`、`INTERNAL_ERROR`，error-codes 和 openapi ErrorResult 都没有列出，也没有说明客户端应如何处理。
3. 以下错误映射未写入文档：
   - 非 HTTPS 或非可信代理返回 400 `INVALID_REQUEST`；
   - 带 Origin 的请求返回 400；
   - `--registration-disabled` 返回 403 `INVITE_INVALID`；
   - 方法错误返回 404 而不是 405。
4. openapi 中 pull 的 `limit` 是 integer，实现按 Counter 规则拒绝 `050`、`+5`。
5. openapi 中 purge 200 响应缺 `X-Request-ID` 头声明。
6. auth.md"验证旧哈希后可升级参数"尚未实现，应注明。

**测试缺口**
- 限流饱和、单项 AUTH_REQUIRED、旧快照字节预算、purge 键大小写、IPv6/XFF 均无测试。
- `TestFixedUntilAndResponseByteBudget` 对 cursor 错误只断言"409 或 400"，没有固定到具体错误码。

**已核对无问题的部分**：Argon2id 与 dummy hash、token/邀请码只存哈希、写事务内身份复验、SSE 绑定设备与复验、全部查询带 user_id、幂等指纹、epoch 文件原子写、systemd 沙箱、VACUUM INTO 加 CMS 加密备份、日志不含正文和凭据。

---

## 4. desktop（Tauri + Svelte）

| # | 严重度 | 位置 | 问题与场景 | 建议 | 置信度 |
|---|---|---|---|---|---|
| D1 | 中-高 | Editor.svelte:119-126 | 见 X2；任务勾选同样进入撤销栈，整篇替换会让光标跳回文首 | 见 X2 | 确认 |
| D2 | 中 | App.svelte:365-371 | readOnly 包含 `busy`，手动同步、托盘同步、打开设置期间编辑器只读，网络慢时按键被静默丢弃 | 同步改用独立 syncing 状态，不占用全局 busy；只有需要冻结正文的操作才锁编辑器 | 只读逻辑确认，锁定时长未测 |
| D3 | 中 | App.svelte:320-338、372-379 | 无论处于哪种模式，每次按键都对全文 `renderMarkdown`，纯源码模式也不例外；标题和字数也按全文重算。大笔记接近上限时卡顿，审计 M2 只覆盖安卓 | 源码模式跳过渲染，预览防抖或空闲时计算 | 计算路径确认，卡顿未测 |
| D4 | 中 | editor-session.ts:129-131、App.svelte:885-897 | `finishLifecycle` 无限期等待 compositionend；WebKitGTK 在组合输入期间失焦可能不触发，`lifecyclePending` 永远为真，应用无法隐藏或退出 | 组合等待加超时，超时后保留缓冲并提示救援 | 可能（与 L4 相邻） |
| D5 | 低 | App.svelte:174-190 | `undoTrash` 在调用 core 前就清除撤销入口，恢复失败后入口消失 | 成功后再清除 | 确认 |
| D6 | 低 | App.svelte:1748-1752 | 吊销设备（包括本机）没有确认步骤 | 吊销本机时加确认并说明后果 | 确认 |
| D7 | 低 | main.rs:548-552 | 托盘左键只判断 visible && !minimized；窗口被遮挡时单击托盘会隐藏窗口 | 增加 `is_focused()` 判断 | 确认 |
| D8 | 低 | main.rs:315-330 | 系统保存框已确认覆盖，`publish_new_file` 仍报"文件已存在" | 调整文案或保存前预检 | 确认 |
| D9 | 低 | main.rs:346、bridge.ts:39-62 | 原生命令错误只返回中文句子，前端拿中文整句作映射键，丢失错误码；Rust 侧改一个字就会退化为通用错误 | 统一返回 `{code}`，并加映射一致性测试 | 确认 |
| D10 | 低 | App.svelte:872-874、914-924 | "跟随系统"外观每 2 秒查询一次（Linux 每次起 gdbus/gsettings 子进程），state 每秒轮询，隐藏到托盘后仍继续 | 隐藏时暂停；Linux 监听 portal SettingChanged | 确认 |
| D11 | 低 | App.svelte:1137-1142、1129 | 卡片和搜索框 `disabled={busy}`，键盘焦点会被弹回 body | 改用 aria-disabled 加逻辑拦截 | 可能 |
| D12 | 低 | App.svelte:1789-1792、1696/602 | 设置"数据"页导出的是主视图当前分区（可能是回收站）；密码 maxlength 256 而校验上限 128；"安装并重启"用了撤销图标 | 导出范围显式选择；统一上限；更换图标 | 确认 |
| D13 | 建议 | Updates.svelte:33-88、156-274；i18n.ts | 约 40 条内联中英文案绕开 i18n；约 24 个未引用 key；editor-session `status` getter 未使用；App.svelte 1978 行 | 文案并入 i18n，清理死键，按职责渐进拆分 App.svelte | 确认 |

**无障碍**
- 设置标签缺 `role=tablist/tab` 和 `aria-selected`。
- `.view-switch` 缺 `role=group`。
- pending-dot 只有 title。
- 设置弹窗与主界面各有一个 `role=alert`，同一错误会被朗读两次。
- `<progress>` 没有可访问名称。

**测试缺口**
- 没有 App/Editor 组件测试（远端替换与撤销、busy 期间输入、撤销失败）。
- 没有 bridge 与 main.rs 的错误映射一致性测试。
- 没有 lifecycle id 过期、托盘点击、导入大小上限的 Rust 测试。

**已核对无问题的部分**：capability 只开放事件监听；CSP `script-src 'self'`；原始 HTML 转义；图片拦截；链接只放行 http(s) 并在原生侧二次校验；导出 create_new 加 hard_link 不覆盖；UI 层没有实现同步或冲突规则；日志无正文和凭据。

---

## 5. Android（Kotlin + Compose）

| # | 严重度 | 位置 | 问题与场景 | 建议 | 置信度 |
|---|---|---|---|---|---|
| A1 | 中 | MemoModel.kt:243-250 | `init` 中每秒调用 core `state` 的循环挂在 viewModelScope 上，后台不暂停，持续耗电 | 用 `repeatOnLifecycle(STARTED)` 或前后台开关暂停 | 已核实 |
| A2 | 中 | MemoModel.kt:246、MainActivity.kt:538 | 轮询失败时 `report()` 写入全局模态 message，本地 I/O 持续失败时会每秒重新弹窗 | 轮询错误改为横幅或状态字段，按错误码去重或退避 | 循环确认，触发条件可能 |
| A3 | 中 | MemoModel.kt:326、417-429 | 每次 flush 和每个搜索字符都 `loadNotes()`，配合 core list 返回全文（§2），会放大 M2 | list 去正文；编辑时只更新当前条目 | 已核实 |
| A4 | 中 | AccountSettings.kt:439-451 | 密码和邀请码只做了视觉遮挡，没有 `KeyboardOptions(Password)`（全工程无 KeyboardOptions）；输入法可能学习这些内容，密码管理器无法填充；服务器地址缺 Uri 键盘 | 设置 Password/Uri 键盘、关闭 autoCorrect、加 autofill，补显示密码切换 | 已核实 |
| A5 | 中 | MemoModel.kt:579-587、647-658；AccountSettings.kt:73、87 | Devices、吊销、注销都走全局 action，busy 时编辑器禁用、返回键被吞、设置无法关闭 | 即 0.2.0 W2 | 确认（已知） |
| A6 | 中 | SourceEditor.kt、MarkdownPreview.kt、CodeHighlights.kt | 即 M2 | 即 0.2.0 W1 | 确认（已知） |
| A7 | 低-中 | MainActivity.kt:148、158-193 | 空间 ZIP 导出写入用 `rememberCoroutineScope`，旋转屏幕会取消写入并被报为"导出不完整"（单篇导出放在 viewModelScope，两者不一致）；整个 ZIP 以 Base64 放在 JSON 中，大空间有 OOM 风险 | 改用 viewModelScope；core 改为流式或分块 | 确认 |
| A8 | 低 | MainActivity.kt:159-160、196-206 | pendingExport、导入会话、当前打开的笔记只存在 ViewModel；文件选择器打开期间进程被回收，会留下 0 字节文件且没有提示 | 结果为空时提示并删除；用 SavedStateHandle 保存笔记 id | 确认 |
| A9 | 低 | MemoModel.kt:647-657 | 编辑器 `enabled = !busy`，任何 action 都会使 EditText 失效、收起键盘 | 编辑器只受保存锁和只读状态控制 | 确认 |
| A10 | 低 | AccountSettings.kt:205-210 | 提交时立即清空密码（与"失败保留表单"注释不符），失败后用模态框报错 | 成功后再清空，错误改为行内显示 | 已核实 |
| A11 | 低 | MarkdownPreview.kt | 没有 SelectionContainer；回收站和只读空间只能预览，无法复制文字 | 外层包 SelectionContainer | 确认 |
| A12 | 低 | MainActivity.kt:876、AccountSettings.kt:360 | 冲突和设备详情直接显示 UUID | 折叠为"技术详情" | 确认 |
| A13 | 低 | MainActivity.kt:576-588 | 首次加载前先闪一下"还没有笔记"；空状态没有引导 | 加载态，空状态提供新建和导入入口 | 确认 |

- `undoTrash` 先等 busy 结束再调用 action，分路评审曾列为竞态。汇总者复核认为：它在主线程 dispatcher 上从循环退出到 `action{}` 之间没有挂起点，基本不会被其他 action 插入，因此不列为缺陷。
- **无障碍**：
  - 可点击文字区域缺 Role/onClickLabel，包括顶栏标题（打开空间设置的入口较隐蔽）、AttentionBanner、ConflictInfo、设备行。
  - 自动检查的 Switch 没有与文字合并为 toggleable 行（UpdateSettings.kt:118）。
  - EditText 的 contentDescription 会覆盖正文朗读（SourceEditor.kt:81）。
  - 预览标题缺 `heading()` 语义。
- **整洁度**：
  - `MemoScreen` 约 445 行、`AccountSettings` 约 345 行，而 detekt 对 @Composable 豁免 LongMethod，这两个函数不会被检出。
  - 状态全部通过 JSONObject 字符串键访问，`optJSONObject("profile")?.optBoolean("read_only")` 重复约 8 次，建议引入轻量 data class。
- **测试缺口**：
  - 没有 compose ui-test 依赖。
  - 后台轮询、错误去重、进程死亡、IME 属性、离线 Devices、TalkBack、大字号均无测试。
  - M2 探针测试未入库。
- **已核对无问题的部分**：allowBackup=false 且 dataExtraction 全部排除；release 禁用明文；receiver 和 FileProvider 不导出，按 attempt 撤销授权；没有 Log/WebView；Keystore AES-GCM；更新包 verify 核对签名、包名、versionCode、minSdk、ABI 并复算哈希；zh/en 字符串键对齐。

---

## 6. 官网、脚本、更新签名链、仓库卫生

| # | 严重度 | 位置 | 问题 | 建议 |
|---|---|---|---|---|
| W1 | 中（运维，限期） | releases/0.1.10-validation.md:47 | 线上目录序号 7 于 **2026-12-14T16:29Z** 到期，过期后客户端报 UPDATE_EXPIRED，没有到期提醒 | 12-07 前签发序号 8；加一个只读检查脚本，剩余不足 10 天时提醒 |
| W2 | 中（运维，限期） | cloud-deployment.md:25 | TLS 证书 2026-12-27 到期，记录为"未接入自动续期" | 确认续期方式并记录 |
| W3 | 中（已知限制） | update-publishing.md:19-21 | 目录钥和包钥各只有一把，存放在单台 Windows 的 DPAPI 中；桥接轮换未实现；没有备份和恢复演练记录 | 记录离线备份事实；在 0.2.x 路线中排入 bridge_releases |
| W4 | 低 | scripts/prepare-update-catalog.py:50-53 | 序号单调性只和本地 `--output/current.json` 比较；换机器签发可能签出低于线上的序号，部署后客户端以 UPDATE_REPLAY 拒绝 | 必须提供线上 current.json 作为基线才允许签名 |
| W5 | 低 | prepare-update-catalog.py:43 | protocols、schema、min_updater 写死；0.2.0 若改 schema 需同步修改 | 从 manifest 或常量读取 |
| W6 | 建议 | tauri.conf.json:71 与 config/update-trust.json | 包公钥存了两份，唯一的比对测试是 `#[ignore]` | 加一个普通单元测试断言两处相等 |
| W7 | 低 | website/index.html:51、130-166；6-10 | nav aria-label 没有 i18n；示意图文字固定为中文；缺 canonical 和 og:image | 补 i18n 或注明为固定示意；补 meta |
| W8 | 低 | scripts/package-server.sh:5,9,25 | server 版本 0.1.4 写死；打包前不清理旧目录 | 版本从源码读取；打包前 rm -rf |
| W9 | 低 | scripts/validate-local.sh | 没有 docs/website/secrets 入口；desktop 只做 cargo check | 增加子命令 |
| W10 | 中 | spec/README.md:3；djhx-memo-project-plan.md:2-4,24-26 | 索引页头仍写 0.1.8；项目计划缺 0.1.9/0.1.10，状态行停在 11-04；ADR-0015/0001 状态过期 | 一并更新 |
| W11 | 低 | quality-audit-0.1.10-verification.md:128 | 含本机用户路径 | 改为 `%USERPROFILE%` |

- **凭据**：
  - 全部 Git 历史中没有私钥、令牌或密码赋值；`memo.djhx.site.key` 被 `.gitignore` 忽略，从未进入历史。
  - 但它是放在工作目录里的明文部署 SSH 信息，建议移出仓库目录。
- **更新链已核对的机制**：
  - 信封验签不允许 legacy 格式，key_id 必须在信任表内；有效期上限 90 天；拒绝更低序号。
  - URL 白名单；下载后检查大小和 SHA256。
  - Windows 校验 minisign trusted comment；Android 核对证书摘要和 versionCode 递增。
- **分支**：26 个本地分支已合并入 main，可以删除（`git branch --merged main` 列出的除 main 外全部）。`codex/docs-audit-corrections-next-client` 和 `codex/review-pr-27-doc-accuracy` 等当前 PR 合并后再删。

---

## 7. 调研分支意见

### 7.1 `codex/docs-client-020-predevelopment`（0.2.0 开发准备）

**做得好的地方**
- 不变量写得清楚：不改正文和语义、不改应用身份、不在 UI 层重写同步规则。
- 区分了"取消 Kotlin Job"与"中断 JNI 阻塞的 HTTP"，也区分了"页面 busy"与"安装屏障"。
- 安装目录表覆盖了 `/D`、`/UPDATE`、静默安装。
- M2 坚持先测量，并按每组 30 个样本计算。
- 源码引用经核对属实（SourceEditor.kt:70、MarkdownPreview.kt:39-42、Editor.svelte:59、installer.nsi INSTALLIDENTITY）。

**问题**
1. 五项横跨性能、会话并发（需交叉评审）、排版、品牌、安装器，验收方式各不相同，任何一项卡住都会拖住发版。
2. 验收不可度量：
   - spec §2 只写"修复长时间停顿"；
   - ≤2s 和 ≤200ms 只出现在开发文档里，"可交互"没有操作定义；
   - arm64 参考设备未指定；
   - TYPE-01、BRAND-01 是主观判定，没有指定评审人和对照稿。
3. spec 草案里仍有"建议拒绝""候选阈值"等未决措辞，spec 应写规范性语句。
4. 与现行契约的衔接：
   - `spec/product-v1.md:50` 的最低高亮要求需要加大笔记降级例外；
   - `scripts/brand_assets.py:144-174` 断言旧路径和身份，新目录规则应新增检查，而不是放宽旧检查。
5. `client-next-discussion.md` 正文仍写"保留满意的 Android/托盘""A/B/C 墨绿候选"，只在页首用一句话推翻，容易被误读。
6. 图标工作量被低估：
   - `brand_assets.py:90-112` 只消费预渲染资源包，而 ADR-0010 记录的 ImageMagick MSVG 对 jade SVG 的渐变、stroke、transform 支持有限；
   - jade SVG 也没有入库。
   - 因此"完整资源包 vs 新增渲染器（需 ADR）"是必须先做的决定。

**给开发人员**
- 先给 SourceEditor 打点，验证"跳过正则和 span 后原生布局是否仍慢"，结论决定 W1 的规模。
- W2 先画状态机（idle/loading/data/error/stale，按 profile+session+request 绑定），再交叉评审。
- W3 排版依赖 W1 的装饰预算，必须排在 W1 之后。
- W4 新增 `logo-pack-v2` profile 和检查，不改 v1 白名单。
- W5 先写 NSIS 测试矩阵，再改模板。
- 0.2.0 期间顺带修 §1 的 X1-X3，以及 A1/A3/A4、D2/D3。

**给产品设计**
- 建议拆为 0.2.0（M2、离线、排版）和 0.2.1（jade-ivory 图标、新装目录），或至少写明"品牌与目录可单独延期，不阻塞性能发版"。
- 把"可交互"定义为"首个输入字符 ≤N ms 回显，且主线程没有超过 X ms 的停顿"，并写进 spec。
- 大笔记降级提示的文案和出现时机要定稿。
- 在 spec 中显式记录"维护者此前满意的 Android 图标将被替换"这一反转。

**给美术设计**
- 按 logo-pack-v1 的结构交付 jade-ivory 完整资源包：
  - 桌面 16/24/32/48/64/128/256 px，小尺寸手工光学调整；
  - 多尺寸 ICO；
  - Android 满幅背景层和安全区前景层（108dp/66dp，stroke 计入边界）；
  - themed 单色层；
  - favicon。
- 在浅色和深色任务栏、Linux dock、OEM 圆形/方形遮罩上出对照稿，重点确认深绿底上的墨滴在 16-24 px 时仍可辨识。托盘黑白资源不动。
- 排版：用同一段中英长文出三组样例（长行折行、段内单换行、空行分段），行高覆盖 1.60/1.70/1.75，段距 0/0.3/0.5em，供维护者一次选定。

### 7.2 `research/cli-cross-platform`（CLI 调研）

**做得好的地方**
- 定位为"薄壳复用 core、不在 UI 层实现同步"，符合 AGENTS.md。
- 状态写明"未实现、不属于 v1"。
- 密码不走 flag 或环境变量；stdout 放数据、stderr 放消息；`edit` 遇到代次冲突会写救援文件。

**问题**
1. 产品适配：v1 尚未完成，CLI 相当于第四端（打包、更新、测试、评审），没有需求依据。
2. 命名冲突：主名 `djhx-memo` 已是桌面 mainBinaryName 和 Linux 包名（`scripts/brand_assets.py:144-146` 有断言），Linux 上会互相覆盖 `/usr/bin` 和 man page；别名 `memo` 过于通用。
3. 跨进程并发未定义：
   - core 只有 `busy_timeout`（`core/src/db.rs:15`）；
   - 桌面单实例靠 tauri single-instance 插件保证；
   - 更新屏障是进程内租约。
   - 因此 CLI 的 `sync` 与桌面后台同步可能同时推进 outbox，CLI 的连接也可能妨碍桌面更新时的 checkpoint；`--root` 默认值未定义。这一项属于同步变更，需交叉评审。
4. 凭据降级自相矛盾：headless 环境下 `save=false` 只存进程内存，而 CLI 每条命令都是新进程，`login` 之后凭据立即丢失。
5. 与现有契约不符：
   - `import` 写了"或恢复空间"，但 `spec/core-bridge.md` 的 import 只接受 local，恢复走 `import_recovery`；
   - 没有覆盖 `import_markdown` 和单篇导出。
6. 违反仓库规则：
   - clap、clap_complete、clap_mangen、cross、Docker 镜像都没有 ADR；
   - 调研用 rustc 1.100（项目基线 1.99.1），Windows 走 gnu/cross 而不是现有 MSVC 路线；
   - **调研文档含内网 IP 192.168.122.x、用户名和宿主机路径**（survey 第 20-26、50 行），违反 SECURITY.md；
   - draft 放在 `spec/` 并列入索引，容易被当作已接受契约。
7. 设计细节：
   - 退出码只有 0/1/2，无法区分鉴权、冲突、网络错误；
   - `--json` 错误结构未定义；
   - 没有 i18n 策略和 `NO_COLOR`；
   - purge 只接受单个 id，与 delete 不对称；
   - Windows `$EDITOR` 默认值和编码（UTF-8/BOM/CRLF）未定义；
   - 没有更新信任链和测试策略。

**建议**
- **产品**：v1 之前不立项。若需要，先写一页需求（用户、场景、频率），可考虑先做"只读 + 本地导出"的最小集。
- **开发**：
  - 删除内网信息后再合并，并把 draft 移到 `docs/research/`；
  - 改名（如 `djhx-memo-cli`）；
  - 先写跨进程并发 ADR（独占锁、检测到桌面运行时只读，或拒绝写入）；
  - headless 凭据要么明确"不支持"，要么设计加密文件存储并评审；
  - 补依赖 ADR；Windows 改用 MSVC 构建；
  - 细化退出码，定义 JSON 错误结构。
- **美术**：无直接工作。若推进，终端输出不使用品牌色或 emoji，并遵守 `NO_COLOR`。

---

## 8. 给设计师的 UI/UX 共性建议（三端）

1. **错误分级**：网络和同步错误改为区域内的行内或横幅提示，并提供"重试"；模态框只留给破坏性确认。同一错误不要反复弹出。
2. **本地保存与云同步分开表达**：两个状态并列、可见；同步进行中不冻结编辑区，必须冻结时要有明确的只读视觉提示。
3. **危险与不可逆操作**：吊销设备（尤其本机）、注销、彻底删除，确认文案和视觉要统一。导出遇到同名文件时，在保存对话框阶段就给出引导。
4. **可读性**：桌面大量 10-11px 的 `--muted` 文字对比度约 4.5:1，刚好压线，建议最小字号 12px，状态信息不要只靠颜色或圆点表达。冲突和设备详情不要直接显示 UUID。
5. **空态、加载态与表单**：首屏用加载态，避免闪现"还没有笔记"；空状态提供"新建 / 导入 Markdown"入口。登录表单提供显示密码切换和正确的键盘类型，失败时保留输入并在字段下方提示。
6. **无障碍**：标签页使用标准 tab 语义；开关与文字合并为一行；标题提供 heading 语义；只读预览允许选择和复制文字。
