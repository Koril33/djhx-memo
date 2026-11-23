# 搜索功能详细设计（0.1.11）

- 状态：§1–14 保留 0.1.11 设计与当时决定；功能已随 0.1.11 实现，当前代码为 0.1.12，实际验证边界见 [0.1.11 记录](releases/0.1.11-validation.md)和[0.1.13 记录](releases/0.1.13-validation.md)。**0.1.13 的定稿增量见 §15，未实施。** 本文为详细设计入口，产品行为以 spec 为准，后续变更同步维护两者。
- 关联文档：[快捷键设计](shortcuts-design.md)、[0.1.11 目标规范](../spec/client-0.1.11.md)、[v1 产品行为](../spec/product-v1.md)、[数据模型](../spec/data-model.md)、[UI 与 core 接口边界](../spec/core-api.md)。
- 范围：桌面（Windows/Linux，键盘快捷键主战场）与 Android（本版补充笔记内查找/全局搜索 UI，无键盘快捷键）；Android 侧范围见 §11。

## 1. 目标与非目标

### 1.1 目标

1. **笔记内查找（Ctrl+F）**：在当前打开的笔记内查找、命中高亮、next/prev 跳转。
2. **全局搜索（Ctrl+Shift+F）**：跨笔记搜索，命中高亮、跳转到命中位置、上下文片段、相关性排序。
3. **范围可控**：本机空间 / 账号空间 / 回收站。
4. **不限字数**：1 个字符即可搜（含 1～2 个中文字符），且性能达标，去除"短词是慢路径"的实际观感。
5. **纯本地**：第一版不做云端搜索，服务端不提供搜索接口；账号空间的搜索也基于本地同步副本。
6. **性能有实测**：用维护者 blog 语料（`C:\Project\blog`，180 篇 Markdown）做基准，延续"常用搜索 P95 ≤200ms"基线。

### 1.2 非目标（v1）

- 不做拼音、模糊、词义、语义搜索（延续 product-v1 §7）。
- 不把用户输入解释为 FTS 布尔表达式 / 正则（字面子串语义）。
- 不做 OS 级全局热键（应用不聚焦也能搜）。
- 不做服务端搜索 API。
- 不做替换（见 shortcuts-design §4.2）。
- 不做图片/附件内容搜索（本应用无附件）。

## 2. 现状盘点（仓库零散资料整合）

### 2.1 已有实现（core / desktop）

