# 0.2.7 开发方案（开发前 · 决策已定稿）

- 日期：2026-11-26；状态：**开发前评审已定稿（D1–D16 全部确认），未实施**。
- 目标行为以 [spec/client-0.2.7.md](../spec/client-0.2.7.md) 为准（本批已建）；本文供后续开发、测试、评审与发行使用。
- 基线：**v0.2.6**（客户端 0.2.6、Android code21、core 0.2.2、server 0.1.4、协议 2、local schema4 / server schema2、包名/证书/签名身份保持）。目标：客户端 0.2.7、Android code22、**core 0.2.3（新增 `batch_purge`）**；server 0.1.4、协议 2、schema 不变。

> 前提更正：当前代码基线是 v0.2.6（`desktop/package.json:4`、`android/app/build.gradle.kts:17-18`、`core/Cargo.toml:4`），0.2.4/0.2.5/0.2.6 是 0.2.3 设置交互热修。**v0.2.3–v0.2.6 均已公开发布**（GitHub Release、官网 current.json 与生产更新目录 seq17 均指向 v0.2.6），但 spec/docs 未记录 0.2.4–0.2.6（AGENTS.md、spec/README 仍写 0.2.2/0.2.3）。0.2.7 的本次增量是 **v0.2.6 → v0.2.7**，变更说明可列累计变化；D10 定：按“基线段注明”收口、不补独立验证记录，公开/入库分开记录。

## 1 复审结论与范围

六项改动经 3 个 Claude 子代理交叉评审（移动端预览规格、需求 1–4 代码可行性、规范/文档一致性）后，又经一次独立实施评审复核（核对本地 HEAD / v0.2.6 tag / GitHub main 三者一致、发行状态、断链与 core 接口细节）。结论：方向成立、无技术死路；已定稿 4 条前提性事实修正，其中 D1 使本轮从“纯客户端”变为“含一次 core 增量”。

| 项 | 复审结论 | 定稿取舍 |
|---|---|---|
| 0 基线 | 实际基线 v0.2.6 且已公开，spec/docs 未入库 0.2.4–0.2.6 | 按 v0.2.6 写基线；公开/入库分开记录（D10） |
| 1 回收站多选 | 批量恢复零 core 改动；批量彻底删除无 core 动作 | D1：做批量彻底删除 → 新增 `batch_purge` |
| 2 桌面行号 | `lineNumbers` 依赖已在、逻辑行号默认 | D4：不做开关，始终开启；暗色 gutter 覆盖；gutter 后重测同步滚动 |
| 3 标题目录 | 无现成解析器；两端标题识别需对齐 | D5–D7、D9、D12、D13 定稿，见 §3.3 |
| 4 登录设备状态 | 根因成立，桌面设备页无加载状态且切账号残留 | D11、D16：全修（状态 + 切账号清空 + 行内重试 + 会话边界） |
| 5 移动端预览美化 | 3 处阻断级缺陷 + 结构性欠账 | D8、D15 定稿删除线，修正清单见 §3.5 |

**前提性事实修正（评审已定稿）：**

- 目录无现成解析器（桌面 `renderMarkdown` 仅非 source 模式运行、Android 预览仅在预览可见时解析、core 的 `pulldown-cmark` 不处理标题）→ 两端各自复用已有解析入口、新写 `locateHeadings`，不新增解析器/依赖。
- `TextStyle.includeFontPadding` 在 Compose 1.7.5 已从 `TextStyle` 移除、默认已关闭（仍可经 `PlatformTextStyle` 显式设置）→ 删除“设置 includeFontPadding=false”要求。
- “代码块不做语法高亮”会回退 0.2.0 已发布能力 → 只改容器样式，保留 `CodeHighlights` 着色。
- “支持动态取色”超出范围且冲品牌色 → 改为“从 `MaterialTheme.colorScheme` 取值、不启用 Material You”。

## 2 已定稿决策记录