- **索引**：`core/migrations/0002_search.sql` 建 `notes_fts` 虚表，`tokenize='trigram'`，与 `notes` 同事务同步（insert/delete/update 触发器），`rebuild` 可重建。每个空间（本机 / 账号 / 恢复）各有独立 SQLite + 独立 `notes_fts`（data-model §3）。
- **查询**：`core/src/db.rs::list(db, query, deleted)`：
  - 字面子串：把 `\ % _` 转义后 `LIKE %escaped% ESCAPE '\'`，参数化，无注入。
  - `query.chars().count() >= 3 && 不含 % _ \` 时走 `rowid IN (SELECT rowid FROM notes_fts WHERE content LIKE ?)` 候选，否则全表 LIKE。
  - 排序 `ORDER BY updated_at DESC, id ASC`（稳定）。
  - `purge_state!='purged'` 过滤；`deleted` 参数区分普通列表与回收站。
- **core 入口**：`core/src/lib.rs` 的 `list` 动作：`{action:"list", query, deleted, profile_id}`，作用域**当前单个 profile**。
- **桌面 UI**：`desktop/src/App.svelte` 列表头搜索框（`bind:value=query` + 120ms 防抖 `search()` → `refreshNotes()` → `list`），结果只显示标题 + 前 90 字符摘要（`excerpt()`，**非命中居中片段**）。无命中高亮、无跳转到命中位置、无相关性、无笔记内查找。
- **Android**：`MainActivity.kt` 列表页搜索框（`model.query` → 同一 `list` 动作），同样只有标题过滤，无高亮/片段/笔记内查找。
- **测试**：`core/src/tests.rs::search_is_literal_short_chinese_ascii_and_tombstones_are_preserved` 验证 1～2 中文字、ASCII、`%`/`_`/引号/`OR` 字面匹配、回收站隔离。conformance `EDIT-01` 同义。

### 2.2 已有契约

- **product-v1 §7**：本地、字面子串，支持 1～2 中文字符、英文及混合；ASCII 大小写不敏感，其他 Unicode 按原字符；不做拼音/模糊/词义；FTS5 trigram 加速、短词回退；过滤 `deleted=false`；回收站可单独查看。
- **core-api**：`search_notes`（profile、literal_query、limit → 本地结果）已列在接口表中，但当前实现是 `list` 内嵌 query，命名与结构未对齐，本文 §5 统一。
- **性能基线**：product-v1 §9 与 client-0.2.0 §2——1,000 条、平均 2KiB，打开与常用搜索 P95 ≤200ms；需记录样本数/设备/系统，不用单次截图代替。

### 2.3 已知问题（来自 docs/reviews）

| 编号 | 来源 | 问题 | 影响 | 0.1.11 处理 |
|---|---|---|---|---|
| C4 | 静态评审 | `notes_update` 触发器在仅元数据更新（ack / set_deleted / purge_state）时也整篇删除重建 FTS 索引 | 1MiB 笔记每次同步多做 1-2 次重建 | 追加迁移 0004，触发器加 `WHEN old.content IS NOT new.content`（不改已发布迁移），实施见 §12 step 1 |
| C6 | 静态评审 | <3 字符（最常见 2 字中文）退化为全表 LIKE，读全部正文 | 2 字搜索总是全表扫描 | 短查询只投影 id+摘要；用 blog 基准确认达标，见 §8 |
| — | 本文 | 结果无相关性、无高亮、无片段、无笔记内查找 | 核心体验缺口 | 本文全部覆盖 |

## 3. 需求细化

| 用户需求 | 本文设计 |
|---|---|
| 控制范围（本机空间 / 账号空间 / 回收站） | 全局搜索范围选择器，§6 |
| 第一版不做云端搜索 | 纯本地，服务端无接口，§1.2 |
| 不限字数（原设计似限制中文字符数） | 1 字符可搜且达标；`≥3` 仅是内部加速阈值，非用户限制，§4.2、§8 |
| 排序与相关性排名 | 核心自定义打分 + 稳定平局，§4.3 |
| 命中高亮 | 核心返回命中区间，前端 `<mark>`，§4.4 |
| 跳到命中位置 | 选中结果 → 打开笔记并定位到首个命中，§5、§7.2 |
| 上下文片段 | 核心返回命中居中片段，§4.4 |
| Ctrl+F 笔记内搜索 | CodeMirror `@codemirror/search`，shortcuts-design §4 |
| Ctrl+Shift+F 全局搜索 | 全局搜索面板，shortcuts-design §5、本文 §7 |
| 快捷键不冲突、仅本应用聚焦 | shortcuts-design §2、§6、§7 |
| 性能实测（blog 语料） | §8 |

## 4. 引擎设计

### 4.1 总体：候选检索 + 核心打分（延续现有架构，最小扩展）

保持"FTS5 trigram 做候选、字面 LIKE 兜底"的两段式不变，新增**第三段：对候选正文做字面定位，产出相关性、命中区间与片段**。

- 规模前提：约 1,000～2,000 条笔记、正文合计约 2～5MB（blog 语料 180 篇）。这个量级下，即使是全表子串扫描也在毫秒级；不引入 Tantivy / ripgrep 等重型引擎。
- 全部逻辑在 core（Rust）侧，UI 不实现搜索/排序逻辑（遵守 AGENTS"UI 不实现同步或冲突规则"的同源原则，搜索裁决同样留在 core）。

### 4.2 查询与字面匹配（不限字数）

- 延续"字面子串"契约：不解释为 FTS 布尔/正则；`% _ \` 与引号按字面转义；ASCII 大小写不敏感（对 ASCII 区间做 case-fold 定位后映射回原偏移），其他 Unicode 按原字符。
- **无最小长度**：空 query 不触发搜索、由前端显示"最近编辑"空态；1 字符即执行。
- 候选检索策略（实现细节，与现状一致但明确语义）：
  - 查询经 case-fold 后：
    - 长度 ≥3 且不含需转义字符：`notes_fts WHERE content LIKE %q%` 候选（trigram 加速）。
    - 否则：直接对正文做子串扫描（短词路径）。
  - **为什么用 `LIKE` 而不用 `MATCH`**：FTS5 trigram 表原生支持对"简单子串"的 `LIKE`/`GLOB`（见官方说明），因此候选路径继续用参数化 `LIKE`，**不构造 FTS 查询语法**，从根上规避空格、`"`、`*`、`:`、`^` 等引起的 `MATCH` 语法错误/语义漂移/注入面（对应评审 R4）。匹配语义唯一来源是"字面子串 + ASCII case-fold"，两条路径（trigram 候选 / 短词全表）行为一致。
- 短词路径优化（对应 C6）：只投影 `rowid/id` 与正文（用于定位），避免无关列；如基准显示 2MB 级全表 LIKE 仍超标，再考虑内存缓存正文或 bigram 辅助索引（§8 决策点，不预判）。
- **大小写折叠仅 ASCII**：`A-Z ↔ a-z`，不做 Unicode 归一化（符合 product-v1 §7"ASCII 大小写不敏感，其他 Unicode 按原字符"）。
- **可选 smart case**（借鉴 Zed `use_smartcase_search`）：查询含大写 → 区分大小写，全小写 → 不区分。列为可选增强，v1 默认关闭（保持"ASCII 大小写不敏感"的简单可预期语义）。

### 4.3 相关性排序

候选集上做 Rust 侧打分（`score`），返回前按 `score DESC, updated_at DESC, note_id ASC`（沿用现状稳定平局，保证分页/截断稳定）。

打分因子（权重待实现时用 blog 语料校准，初值）：

1. **标题命中**（权重最高）：笔记首非空行（去行首 `#{1,6} `）含 query → 大幅加分。
2. **命中次数**：出现次数越多越高（对数/线性，避免长文刷分）。
3. **首个命中位置**：越靠前越高（对"开头就在说这个"的笔记更相关）。
4. **可选：词边界/邻近**：命中处前后是否空白/标点（英文词界），提升整词命中。
5. **平局**：`updated_at DESC, note_id ASC`。

不采用 FTS5 `bm25()` 的原因：bm25 与 trigram 组合表达的是"trigram 重叠度"，与"字面子串"契约语义不一致（Logseq 同款短查询噪声），且会牵出 FTS 查询语法的转义/注入面。自定义打分在小规模下可预测、可测试、可解释，且与"字面"契约完全一致。交叉评估（§10）结论与 Joplin/Trilium/Logseq 的收敛点一致：命中次数 + 标题加权 + 时间兜底。

### 4.4 命中高亮、片段与跳转

对候选正文执行字面子串定位（case-fold 后），产出**已切分的 segments**，而非裸偏移——这是消除跨语言偏移错位（Rust 字节 / JS UTF-16 / Kotlin UTF-16 三者不一致）的关键决策（对应评审 R2）：

- `segments: [{ text: string, hit: bool }]`：片段文本切分结果，`hit=true` 的段即命中子串，前端用 `<mark>` 直接包裹，**零偏移运算、零编码换算**；Android 侧用 `AnnotatedString` + `SpanStyle` 消费同一份 segments，收益翻倍。
- `snippet`：围绕**首个（或最佳）命中**的上下文窗口，前后各 N 个字符（候选 N=40），在词边界/空白处截断，UTF-8 边界安全。
- `title_segments`：标题的 segments（标题命中也高亮）。
- `hit_count`：命中次数（也用于 §4.3 排序）。

> **为什么不用 FTS5 的 `highlight()` / `snippet()` 辅助函数**：它们在 trigram 分词下按"三元组"标记命中，会与"字面子串"语义错位（过度高亮或跨字符错位），且需 `MATCH` 语法；更关键的是 <3 字符走全表 LIKE 路径时根本没有 FTS 行可用，两条路径会给出一致性各异的高亮。统一在 Rust 侧对正文做一次 case-fold 子串扫描、一次遍历同时产出 segments / snippet / hit_count，是唯一能保证两条路径契约一致的做法，且在小规模（~2MB）下开销可忽略。

**跳转**：用户选中命中 → 打开该笔记（`get` 取全文）→ 前端把 query 注入 CodeMirror 的搜索状态（`setSearchQuery` + `findNext`），由 CodeMirror 定位并滚动到首个命中、高亮全部命中。**不跨语言传偏移**，跳转与高亮全部由 CM 完成（"全局搜索 → 笔记内查找"串成一条链路，见评审衔接设计）。

### 4.5 召回与打分（全量召回，无 SQL 预截断）

- 召回：候选检索 SQL（trigram 候选 / 短词全表 LIKE）**不做 `LIMIT` 预截断**，返回所有字面匹配笔记的（id, content, updated_at, generation, deleted）。
- 打分：Rust 对每个候选计算 score（标题命中 / 命中次数 / 首命中位置），生成 segments / snippet（单片段，围绕首个命中）。
- 排序：跨空间合并后按 `score DESC, updated_at DESC, note_id ASC`，取 top 50。

不做 SQL `LIMIT 200` 预截断的原因：若按 `updated_at` 预截断，命中数 >200 时高分（如标题命中）笔记可能根本进不了候选，直接违反"标题命中 > 正文命中"的排序契约（§9 用例 4）。本规模（~1000 条 × 2KiB）下全量打分开销毫秒级（见 §8.4），无需预截断。结果 payload 仍小：只回 top 50、不返回全文。

### 4.6 结果结构（SearchHit）

跨空间统一返回精简结构，**不返回全文**（点开再 `get`）：

```text
SearchHit {
  note_id: string
  profile_id: string
  space_kind: "local" | "account"          // v1 全局搜索范围不含恢复空间
  deleted: bool
  title: string
  title_segments: [{ text, hit }]     // 标题高亮
  snippet: string
  segments: [{ text, hit }]           // 片段高亮（核心已切分，无偏移）
  hit_count: int
  score: number
  updated_at: string
  generation: string                  // 用于打开时的并发保护
}
```

## 5. core API

扩展 core-api.md 已列的 `search_notes`，新增 `search_notes` 动作（UniFFI 与 Tauri 包装命名随语言风格，行为一致；本文简称 `search`）。

```
search_notes {
  query: string                       // 字面子串，可为空
  scope: {
    kind: "current" | "all_local" | "account"   // 空间集合（见 §6）
    space_ids: string[] | null        // 显式指定空间（可选）
    include_deleted: bool             // 是否含回收站（deleted=true）
  }
}
→ {
  hits: [SearchHit]                   // 跨库合并排序后 top 50
  truncated: bool                     // 排序后命中数 >50 被截断
}
```

实现要点：

- 枚举目标 profile（由 scope + 空间注册表决定，**跳过 `kind=="recovery"`**），逐库执行 `db::search_notes`（候选检索 + 打分 + 片段），跨库合并排序后取 top 50。
- **不做 offset 分页**：v1 任何 scope 至多命中 1～2 个空间（一个本机 + 当前账号），单库 offset 本可下推；选"top 50 + `truncated` 提示"是产品取舍（结果少而精，避免分页一致性与高频词刷屏），非技术不可行。`truncated` 语义：合并排序后命中数 > 50 时置 true（被截断）。
- 只读本地，不触发同步；不持有全局 busy（与 list 一致，只读事务）；跨空间用 `fixed_profile` 打开非选中库（不受 `profile()` 守卫约束，见 core-bridge）。
- 空 `query`：返回空 hits（由前端显示"最近编辑"空态，见 §7.2）。
- 取消/增量：前端防抖 120～180ms，`requestCounter`/`AbortController` 丢弃晚到回执（比防抖更重要——避免"输入完整词却显示上一个词结果"的乱序）；core 侧 `search_notes` 为同步只读，多库顺序执行即可（SQLite 连接不跨线程共享更简单），1000 条规模无需并行。
- `generation` 随命中返回，打开时若已变化按现有并发规则重读（不盲目覆盖编辑器缓冲）。

> 注：现有 `list` 动作保留（列表页仍用它做当前空间的标题过滤，契约"稳定排序 + 单空间"不变）。`search_notes` 是新增的跨空间、带片段/排序/高亮的全局搜索入口，两者不互相替代、不互相污染返回结构。

## 6. 范围过滤（本机空间 / 账号空间 / 回收站）

范围是**两个正交维度**，不是三个互斥选项（回收站是 note 的状态，不是 space；做成三选一会产生"账号空间的回收站怎么搜"的语义空洞）：