| 编号 | 决策 | 定稿结论 |
|---|---|---|
| D1 | 回收站“批量删除”语义 | = **批量彻底删除**，新增 core `batch_purge`（见 §3.1） |
| D2 | 批量恢复撤销 | 提供“已恢复 N 条 · 撤销”，撤销 = 重新移回回收站 |
| D3 | 全选范围 | 全选包含所有可见条目（含 `pending`/`rejected`；`purged` 本不在列表） |
| D4 | 桌面行号开关 | 不做开关，始终开启 |
| D5 | 桌面目录面板 | 默认关闭、图标展开/折叠；窄窗口自动改浮层、不改偏好；只读；不重建编辑器/丢状态 |
| D6 | 目录跳转 | 只滚动，不移动光标 |
| D7 | 模式按端定义 + 持久化 | 桌面 source/preview/split、Android source/preview；桌面模式全局持久化；窄窗口分屏临时降级为 `source`、不改偏好 |
| D8 | 删除线 | 做，ADR-0008 依赖表追一行（`commonmark-ext-gfm-strikethrough 0.24.0`） |
| D9 | 目录图标 | 新增 Lucide `table-of-contents`（走 ADR-0011 图标管线） |
| D10 | 0.2.4–0.2.6 缺口 | 按“基线段注明”收口，不补独立验证记录；公开与入库分开记录 |
| D11 | 设备页连带修复 | 一并修切账号残留旧列表 + 行内错误 + 重试 |
| D12 | Android 只读笔记目录跳转 | 只读笔记（已删除/恢复空间）**隐藏目录入口** |
| D13 | 目录标题识别一致性 | **共享识别范围**（ATX + setext，排除代码围栏/引用/列表内标题）+ 纯文本标签规则 |
| D14 | 是否本批建 spec | 本批已建 spec/client-0.2.7.md + releases/0.2.7-validation.md 占位 |
| D15 | 删除线波浪线范围 | 跟随扩展默认，`~~` 与 `~` 均识别 |
| D16 | 设备页 profile 锚点 | 前端显式传 `profile_id` + 会话序号 + 回执校验，不扩 core；纳入独立鉴权评审 |

## 3 逐项设计

### 3.1 回收站多选 + 批量彻底删除（桌面 + 移动，含 core 增量）