- **维度 A（空间集合）**：`当前空间 / 全部本机空间 / 账号空间`，互斥单选（Segmented control）。
- **维度 B（删除状态）**：`包含回收站`（`deleted=true`）为独立 checkbox，叠加生效。

细则：

- **本机空间**：`kind=local` 的 profile 数据库（"全部本机空间"即所有 local profile；通常只有一个）。
- **账号空间**：`kind=account` 的 profile 数据库。**仅"当前激活账号"**；**当前激活空间不是账号时，`账号` 范围返回空结果**（无独立"当前账号"概念，只有 `registry.selected`，见 core）。跨账号聚合不在 v1（§13）。
- **恢复空间**：`kind=recovery` 的只读归档**不在 v1 全局搜索范围**，枚举目标空间时跳过（`scope.kind="current"` 且 selected 为 recovery 时返回空）。
- **回收站**：`include_deleted=true` 时纳入 `deleted=true` 的笔记（`notes` 表已有 `deleted` 标记，加 WHERE 即可，非新机制）。
- 数据全部来自本地 SQLite（账号空间是本地同步副本），无服务端搜索接口。**UI 必须显式说明"仅搜索已同步到本机的笔记"**（对应评审 R3，防止用户把本地副本搜索误判为服务端全量搜索、漏结果时当成数据丢失）。
- 回收站命中的打开行为：只允许"预览 / 恢复"，不直接进入编辑（与现有回收站语义一致）；恢复空间只读，命中打开也只读。

范围选择器默认值：**当前激活空间（本机或账号），不含回收站**。

## 7. UI 设计（参考成熟搜索框，不必自研复杂交互）

### 7.1 笔记内查找（Ctrl+F）

采用 CodeMirror `@codemirror/search`：`search({ createPanel, top: true })` 自绘轻量面板外壳（输入框 + 命中计数 + 上/下一个 + 大小写开关 + 关闭，不含替换框），查找/高亮/跳转复用 CM 的 `setSearchQuery`/`findNext`/`matchHighlighter`。**面板外壳少量自研**（默认 `searchPanel` 自带替换框且无隐藏开关，故自绘以关掉替换并自守 IME Enter），查找引擎零自研。详见 shortcuts-design §4。

### 7.2 全局搜索（Ctrl+Shift+F）

新全局搜索面板（Svelte 组件），借鉴成熟产品（VSCode/VS Code 工作区搜索、Obsidian 快速切换、Zed project search、Telescope/fzf 风格）：

- **入口**：Ctrl+Shift+F 打开，焦点落搜索框；Esc 关闭（先关面板，不冒泡到关菜单）。
- **结构**：顶部搜索框 + 范围选择器（空间集合单选：当前/全部本机/账号 + "包含回收站" checkbox + 账号空间"仅本地已同步"说明）；下方扁平结果列表（比树形更贴合"笔记扁平、无目录"的模型）。
- **每行结果**：标题（`title_segments` 高亮）+ 命中高亮片段（`segments` → `<mark>`）+ 命中数 + 更新时间 + 空间徽标（本机/账号/回收站）。
- **键盘导航**：↑/↓ 移动选择、Enter 打开选中、Esc 关闭。搜索框与结果列表的焦点分离，与 VSCode 一致。
- **打开与跳转**：Enter 打开笔记（`get` 全文）→ 前端把 query 注入 CodeMirror 搜索状态（`setSearchQuery` + `findNext`）→ CM 定位并滚动到首个命中、高亮全部命中。再次 Ctrl+F 在笔记内继续查找（分层：全局定位到笔记，笔记内查找定位到具体位置）。
- **增量与防抖**：输入即搜（120～180ms 防抖）+ `requestCounter`/`AbortController` 丢弃晚到回执；结果一次性返回 top 50（不做 offset 滚动加载）。
- **空态/超限**：无结果显示"无匹配"；`truncated=true` 时提示"结果已截断，请细化关键词"（配合相关性排序兜底）；空 query 显示"最近编辑"。

## 8. 性能设计与测试方案

### 8.1 基线目标

- 1,000 条笔记、平均 2KiB：常用搜索 P95 ≤200ms（product-v1 §9 / client-0.2.0 §2）。
- 短词（1～2 中文字符）不得成为明显慢路径（对应 C6）。
- 报告必须记录样本数、设备、系统、冷/热缓存，不用单次截图代替测量（product-v1 §9）。

### 8.2 测试集：`C:\Project\blog`

- 语料：180 篇 Markdown 博客（标准 Markdown；目录含图片等资源，但仅取 `.md` 文本，纯文本合计约 2MB 内，具体以实测为准）。已确认可访问。
- 用法（只读基准，不写入应用数据库、不改动 blog 文件）：
  1. 构造隔离 SQLite（镜像现有 schema：`notes` + `notes_fts` trigram），把 180 篇 `.md` 作为笔记正文导入。
  2. 跑与现有 `db::list` 等价的查询集（候选检索 + 打分 + 定位），统计端到端延迟。
- 查询集（覆盖需求）：
  - 1 字中文（如"的"、"会"——超高频与低频各一）
  - 2 字中文（如"会议"、"博客"）
  - 3 字中文
  - 英文单词（大小写变体各测，验证 ASCII 不敏感）
  - 中英混合、数字、特殊字符（`%`、`_`、`\`、引号）
  - 无命中 query（最坏：全表扫后无结果）
  - 命中数极大 query（1 字超高频，验证排序+limit 兜底）
- 指标：P50 / P95 / P99，每个 query 多次（≥50 次）取稳定值；记录冷启动（首次开库）与热查询。
- 若短词路径超标，决策点（按序尝试，不预判）：(a) 短查询只投影 id+摘要；(b) 进程内正文缓存；(c) bigram/字符辅助索引。方案选型以实测为准。

### 8.3 验证入口

- core 侧：`bash scripts/validate-local.sh core` + 新增 `search_notes` 单元/集成测试（见 §9）。
- 基准脚本建议落在 `tools/` 下（与既有 `tools/native-probe` 同级），只读 blog 语料，可重复运行。

### 8.4 已测基线（2026-11-20，blog 语料，内存 SQLite）

用 `C:\Project\blog` 的 180 篇 Markdown（合计 **1.56 MiB** 纯文本）构造隔离 SQLite（镜像现有 `notes` + `notes_fts` trigram），跑与现有 `db::list` 等价的 `LIKE`（<3 字符全表 / ≥3 字符走 FTS trigram 候选）查询，每 query 50 次：

| query | 路径 | 命中数 | P50 | P95 | P99 |
|---|---|---|---|---|---|
| `的`（1 字） | LIKE 全表 | 180 | 0.20ms | 0.26ms | 0.35ms |
| `会议`（2 字） | LIKE 全表 | 1 | 1.40ms | 1.50ms | 1.61ms |
| `博客`（2 字） | LIKE 全表 | 19 | 1.33ms | 1.37ms | 1.38ms |
| `the`（3 字） | FTS trigram | 83 | 0.78ms | 0.84ms | 0.90ms |
| `python` | FTS trigram | 65 | 0.46ms | 0.51ms | 0.58ms |
| `zzzznotfound`（无命中） | FTS trigram | 0 | 0.03ms | 0.04ms | 0.08ms |

结论：

- **最坏情况（2 字中文全表 LIKE 扫描）P99 ≈ 1.6ms，比 200ms 基线低两个数量级**；C6 担心的"短词总是全表扫描"在本规模下不是性能瓶颈，v1 无需 bigram/unigram 辅助索引，维持"trigram 候选 + 短词 LIKE 兜底"即可。
- 结果数越大延迟越低（`的` 命中 180 条仍 0.35ms），因 LIKE 命中即止或索引快速收敛；无命中时 FTS trigram 快速拒绝（0.03ms）。
- **口径说明**：本测量是内存 SQLite 的纯查询延迟（不含 IPC、JS 渲染、打分、片段生成与磁盘冷缓存）；实际端到端需在实现后按 §8.1 复测，磁盘 WAL + 2MiB 热缓存预期接近本数值，冷缓存与端到端 P95 以真机记录为准。此为下限基准，不替代实现后的完整测量。

## 9. 测试用例（新增）

延续 `docs/test-cases.md` 与 conformance `EDIT-01`，新增：

1. **不限字数**：1 字中文、1 字 ASCII、2 字中文均命中且 P95 ≤200ms。
2. **字面语义**：`% _ \` 引号、`OR`、`*` 按字面匹配，不解释为语法、不注入、不报错（回归现有用例）。
3. **ASCII 大小写不敏感**：`ABC` / `abc` / `Abc` 等价；其他 Unicode 按原字符。
4. **相关性**：标题命中 > 正文命中；命中次数多、位置靠前者靠前；平局按 updated_at DESC 稳定。
5. **高亮与片段**：返回 segments 与 title_segments 可精确高亮（无偏移运算）；UTF-8 多字节字符边界不截断（中文/emoji）；命中片段居中、上下文完整。
6. **范围**：本机空间/账号空间/回收站过滤正确；回收站结果标 `deleted=true`；跨空间不串库；`include_deleted` 独立于空间选择。
7. **跳转**：打开命中笔记后注入 query → CM `findNext` 定位首个命中并高亮；并发 generation 变化不覆盖编辑缓冲。
8. **截断与取消**：top 50 + `truncated` 语义正确无遗漏；晚到回执按 requestCounter/AbortController 丢弃（乱序不覆盖新结果）。
9. **FTS 一致性**：仅元数据更新（ack/set_deleted/purge_state）不重建 FTS（C4 修复后回归）；崩溃后索引可重建。
10. **性能**：blog 语料基准达标，见 §8。

## 10. 开源方案交叉评估

> 由独立调研子代理产出（调研 VSCode、Zed、Logseq、Joplin、SiYuan、Obsidian、Trilium、SilverBullet、QOwnNotes、CherryTree、Standard Notes、Helix、Kate/KWrite、ripgrep、fzf、Telescope.nvim），本文提炼结论并映射到本项目。完整出处见 §14。

### 10.1 三个关键结论

1. **Logseq 是最近参照**：同样用 SQLite FTS5 + `trigram` 分词器，全文索引/查询放 DB worker 线程，用"精确标题 → FTS5 → 模糊"多路回退；明确记录 trigram 对 <3 字符短查询"天生噪声大"。本项目现状（trigram 候选 + 短词回退）方向正确，可借鉴其多路回退与短查询处理。
2. **引擎侧不上重方案**：VS Code（ripgrep 子进程）、Zed（自研并行扫描）、SilverBullet（tantivy）、Telescope（每次重跑 rg）都为"海量文件扫描"设计，与 1000 条×2KiB（~2MB 语料）不成比例。本项目在现有 FTS5 trigram 上补"精确子串校验 + 高亮/片段/计数 + 轻量排序"即可。
3. **笔记内查找直接用 `@codemirror/search`**：自带增量搜索、大小写/正则/整词开关、命中高亮、"1 of N"计数、next/prev、滚动定位，把默认语义设为 `literal:true, caseSensitive:false` 即覆盖需求。

### 10.2 各项目要点与可借鉴点

| 项目 | 引擎/索引 | 排序相关性 | 高亮/片段 | 范围过滤 | 增量/取消 | 对本项目借鉴 |
|---|---|---|---|---|---|---|
| VS Code | 工作区用 ripgrep（Rust regex，失败回退 PCRE2）；编辑器内用 JS RegExp | 无统一相关性（文件命中数/树形分组） | 结果预览内高亮 + contextLines | include/exclude + 排除规则 | 进程可取消、流式回填 | 笔记内/全局两套实现分层；结果面板交互最完整 |
| Zed | 自研扫描，优先内存 buffer；与 ripgrep 共用 Rust regex | 无 BM25 | 多缓冲区命中展示 | 遵守 .gitignore | 并行 + `select_biased!` 优先"首命中延迟" | smart case；"首命中延迟"作为一等指标 |
| Logseq | **FTS5 trigram** + DB worker | 精确标题→FTS→模糊多路回退 | — | 页面/块 | worker 线程 | **最近参照**；短查询噪声处理 |
| Joplin | FTS4/5 实时索引；CJK 切自定义非 FTS 模式 | 命中次数越多越靠前；BM25 仅 FTS 模式 | — | 大量 filter（title:/body:/tag: 等） | — | 命中次数排序；CJK 字面语义的兜底切换 |
| SiYuan | FTS5 + 自定义 **unigram 单字分词器** | — | — | 布尔 + 属性过滤 | — | 中文靠单字/trigram 分词，不上词典 |
| Obsidian | 无持久化索引，按需逐字匹配（社区 trigram 插件佐证） | 无 | 上下文片段 + 高亮 | path:/tag:/line: 算子 | 实时 | 零索引维护、语义等价字面子串 |
| Trilium | 未用索引，逐条匹配 | **精确永远排在模糊前** | — | 属性表达式 | 渐进式 | 分层策略可解释 |
| SilverBullet | tantivy（服务端每空间索引） | BM25 | — | 空间 | 变更即更新 | 服务端索引方向（本项目 v1 不做服务端） |
| QOwnNotes | SQLite `LIKE '%word%'` + 内存缓存 | 无 | — | 多列 AND | — | LIKE 子串够用；对 SQLite 复杂匹配克制 |
| CherryTree / Standard Notes / Helix / Kate | 扫描式（Helix 用 grep_searcher） | — | 替换/高亮 | 节点/文件范围 | Helix 增量、Kate 增量 | 范围切换与替换迭代 |
| ripgrep / fzf / Telescope | regex / fuzzy / 重跑 rg | fzf ExactMatch 加分、Telescope sorter | — | — | — | 加分项设计（词边界/camelCase/连续块）参考 |

### 10.3 UI 侧多参考（成熟，直接采用）

- 快捷键分层全球一致：`Ctrl+F` 笔记内、`Ctrl+Shift+F` 全局、`Esc` 关、`Enter` 打开/下一处、`F3`/`Shift+F3` 上/下一处。
- 结果面板：输入即搜（增量）、实时"命中数 / 1 of N"、结果项 = 标题 + 含高亮片段 + 命中数、上下键选择、Enter 打开并定位、Esc 关闭。
- 高亮与片段：`<mark>` 包裹命中；片段取首命中前后 20–40 字符。
- 开关最小集：v1 仅"大小写"（默认不敏感）；整词/正则/smart case 放后续。

### 10.4 引擎侧按需裁剪（明确不做）

- ❌ 不引入 ripgrep / tantivy / 自研倒排（海量文件场景才需要）。
- ❌ 不为中文上词典分词（SiYuan 单字、Logseq trigram 已够）。
- ✅ 保持"FTS5 trigram 候选 + Rust 精确子串校验"，补齐排序/高亮/片段。

## 11. Android 侧范围（0.1.11 本版补充 UI，无键盘快捷键）

维护者决定：Android 端没有 Ctrl+W / Ctrl+F 这类键盘快捷键（触摸交互），因此**只补 UI**，且本版就要交付：

- **全局搜索 UI**：复用 core `search_notes`（经 UniFFI，一次实现双端共享），把现有列表页搜索升级为"范围过滤 + 相关性 + 命中高亮 + 片段 + 跳转"。入口用 Material 3 `SearchBar` / `DockedSearchBar`（版本 API 以项目锁定的 material3 为准，不照抄网上示例）；结果项用 `AnnotatedString` + `SpanStyle` 消费 core 返回的 segments（无 UTF-8↔UTF-16 换算）；**范围选择与桌面一致**——空间集合单选（当前/全部本机/账号）+ "包含回收站"独立 checkbox（账号同样"仅当前账号、仅本地已同步"，UI 附说明）。core 契约两端共享，故不缩水为"先只做本机"。
- **全局搜索 → 笔记内定位（Android 衔接）**：打开命中笔记后，自动进入该笔记并在编辑器内定位/高亮首个命中（与桌面"注入 query → 定位"等价）；由查找条承载"继续上/下一个"。否则只打开不定位，违背"两端只差 UI 载体"的承诺。
- **防抖**：现有 `MemoModel.search` 每键直接 `loadNotes`（无 debounce），升级到 `search_notes` 后**补 120～180ms 防抖**（沿用 `listRequest` 序号丢弃晚到回执），避免每键一次打分+片段往返。
- **笔记内查找 UI**：原生编辑器是 `AndroidView` 包裹的自定义 `EditText`（`SourceEditor.kt`），非 Compose `BasicTextField`，故查找条用**原生 Spannable** 实现：`BackgroundColorSpan` 高亮 + `Selection.setSelection` 定位 + 外层 `ScrollView` 滚动，复用既有的 composing span 跟踪守 IME。语义与全局搜索一致（ASCII 大小写不敏感、字面），**纯前端文档内查找，不走 core/FTS5**；入口挂在编辑页工具栏/更多菜单（无打开笔记时该页不存在 ⇒ 无入口，天然对应桌面"无笔记无动作"）。
- **Ctrl+W / 键盘快捷键**：不适用（Android 对应系统返回手势/返回键，无托盘概念）。

三份"字面 + ASCII 大小写不敏感"实现（Rust core / CodeMirror JS / Kotlin）需以 [product-v1 §7](../spec/product-v1.md) 的"仅 ASCII 折叠、其他 Unicode 按原字符"为唯一口径对齐，并在一致性测试中覆盖（§9），避免三端在非 ASCII 大小写上的隐性分叉。

与桌面侧共用同一份 [0.1.11 规范](../spec/client-0.1.11.md) 的搜索契约；Android 全局搜索与桌面全局搜索的排序/片段/范围语义必须一致（core 保证），两端只差 UI 载体。三端均属 v1，Android 需在 AVD/真机验收（见 [validation](../docs/validation.md)），不以桌面单端通过代替。

## 12. 实施计划（分阶段）

1. **core 检索与打分**：`db::search_notes`（候选 + 打分 + segments 定位 + 片段）+ `search_notes` 动作 + 单元测试；修复 C4（追加迁移 0004）。
2. **性能基准**：blog 语料基准脚本（落 `tools/`），确认短词路径与 P95。
3. **桌面笔记内查找**：引入 `@codemirror/search`（补 ADR），Ctrl+F 接入 + IME 守卫 + Escape 优先级链。
4. **桌面全局搜索**：`search_notes` 动作前端封装 + 全局搜索面板 + 范围选择器 + segments 高亮/片段/跳转 + 键盘导航。
5. **Ctrl+W**：最终采用 `native('request_hide')` + IME 保护，复用既有关闭链路；早期 `getCurrentWindow().close()` 方案已按 §13 弃用。
6. **Android 全局搜索 UI**：`search_notes` 经 UniFFI 接入 + M3 SearchBar + segments 高亮 + 范围过滤。
7. **Android 笔记内查找 UI**：原生 Spannable 查找条（字面匹配 + 高亮 + 上下一个 + 定位 + IME 守卫）+ 全局搜索→笔记内定位衔接 + 防抖。
8. **交叉评审**：同步/迁移/鉴权边界变更（C4 迁移）须独立交叉评审；快捷键与搜索 UI 做 UX 交叉评审。
9. **0.1.11 发布**：三端验收，延续 GitHub Actions 签名/发布（ADR-0017）。

## 13. 已确认决策与风险（2026-11-20，维护者定稿）

| 项 | 决定 | 状态 |
|---|---|---|
| 空间表述 | 用户原话"本地控件"为笔误，统一为"**本机空间**" | 已定 |
| 账号空间范围 | **仅当前激活账号**（跨账号聚合不在 v1） | 已定 |
| 回收站维度 | 独立 checkbox（`include_deleted`），非第三互斥空间 | 已定（评审 R9） |
| 账号空间本地性 | 纯本地搜索已同步副本，UI 显式说明"仅搜索已同步到本机的笔记" | 已定（评审 R3） |
| 高亮表示法 | core 返回 segments（无偏移），消除 Rust/JS/Kotlin 偏移错位 | 已定（评审 R2） |
| 跨库分页 | v1 不做 offset，top 50 + truncated 提示 | 已定（评审 R12） |
| Ctrl+F 无打开笔记 | **无动作**（用 Ctrl+Shift+F 显式全局搜索） | 已定 |
| Ctrl+W 模态框内 | **不触发** | 已定 |
| Ctrl+W 产品语义 | **窗口级关闭到托盘**（本项目不做多笔记 tab） | 已定（评审 R6） |
| Linux 关闭行为 | **维持 `hide()`**；`minimize()` 加固方案与理由保留在 [shortcuts-design §3.4](shortcuts-design.md) 供未来参考 | 已定 |
| Escape 优先级链 | **接受**"面板 > 编辑器失焦 > 关菜单/取消多选" | 已定（评审 R7） |
| 替换功能 | 0.1.11 不启用（`createPanel` 自绘面板不含替换框） | 已定 |
| Ctrl+F 实现路线 | `@codemirror/search` + `createPanel` 自绘外壳（关替换 + 守 IME Enter）；**窗口级绑定 Ctrl+F**（守卫 1 让编辑器聚焦时不重复处理） | 已定（评审 A6/A7） |
| Ctrl+W 实现 | 新增 Tauri 命令 `request_hide`（不放开 `core:window:*` 权限；`close()` 需权限故弃用） | 已定（评审 A2） |
| Android | **本版补充 UI**（笔记内查找 UI + 全局搜索 UI），无键盘快捷键 | 已定 |
| Android 技术栈 | 编辑器为原生 `EditText`，查找条用 Spannable + `Selection` + `ScrollView`（非 Compose `BasicTextField`）；全局搜索→笔记内定位衔接 + 补防抖 | 已定（评审 C2/C4/C6） |
| 账号空间无激活时 | `账号` 范围返回空（仅 `registry.selected` 是账号才搜） | 已定（评审 B2） |
| 恢复空间 | v1 全局搜索范围跳过 `recovery`（枚举时过滤） | 已定（评审 B3） |
| 候选截断 | **全量召回打分，无 SQL `LIMIT` 预截断**（避免高分笔记被时间序裁掉） | 已定（评审 B4） |
| snippet/truncated | 单片段（围绕首个命中）；`truncated` = 合并排序后命中数 >50 | 已定（评审 B5） |
| core-bridge | 已登记 `search_notes` + 跨空间只读、不受 `profile()` 守卫约束 | 已定（评审 B6） |
| C4 触发器迁移 | 追加 0004（`WHEN old.content IS NOT new.content`），客户端 schema 3 → 4，不改既有迁移 | 待实施+评审 |
| 1 字高频词结果爆炸 | top 50 + 相关性兜底 + "请细化关键词"提示 | 已设计 |
| 大笔记（1MiB 上限）定位 | 全量召回打分后取 top 50；segments 切分 UTF-8 边界安全 | 已设计 |

> 0.1.11 开发前**无遗留待决策项**；剩余均为"待实施+评审/已设计"的实现期事项（C4 迁移、性能复测、三端真机验收）。

## 14. 参考

- [v1 产品行为 §7 搜索](../spec/product-v1.md)
- [数据模型 §3 notes_fts](../spec/data-model.md)
- [UI 与 core 接口边界](../spec/core-api.md)
- [一致性场景 EDIT-01](../spec/conformance/scenarios.md)
- C4/C6 源自 2026-11-18 一次性静态评审（已归档）。
- [SQLite FTS5 trigram 官方说明](https://www.sqlite.org/fts5.html#the_trigram_tokenizer)
- CodeMirror 6 search：[@codemirror/search](https://codemirror.net/docs/ref/#search) · [highlightSelectionMatches 文档](https://npmx.dev/package-docs/@codemirror/search/v/6.7.2) · [自绘查找面板 discuss](https://discuss.codemirror.net/t/replace-default-search-panel-with-my-own-component/5885)
- VS Code：搜索服务与引擎（ripgrep）[Search Issues](https://github.com/microsoft/vscode/wiki/Search-Issues)、[DeepWiki 摘要](https://deepwiki.com/microsoft/vscode/11-workbench-ui-architecture)
- Zed：[Project Search 官方博客（实现与 `select_biased!` 优化）](https://zed.dev/blog/nerd-sniped-project-search)、[buffer_search.rs](https://github.com/zed-industries/zed/blob/e4937eb0/crates/search/src/buffer_search.rs)
- Logseq：[Search and Command Palette（FTS5 trigram、worker）](https://deepwiki.com/logseq/logseq/9.1-search-and-command-palette)、[搜索实现（Engine/DB worker）](https://opendeep.wiki/logseq/logseq/editor-core.search)
- Joplin：[Searching 官方文档（FTS/CJK 自定义模式/相关性）](https://joplinapp.org/help/apps/search/)、[1.1 搜索引擎更新（BM25/filter）](https://github.com/laurent22/joplin/blob/144ed593ccfd3ed4bb92e4e22046bb486cffc5b9/readme/news/20200913-163730.md)
- SiYuan：[Full-Text Search（FTS5 + 自定义 tokenizer）](https://deepwiki.com/siyuan-note/siyuan/9.1-full-text-search)、[中文单字分词器](https://github.com/siyuan-note/sqlite-fts5-siyuan-tokenizer)
- Obsidian：[indexed-search 插件（佐证核心无索引）](https://github.com/mnaoumov/obsidian-indexed-search)
- Trilium：[Under the hood（渐进式搜索）](https://docs.triliumnotes.org/user-guide/concepts/navigation/search/under-the-hood)
- SilverBullet：[ADR-013 Server-Side Search（tantivy）](https://github.com/silverbulletmd/silverbullet/blob/b9a7e6126fa7466f4fa5cb6c356d7430a7efe025/docs/ADR/013%20Server-Side%20Search.md)
- QOwnNotes：[SQLite LIKE 搜索解析](https://blog.gitcode.com/6263d096445b7740c4fc3dfb829a6230.html)
- Helix：[pickers（全局搜索正则）](https://raw.githubusercontent.com/sarvex/helix/refs/heads/main/book/src/pickers.md)
- fzf：[algo.go（ExactMatchNaive 加分）](https://github.com/junegunn/fzf/blob/master/src/algo/algo.go)
- Telescope.nvim：[README（finders/sorters）](https://github.com/nvim-telescope/telescope.nvim)

## 15. 0.1.13 定稿增量（未实施）

维护者已确认 [0.1.13 目标规范](../spec/client-0.1.13.md)及[开发文档 §4–5](client-0.1.13-development.md#4-w2固定搜索范围与接口)。本节替代历史设计中的可选过滤、默认当前空间且不含回收站的目标口径，不追溯改变 0.1.11/0.1.12 行为。

- 两端去掉范围与回收站开关，固定搜索全部本机空间＋selected 恰为账号时的该一个账号，均含普通回收站。selected 为本机/恢复空间时只搜本机，不带入上次或其他账号。此前评估中的“所有账号”不实施。
- 新增 core 范围模式 `local_and_current_account`，新模式根 `profile_id` 作为必填锚点，与枚举用同一 registry 快照校验；不与 `space_ids` 混用。字段/错误与兼容要求以 [core-api](../spec/core-api.md#0113-搜索目标扩展) 为准。
- 纯本地、恢复归档排除、彻底删除规则、字面匹配与合并后 top 50 保持。空 query 显示输入提示，不显示全量/最近编辑。两端固定发送 `include_deleted=true`，旧接口默认值不变。
- 账号/服务器来源与回收站状态分别展示；安全来源快照和 `(profile_id,note_id)` key 保证身份一致。失败整次可重试，不提供部分成功或分页；迟到回执按请求/范围身份作废。
- 桌面修复结果行 Flex 收缩，列表滚动、选中项可见；Android 验证长标签/大字/键盘布局。先限制参与空间再召回，不能全账号搜索后由 UI 过滤。

完整状态、跳转、故障与验收用例只在本版规范/开发文档维护。当前代码、设备和发布矩阵未因本节建立而通过。