**core 增量（D1）**：新增 action `batch_purge`，协议形状复用 `batch_set_deleted`（`items[{id,expected_generation}] → results[{id,ok,error?,purge_state?}]`）。**逐项复用 `purge::request`（每项独立 Immediate 事务，与现有 `batch_set_deleted` 逐项开库一致，非单事务）**；回执用**精简视图**（仅 id/ok/error/purge_state，不含正文/标题/冲突，避免 10_000 项回执体积）。`profile_id` 经既有 `self.profile()` 校验（[lib.rs:221-239](../core/src/lib.rs#L221-L239)），与选中空间不符返回 `PROFILE_CHANGED`；重复 id 返回 `INVALID_REQUEST`；上限 10_000。连带：core 0.2.2→0.2.3、`spec/core-api.md`+`spec/core-bridge.md` 请求表加一行、修订 `permanent-deletion.md:7` 与 `client-0.1.6.md:16`、AGENTS.md 禁止事项第 21 行、按 AGENTS.md:13 属删除语义变更须独立交叉评审、重建 Android UniFFI/JNI 与桌面绑定。

**幂等语义与计数**：`purge::request` 顺序是 已 `purged` → 幂等 Ok（代次检查前）；代次不符 → `LOCAL_GENERATION_CONFLICT`；非回收站 → `PURGE_CONFLICT`；已 `pending` → 幂等 Ok（**不重复入队、不计为本次新请求**）。UI 按回执 `purge_state` 区分 `purged`=已彻底删除、`pending`=已请求，报“已彻底删除 X · 已请求 Y”（账号空间）或“已彻底删除 N”（本机空间）。**`permanent-deletion.md:15` 的 pending “禁止重复请求”须补批量幂等回执例外**。

**冻结快照与确认**：二次确认在**打开确认框时**冻结 `(profile_id, id, generation)` 快照（与 Android 单条 purge 一致，[MainActivity.kt:435](../android/app/src/main/java/app/djhx/memo/MainActivity.kt#L435)），确认时按快照提交；确认期间 1s 轮询刷新不得改变提交范围。提交前走既有 flush/IME 屏障（照 [App.svelte:165](../desktop/src/App.svelte#L165)）。

**批量恢复（零 core 改动）**：`batch_set_deleted(deleted:false)`。撤销 = 把刚恢复条目重新 `deleted:true`，**仅处理成功项、使用恢复成功后返回的代次**；撤销组与普通删除/Android 左滑撤销组需定义替换规则（避免混入相反方向）。`undoGroup`/`TrashUndo` 泛化为带方向（`deleted` 值），不能再写死 `deleted:false`（现 [App.svelte:201](../desktop/src/App.svelte#L201)、[MemoModel.kt:182](../android/app/src/main/java/app/djhx/memo/MemoModel.kt#L182)）。

**当前编辑笔记的关闭条件**：批次含当前编辑笔记时，仅当成功进入 `pending`/`purged`（彻底删除）或恢复成功（恢复）才关闭编辑器；失败或代次冲突的当前笔记保留编辑器（否则 `flush()` 撞 `NOTE_PURGED` 或丢输入）。

**全选语义（D3）**：全选勾选所有可见条目（含 `pending`/`rejected`；`purged` 已被 `list(deleted=true)` 过滤）。恢复时 `pending` 返回 `PURGE_PENDING` 计入失败提示、`rejected` 重置为 `none` 正常恢复；彻底删除时 `pending` 幂等成功、`rejected` 正常执行。`pending` 条目置灰标记但可勾选。

**入口与工具栏**：

- 桌面：放开 `{#if !trash}`（[App.svelte:1305](../desktop/src/App.svelte#L1305)）；回收站模式工具栏 = 取消 + 计数 + 全选 + **恢复（`rotate-ccw`）** + **彻底删除（`trash-2`，danger，二次确认）**。入口唯一（顶部 ListChecks，不在“更多”菜单重复，保持 [client-0.2.3.md:41](../spec/client-0.2.3.md#L41)）。
- Android：`toggleSelection` 的 `trash` 拦截改为只拦 `read_only`（[MemoModel.kt:103-108](../android/app/src/main/java/app/djhx/memo/MemoModel.kt#L103-L108)）；顶栏 selecting 工具栏加恢复 + 彻底删除；左滑禁用保持（[MainActivity.kt:840](../android/app/src/main/java/app/djhx/memo/MainActivity.kt#L840) 现 `!model.trash`）。
- 已发现的坑：桌面 `BatchResult` 类型缺 `error` 字段（[App.svelte:138](../desktop/src/App.svelte#L138)），按原因提示须先补；`set_deleted` 对 `note.deleted == deleted` 返回 `LOCAL_GENERATION_CONFLICT` 而非幂等，撞“已被别端恢复”时文案须容忍。

### 3.2 桌面编辑区行号（D4：无开关，始终开启）

- [Editor.svelte:306-366](../desktop/src/Editor.svelte#L306-L366) 扩展数组加 `lineNumbers()`（`@codemirror/view` 已依赖，无新依赖）；软换行按逻辑行编号（默认行为），写入规范防改成视觉行。
- **暗色 gutter 覆盖（必须）**：现有 `theme` 未传 `{dark:true}`（[Editor.svelte:129-141](../desktop/src/Editor.svelte#L129-L141)），暗色会命中 base 亮色 gutter。显式加 `.cm-gutters { backgroundColor:'transparent', color:'var(--muted)', borderRight:'1px solid var(--border)' }` 与 `.cm-lineNumbers .cm-gutterElement { color:'var(--muted)' }`。跟随主题走 CSS 变量，不引入硬编码色（对齐 [product-v1.md:121](../spec/product-v1.md#L121)）。
- **同步滚动重测**：行号 gutter 会缩窄正文、改变软换行折行点与块高度，`splitScroll`（[split-scroll.ts:95](../desktop/src/lib/split-scroll.ts#L95)）须在 gutter 就绪/布局变化后重新测量，不能假设“不受影响”。
- 不做 `highlightActiveLineGutter()`（避免再引入一处暗色覆盖）。

### 3.3 Markdown 标题目录（桌面 + 移动）

**解析与识别范围（D13）**：目录是派生展示，两端采用**共享识别范围**——仅 ATX（`#{1,6} `）与 setext（`===`/`---`）标题，排除代码围栏、引用/列表内标题；目录标签取纯文本（剥离行内加粗/斜体/链接/行内代码标记，解码 HTML 实体）。桌面用 `marked.lexer(source)`（heading token），Android 用 commonmark AST（`Heading` + `sourceSpans.lineIndex`），两端各自新写、不新增解析器/依赖。**必须对 `\r\n→\n`（含单独 `\r`）归一化后的文本计算偏移**。独立防抖解析放 `MemoModel`（`Dispatchers.Default` + 版本号作废）。

**跳转（D6：只滚动、不动光标）**：

- 桌面 source：`EditorView.scrollIntoView(pos, {y:'center'})` effect（**不用** selection dispatch），带 lint 同款守卫 `if (view.state.doc.toString() !== value.replace(...)) return`（[Editor.svelte:454-464](../desktop/src/Editor.svelte#L454-L464)）。split 滚源码、开同步滚动时预览自动跟。
- 桌面 preview：走预览块 `data-source-from`（[markdown.ts:136-139](../desktop/src/lib/markdown.ts#L136-L139)）`querySelector('[data-source-from="N"]').scrollIntoView()`；须复用生成 `data-source-from` 的同一套解析与行尾归一化。
- Android source：逻辑行 `lineIndex` 先经 `content.lines()` 前缀和换算字符 offset，再 `layout.getLineForOffset(offset)` + `viewport.scrollTo(0, layout.getLineTop(line)+paddingTop)`（照 [SourceFind.kt:191-202](../android/app/src/main/java/app/djhx/memo/SourceFind.kt#L191-L202)，只 scrollTo 不动 `Selection`）。`SourceFindHost` 不外露 → `MemoModel` 加 `outlineTarget:(offset, serial, note_id)` + `SourceEditor` 内 `LaunchedEffect(target)` 消费（serial 区分重复点击，note_id 判断是否跨笔记过期）。
- Android preview：**无定位机制**且只读笔记强制走预览。定稿：普通可写笔记 preview 态点目录先切回 source 再跳（等视图/布局就绪后按笔记身份 + 正文快照消费）；**只读笔记（已删除/恢复空间）本版隐藏目录入口（D12）**。

**面板/弹层**：

- 桌面（D5）：工具栏 `table-of-contents` 图标开关；面板默认关闭、可展开/折叠；层级缩进按 h1–h6；只读、不改正文/保存/同步。**先修 CSS**：`.editor-body.split > * { width:50% }`（[style.css:451-453](../desktop/src/style.css#L451-L453)）改为只对 `.source-pane`/`.preview-pane` 生效。面板放 `{#key documentKey}` 的 `Editor` 外层，经 `sourceView`（[App.svelte:289](../desktop/src/App.svelte#L289) + `onView`）跳转，**不重建编辑器、不丢 IME/撤销/选区**。窄窗口（沿用现有 850px 媒体查询，实测冻结）自动改浮层，不改偏好、加宽恢复。
- Android：`ModalBottomSheet`（material3 1.3.0），`rememberModalBottomSheetState(skipPartiallyExpanded=false)` + 内容 `fillMaxHeight(0.5~0.8)`；右上角“×”；返回键/手势由 sheet 自身 dismiss（天然先关弹层）。入口进 ellipsis 菜单（[MainActivity.kt:299-358](../android/app/src/main/java/app/djhx/memo/MainActivity.kt#L299-L358)）。空目录/无标题空态两端加文案。

**持久化（D7）**：

- 桌面模式：新增 `Preferences.editorMode`（`'source'|'preview'|'split'`），全局一个值、与主题/语言同级，`loadPreferences` 加白名单分支；不写笔记、不参与同步。**只持久化用户主动选择的模式**；`Ctrl+F`/全局搜索跳转/lint 触发的自动切 source 是临时态、不写回。窄窗口分屏临时降级为 `source`（D7 定稿），不改已保存偏好，加宽恢复。
- 桌面目录开关：作为展示偏好持久化（`Preferences.outlineVisible` 或照 `djhx-memo.note-list.v1` 单独 key），默认关闭。
- Android 弹层：`rememberSaveable` 不持久化（定稿），**旋转恢复当前弹层、每次进编辑页默认关闭**。

**图标（D9）**：新增 Lucide `table-of-contents`（已核实固定提交 `b826f0c` 存在），走 `assets/ui-icons/lucide/*.svg` → `UPSTREAM.json`（files + 逐文件 SHA256）→ `python3 scripts/generate-ui-icons.py` + `--check` → ADR-0011 末尾追一行。两端共用，无运行时依赖。

### 3.4 登录设备加载状态（桌面，D11、D16）

**根因（已确认）**：设备 tab 走 `run(() => openSettings('devices'))` → 全局 `busy=true` → `await call({action:'devices'})`（同步网络 GET）→ `.note-card` 的 `disabled={busy}` 命中 `button:disabled{opacity:0.42}` → 半透明 `.modal-backdrop` 透出“列表变暗再恢复”。连带：拉取期间编辑器变只读、状态轮询停摆、`run()` 静默吞点击、`revoke` 双网络请求闪更久。

**设计（对齐 Android [MemoModel.kt:770-838](../android/app/src/main/java/app/djhx/memo/MemoModel.kt#L770-L838)）**：

- `openSettings` 不再 await 网络，末尾 `void loadDevices()`；新增 `devicesLoading/devicesRefreshing/devicesError/devicesStale/devicesSession（自增）/revoking/revokeError`。`loadDevices()` 自 try/catch，不写全局 `failure`。
- **会话边界（D16）**：`devices`/`revoke_device` 请求**显式携带发起时 `profile_id`**（现 [App.svelte:834](../desktop/src/App.svelte#L834)、[App.svelte:848](../desktop/src/App.svelte#L848) 未传），复用 core 既有 `self.profile()` 校验——空间不符返回 `PROFILE_CHANGED`（[lib.rs:221-239](../core/src/lib.rs#L221-L239)）。回执按 `devicesSession` 序号 + 回执时 profile 双重作废；覆盖注销、切空间、同账号重新登录与重复刷新上限。**不扩 core**，纳入独立鉴权交叉评审。
- 设备区三态：无缓存→加载态；有缓存→后台刷新不闪空；失败→保留旧列表 + “未刷新” + 行内重试（D11）。空态 reload 按钮改调 `loadDevices()`。
- **切账号清空（D11）**：`switchProfile()`、`authenticate()`、`refreshState` 的 profile 变更分支加 `invalidateDevices()`（现仅 `logout` 清）。
- **自吊销**：吊销当前设备后 core 清凭据（[lib.rs:472-474](../core/src/lib.rs#L472-L474)），随后 GET 可能返回 `AUTH_REQUIRED`；呈现“吊销成功 + 需重新登录”并保留离线编辑。吊销成功与随后刷新失败是两个独立结果。
- 规范层面：把 [client-0.2.0.md §3.1](../spec/client-0.2.0.md) 的 Devices 状态表适用范围扩展到 Windows/Linux，不另写一套措辞。`devicesHelp` 的 ⓘ 结构与文本选择（0.2.6 已修）不得回归。

### 3.5 移动端预览美化（MarkdownStyle，D8、D15）

**范围与安全边界**：仅改 Android `MarkdownPreview.kt`/`MarkdownSafety.kt` 排版与配色，不改 core/正文/标题派生规则。`product-v1.md:55` 安全边界逐条保持（HTML 仅文本、图片仅占位、`safeLink` 白名单）。桌面预览不变（三端不对称写入已知项）。

**结构**：`@Immutable data class MarkdownStyle` + `LocalMarkdownStyle`；派生点放 `MemoPresentation` 的 `CompositionLocalProvider` 内（或 `@Composable fun rememberMarkdownStyle()` 带不崩默认值），**不要**用 `staticCompositionLocalOf { error(...) }`（默认值 lambda 非 @Composable）。全部取值从 `MaterialTheme.colorScheme`/`typography` 派生，不写死色值、不启用 Material You。语法色留在 `CodeKind` 不动，`MarkdownStyle` 不覆盖。

**样式令牌（阶梯见 [spec §6](../spec/client-0.2.7.md)）**：正文 16/27·Normal·onSurface·letterSpacing 0.2sp；H1 28/36、H2 22/30、H3 18/26、H4 16/24、H5 14/20、H6 14/20（H5/H6 onSurfaceVariant）。**不写 `includeFontPadding`**（Compose 1.7.5 默认已关闭，仍可经 `PlatformTextStyle` 显式设置）。显式 `lineHeightStyle = LineHeightStyle(Alignment.Center, Trim.None)`（1.7.5 两参构造）；`lineBreak = LineBreak.Heading/Paragraph`（minSdk 29 生效，低于 29 静默降级写入规范）。Emphasis `Italic`、StrongEmphasis `SemiBold`（现 Bold 需改）。链接 primary+下划线，`TextDecoration.combine` 合并装饰，点击走 `safeLink`。行内代码 0.9 倍字号、`surfaceContainerHighest` 背景。代码块等宽 13/20、`surfaceContainer`、圆角 8dp、内边距 12dp、`softWrap=false + horizontalScroll`、**保留语法高亮**；每代码块各自 `rememberScrollState()` 且 `key(block.sourceSpans.firstOrNull()?.lineIndex)` 包裹。引用左侧 3dp `outlineVariant` 竖线 + 左缩进 12dp + `onSurfaceVariant`（现无竖线）。列表 `isTight()` 区分紧凑 4dp/松散 8dp、无序每级缩进 20dp + `depth%3` 循环 `•/◦/▪`、有序右对齐按最大序号位数计算（修 32dp 裁切三位数 bug）、`fontFeatureSettings="tnum"`；“每级缩进 20dp 与现有 32dp 标记列关系”在规范用盒模型钉死。任务项**图形（~20dp）与可操作区分离**：`LocalMinimumInteractiveComponentSize` 只包 Checkbox（provide `Dp.Unspecified`，不压到 24dp 以下），验证紧凑列表相邻任务点击区、TalkBack、只读禁用。已完成项 `onSurfaceVariant`+`LineThrough`（新增行为，深浅两主题各验收对比度）。分隔线 1dp `outlineVariant`。HTML 保持纯文本。**表格不在范围**（无 tables 扩展，死规格删除）；图片继续占位。

**删除线（D8、D15）**：新增 `org.commonmark:commonmark-ext-gfm-strikethrough 0.24.0`（BSD-2-Clause，与 commonmark 同源同版本），在 `MarkdownSafety.parse` 注册 extension，`Strikethrough` 渲染 `LineThrough`；**`~~` 与 `~` 均识别**（扩展默认）。连带：ADR-0008 依赖表追一行（用户已提供粘贴行）+ 文末变更记录、更新 `gradle.lockfile`、重新生成 APK 依赖许可通知、`docs/licensing.md` 复核。**“已完成任务项加删除线”用 `LineThrough` 即可，不依赖该扩展。** 同批修订 [product-v1.md:51](../spec/product-v1.md#L51) 把删除线加入保证集（此前桌面 `marked gfm:true` 已事实渲染 `<del>`，本版对齐两端）。

**间距模型（结构性改造）**：移除全部 `Arrangement.spacedBy`（现顶层 8dp、列表层 6dp），改每块 `Modifier.padding(top=spaceBefore)` 自管；每个块容器首子块 `spaceBefore=0`。**写成可验收规则**：标题后距与下一块前距合并、紧凑/松散列表覆盖通用段落间距、深层列表在窄屏大字号下正文列不被挤空。段落 12/0；H1 28/12、H2 24/8、H3 20/6、H4–H6 16/4；标题紧跟标题减半（最小 8）需前驱兄弟块 → `children().forEachIndexed` + `previousBlock`。渲染上下文收敛为 `@Immutable data class MarkdownContext(depth, previous, tight, markerWidth, …)`（否则参数撞 detekt `LongParameterList:10`）。页面水平边距 20–24dp、宽屏最大 640dp 居中、顶部底部 24dp。`isLargeNote`（沿用 128 KiB 判断）时至少关闭 `LineBreak.Paragraph`。`@Immutable` + 全稳定字段，避免 `MarkdownText` 的 `remember` 每帧 cache miss。

## 4 验收矩阵

实际结果以 [0.2.7 验证记录](../docs/releases/0.2.7-validation.md) 逐项区分；Windows/Linux 分别记录，Android 单独记录；预览美化深浅双主题各截图。

| ID | 场景 | 必须满足 |
|---|---|---|
| T01 | 两端回收站多选：进入/退出、全选（含 pending/rejected）、恢复 | 入口唯一；恢复提供“已恢复 N 条·撤销”；pending 恢复失败计入提示 |
| T02 | 两端批量彻底删除（含账号空间 pending） | 打开确认框冻结快照；“已彻底删除 X·已请求 Y”；不可撤销；含当前编辑笔记时按结果关闭/保留编辑器 |
| T03 | 批量恢复撤销 | 撤销=重新移回回收站，方向正确；仅成功项、用成功后代次；部分失败/撤销前远端修改/切空间/离线重启 |
| L01 | 桌面行号逻辑行、暗色 gutter、窄窗/split、与 lint/Ctrl+F 叠加 | 逻辑行号；暗色无亮灰 gutter；行号两位跨三位长笔记下滚动/查找定位不漂移；同步滚动重测 |
| O01 | 两端目录解析（共享识别范围、代码围栏排除、blockquote/list 内标题不入） | 两端一致；CRLF/单独 CR 偏移正确；纯文本标签剥离行内标记与实体 |
| O02 | 两端点击跳转（source/preview/split；Android 只读隐藏入口）只滚动不动光标 | 跳转准确；异步偏移过期有守卫（note_id+正文快照）；IME 不中断；光标/选区不动 |
| O03 | 桌面目录面板（默认关、浮层、持久化）/ Android 底部弹层（返回键先关） | 模式三态可见；窄窗浮层不改偏好、加宽恢复；旋转恢复弹层；不重建编辑器 |
| D01 | 桌面设备页三态、切账号清空、失败行内重试、自吊销 | 不闪主页面；切账号无残留；失败不弹全局错误；自吊销后“吊销成功+需重新登录”并保留离线编辑；ⓘ/文本选择不回归 |
| P01 | 移动端预览深浅双主题、紧凑/松散列表、三位数有序列表、已完成项、删除线 | 符合阶梯；无魔法数字；语法高亮保留；删除线×链接/强调叠加；任务勾选改对源码行；代码块独立横向滚动；不加载外部图片/HTML；大笔记 128 KiB/1 MiB 抽样 |

## 5 规范与文档同步清单（同批提交）

1. 新建 [spec/client-0.2.7.md](../spec/client-0.2.7.md)（本批已建）：基线 v0.2.6、版本行、§1 范围表、§2–§6 逐项契约、§7 验证交付；显式写出“本条替代/澄清”的条文编号。
2. 修订 [spec/client-0.1.6.md](../spec/client-0.1.6.md)：§1 回收站多选放开、批量彻底删除由“不提供”改为“0.2.7 提供”；撤销契约扩写批量恢复撤销（仅成功项、成功后代次）。
3. 修订 [spec/product-v1.md](../spec/product-v1.md)：§4 新增标题目录为派生展示能力 + 删除线语法（D8）；§11 第 135 条澄清桌面目录面板、第 134 条澄清移动端底部弹层；§6 第 87 行同步批量彻底删除。
4. 修订 [spec/permanent-deletion.md](../spec/permanent-deletion.md)：§1 由“仅单条/不提供批量清空”改为“0.2.7 提供回收站批量彻底删除（逐条代次校验，不可撤销）”；§2 明确 pending“禁止重复请求”在批量幂等回执下的例外（已入队条目幂等 Ok、不计新请求）。
5. 修订 [spec/core-api.md](../spec/core-api.md) + [spec/core-bridge.md](../spec/core-bridge.md)：新增 `batch_purge` 请求行（精简回执字段）。
6. 修订 [spec/client-0.2.0.md](../spec/client-0.2.0.md) §3.1：Devices 状态表适用范围扩展到 Windows/Linux。
7. 修订 [AGENTS.md](../AGENTS.md)：第 6 行当前状态段更新为 0.2.7；禁止事项第 21 行措辞随批量彻底删除同步；追加 0.2.7 条目。
8. 补齐 0.2.4–0.2.6 入库缺口（D10）：`AGENTS.md`、`spec/README.md` 顶部基线段与索引表、`docs/validation.md` 基线行写明“0.2.4/0.2.5/0.2.6 为 0.2.3 设置交互热修、已公开发布”；为旧规范（0.1.6/permanent-deletion 等）加版本适用范围、保留原版历史行为。
9. 索引：`spec/README.md` 新增 client-0.2.7 行；`docs/validation.md` 更新基线行。
10. [docs/ui-design.md](../docs/ui-design.md) 新增“0.2.7 回收站多选、行号与标题目录”节。
11. [docs/adr/0011-offline-interface-icons.md](../docs/adr/0011-offline-interface-icons.md) 末尾追一行（`table-of-contents`，固定提交 `b826f0c` 核实）。
12. [docs/adr/0008-android-dependencies.md](../docs/adr/0008-android-dependencies.md) 依赖表追一行 + 变更记录 + `gradle.lockfile` + APK 许可通知 + [docs/licensing.md](../docs/licensing.md) 复核（删除线）。
13. 新建 [docs/releases/0.2.7-validation.md](../docs/releases/0.2.7-validation.md)（本批已建占位）：矩阵逐项“未执行/通过/失败”、本地检查表、未验收项；写明本次交付含 0.2.3–0.2.7 累计变更、本次增量 v0.2.6 → v0.2.7。

## 6 实施顺序与交付

1. core 先行：`batch_purge`（含删除语义独立交叉评审）→ 重建 JNI/UniFFI → 两端 UI 批量彻底删除；批量恢复与撤销可并行在两端 UI 层做（零 core 依赖）。
2. 其余客户端项：需求 4（登录设备状态，含鉴权并发独立评审）→ 需求 2（行号）→ 需求 3（目录，含图标管线）→ 需求 5（预览美化，先加删除线依赖 + lockfile + 许可）。
3. 检查清单：`bash scripts/validate-local.sh core`（batch_purge 场景）+ `bash scripts/validate-local.sh integration`（批量 purge 隔离联调：原协议逐笔收敛、冲突拒绝、原 ID 不复活）；桌面 `npm run check` + `npm run lint -- --max-warnings 0` + `npm run format:check` + `npm run test` + `npm run build`（validate-local.sh desktop 不含 lint/format，须显式执行）+ `bash scripts/validate-local.sh desktop`；`bash scripts/validate-local.sh android`（对比 detekt 17 项与 lint 警告数；`MarkdownContext` 收敛后 `LongParameterList` 不新增）；若动图标 `python3 scripts/generate-ui-icons.py --check`；`python3 scripts/check-docs.py`；`git diff --check`。
4. 版本锁定点名：桌面 npm/Tauri 版本与锁文件、core crate 与锁文件、Android code22 与双 ABI JNI。
5. 覆盖升级至少含当前公开 v0.2.6，Windows WebView2 / Linux GTK / Android 真机分别验收，不互相代替。公开/官网/更新目录推广由后续任务验收；本版不改下载源/更新目录字段。

**已决**：D1–D16 全部定稿，无遗留决策。
