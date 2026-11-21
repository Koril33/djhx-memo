# 0.1.11 客户端目标规范（快捷键与搜索）

> 版本说明：本文保留 0.1.11 的行为与当时记录。0.1.13 已确认的空白页、两段式 Ctrl+W 和固定搜索范围见 [新版本目标契约](client-0.1.13.md)，尚未实施；不能把本文旧过滤器与窗口级 Ctrl+W 直接作为新版本目标。

- 日期：2026-11-20。
- 状态：**已实现并本地测试通过（core/桌面/Android），待三端真机验收与 CI 发布。** 本版客户端/core 0.1.11、客户端 schema 3 → 4（追加迁移 0004 修复 C4 FTS 触发器）、Android code 12；协议 2、server 0.1.4/schema 2 不变，不改既有迁移 0001-0003、不改同步协议。
- 基线：[product-v1](product-v1.md)、[0.1.10](client-0.1.10.md)、[数据模型](data-model.md)、[core-api](core-api.md)。
- 设计来源：[快捷键设计](../docs/shortcuts-design.md)、[搜索功能详细设计](../docs/search-design.md)。两者是唯一设计来源，本规范只陈述行为契约与验收，不再重复设计取舍。

## 1. 范围及已确认取舍

| 项目 | 0.1.11 目标 | 已确认边界 |
|---|---|---|
| Ctrl+W 关闭到托盘 | Windows/Linux 关闭窗口到托盘，等同点击关闭按钮 | 复用既有保存后隐藏链路；无托盘时退出；保存失败中止关闭；Linux 维持 `hide()`（见 §2） |
| Ctrl+F 笔记内查找 | 当前笔记内查找、命中高亮、next/prev 跳转 | 基于 CodeMirror `@codemirror/search`；第一版不做替换 |
| Ctrl+Shift+F 全局搜索 | 跨笔记搜索，范围可选，相关性排序、命中高亮、上下文片段、跳转命中 | 纯本地，服务端不提供搜索接口；账号空间仅搜本地已同步副本 |
| 搜索范围 | 空间集合（当前/全部本机/账号）+ 回收站独立勾选 | 默认当前激活空间（不含回收站）；账号**仅当前账号** |
| 不限字数 | 1 字符即可搜且性能达标 | 无最小长度；空 query 显示最近编辑 |
| Android | 补充**笔记内查找 UI + 全局搜索 UI**（复用 core，无键盘快捷键） | 无 Ctrl+W/Ctrl+F 快捷键；触摸交互用 UI 入口 |
| 性能 | 1,000 条 × 2KiB 常用搜索 P95 ≤200ms | 用维护者 blog 语料实测；记录样本/设备/系统 |

不包含：拼音/模糊/词义/语义搜索、服务端搜索 API、OS 级全局热键、替换、附件搜索、Android 键盘快捷键（用 UI 入口代替）。

## 2. Ctrl+W 关闭到托盘

- `Ctrl+W`（仅 Windows/Linux 的 Ctrl，非 metaKey）在应用聚焦时触发，行为与点击窗口关闭按钮完全一致：先 flush，保存成功且有托盘则隐藏到托盘，无托盘则退出，保存失败则不关闭并保留窗口（见 [product-v1 §8](product-v1.md)、[shortcuts-design §3](../docs/shortcuts-design.md)）。
- 复用 `request_lifecycle` 链路：新增 Tauri 命令 `request_hide`（`request_lifecycle(app, Lifecycle::Hide)`），前端 `native('request_hide')`。**不放开 `core:window:*` 前端权限**（`getCurrentWindow().close()` 需 `core:window:allow-close`，扩大权限面，故弃用；见 shortcuts-design §3.2 权衡）。
- 已知边界（与 X 按钮同源，非 Ctrl+W 独有）：安装屏障期 `request_lifecycle` 直接返回、`frontend_ready=false` 时仅 `show_window`、pending 去重会重发旧 kind、无托盘时 `Lifecycle::Quit` 直接退出进程——这些分支不丢数据（flush 成功才退），但键盘路径无"窗口未关/已退出"的视觉反馈，见 [shortcuts-design §3.4](../docs/shortcuts-design.md)。
- 中文输入法组合期间（`event.isComposing`）不触发；模态框内**不触发**（Escape 关模态框）。
- **Linux 关闭行为（已定）**：**维持 `hide()`**（Windows/Linux 一致）。已知残留风险与未来 `minimize()` 加固方案、理由记录在 [shortcuts-design §3.4](../docs/shortcuts-design.md) 供后续参考，0.1.11 不改。
- 产品语义（已定）：**窗口级关闭到托盘**（本项目不做多笔记 tab）。

## 3. Ctrl+F 笔记内查找

- `Ctrl+F` 在编辑器聚焦时打开笔记内查找面板；命中高亮（含全部命中）、计数、next/prev 跳转；`F3`/`Shift-F3`/`Mod-G`/`Mod-Shift-G` 下一个/上一个。
- 实现用 `@codemirror/search` 的 `search({ createPanel })` **自定义面板外壳**（输入框 + 计数 + 上/下一个 + 关闭 + 大小写开关），查找/高亮/跳转复用 CM 的 `setSearchQuery`/`findNext`/`matchHighlighter`；**不含替换框**（默认 `searchPanel` 自带替换且无隐藏开关，故用 `createPanel`）。守 IME 组合（中文 Enter 二义）。
- 大小写/正则/整词：默认大小写不敏感（与全局搜索一致）、非正则、非整词（`literal:true`）。
- 不提供替换；查找不改动正文、选区、撤销栈或 IME 组合。
- **窗口级绑定 Ctrl+F**（守卫 1 `defaultPrevented` 保证编辑器聚焦时由 CM 消费、窗口级不重复处理；编辑器未聚焦时由窗口级接管）。全局 keydown 统一遵守 `defaultPrevented` → `isComposing` → 上下文的守卫顺序（shortcuts-design §2.1）；Escape 优先级链见 shortcuts-design §4.4。
- 触发语义（已定）：编辑器聚焦 → 开面板；编辑器未聚焦但有打开笔记 → 聚焦编辑器并开面板；**无打开笔记 → 无动作**；预览态 → 切编辑态再开面板。
- 修饰键只认 `ctrlKey`（不认 `metaKey`）；现有 Ctrl/Cmd+N/S 一并改为只认 `ctrlKey`（Linux Super 组合让位于桌面环境）。

## 4. Ctrl+Shift+F 全局搜索

- `Ctrl+Shift+F` 打开全局搜索浮层；范围 = 空间集合单选（当前/全部本机/账号）+ "包含回收站"独立 checkbox；默认当前激活空间、不含回收站。
- 范围语义（core 枚举时落地）：**v1 全局搜索范围不含恢复空间**（恢复空间为只读归档，枚举时跳过 `kind=="recovery"`）；`账号` = 仅当前激活账号，**当前激活空间不是账号时 `账号` 范围返回空结果**；`全部本机` = 所有 `kind=="local"` 空间（v1 通常只有一个）。
- 账号空间搜索为**纯本地已同步副本**，UI 显式说明"仅搜索已同步到本机的笔记"。
- 结果按相关性 + 更新时间稳定排序；显示命中高亮标题/片段（core 返回 segments，前端 `<mark>`/`AnnotatedString` 直接包裹，无偏移运算）；键盘导航（↑/↓/Enter/Esc）；Enter 打开笔记并注入 query → CM `findNext` 定位首个命中。
- 搜索为本地字面子串：1 字符即可，ASCII 大小写不敏感，其他 Unicode 按原字符；`% _ \` 引号等按字面匹配，不解释为 FTS/正则/布尔，不注入（LIKE 参数化，不构造 FTS MATCH 语法）。
- 结果一次性返回 top 50；`truncated=true` 表示合并排序后命中数超过 50（被截断，提示细化关键词）。不做 offset 分页（v1 范围下每次最多命中 1～2 个空间，单库 offset 本可下推，但 1000 条规模 top 50 + 截断提示已够用，属产品取舍而非技术不可行）。晚到回执按请求序号/AbortController 丢弃；搜索不触发同步、不持全局 busy。

## 5. core 接口

新增（扩展）`search_notes` 动作（扩展 [core-api](core-api.md) 已列的 `search_notes`，见其 §0.1.11 搜索扩展），输入 `query / scope{kind,space_ids,include_deleted}`，输出 `hits[] / truncated`；`SearchHit` 含 `note_id / profile_id / space_kind(local|account) / deleted / title / title_segments / snippet / segments / hit_count / score / updated_at / generation`（segments 为已切分文本，无裸偏移；`snippet` 为围绕首个命中的单片段）。只读本地，不返回全文。实现口径：**全量召回匹配笔记（无 SQL `LIMIT` 预截断，避免高分笔记因时间序被裁掉、破坏相关性排序）→ Rust 打分 → 排序 → 取 top 50**；`truncated` 表示排序后命中数 >50 被截断。现有 `list` 动作保留不变（稳定排序 + 单空间语义）。

## 6. Android 范围（本版补充 UI）

- Android 无 Ctrl+W / Ctrl+F 键盘快捷键（触摸交互），0.1.11 **补充两块 UI**：
  1. **全局搜索 UI**：复用 core `search_notes`（UniFFI），现有列表搜索升级为"范围过滤 + 相关性 + 命中高亮 + 片段 + 跳转"；入口用 Material 3 `SearchBar`/`DockedSearchBar`；结果高亮用 `AnnotatedString` + `SpanStyle` 消费 segments。
  2. **笔记内查找 UI**：Compose 原生编辑器自研轻量"查找条"（工具栏/更多菜单入口），字面子串匹配 + 命中计数 + 上/下一个 + 滚动定位 + 高亮；语义与全局搜索一致（ASCII 大小写不敏感、字面），纯文档内查找不走 core/FTS5，守 IME 组合。
- 两端搜索契约（排序/片段/范围）由 core 保证一致，只差 UI 载体；三端均属 v1，Android 需在 AVD/真机验收。

## 7. 性能验收

- 基线：1,000 条笔记、平均 2KiB，常用搜索（含 1～2 中文字符）P95 ≤200ms。
- 测试集：维护者 blog 语料（`C:\Project\blog`，180 篇 Markdown），只读隔离导入，不改动应用数据库与 blog 文件。已测下限基准见 [search-design §8.4](../docs/search-design.md)：最坏情况（2 字中文全表 LIKE）P99 ≈ 1.6ms。
- 记录样本数、设备、系统、冷/热缓存；不用单次截图代替测量。

## 8. 开发与验收关口

- 本规范与两篇设计文档先行、决策已收敛；尚未授权本轮编码；文档完成不等于进入实现或通过独立评审。
- 涉及迁移（C4 触发器修复）须独立交叉评审；快捷键与搜索 UI 须 UX 交叉评审；同步/迁移/鉴权边界改动须按 [全局规范](../AGENTS.md) 交叉评审。
- 目标版本 0.1.11；不要求修改同步协议/server schema；发现必要变化后追加评审。
- 迁移 0004 使客户端 schema 3 → 4（升级路径按 data-model §4 版本化迁移验证）；升到 0.1.11 后若回滚到 0.1.10，本机库会因 `LOCAL_SCHEMA_TOO_NEW` 变为只读（既有机制，非 0.1.11 新增回滚路径）。
- 三端均属于 v1，不以 Windows 单端通过代替 Android/Linux 验收；Android 本版交付 UI 需记录 AVD/真机验收项。

## 9. 已确认决策（2026-11-20，维护者定稿）

| 项 | 决定 |
|---|---|
| 空间表述 | 统一为"**本机空间**"（用户已确认"本地控件"为笔误） |
| 账号空间范围 | **仅当前激活账号**（跨账号聚合不在 v1） |
| 回收站维度 | 独立 checkbox（`include_deleted`），非第三互斥空间 |
| 账号空间本地性 | 纯本地搜索已同步副本，UI 显式说明 |
| 高亮表示法 | core 返回 segments（无裸偏移） |
| 跨库分页 | 不做 offset，top 50 + truncated |
| Ctrl+F 无打开笔记 | **无动作** |
| Ctrl+W 模态框内 | **不触发** |
| Ctrl+W 产品语义 | **窗口级关闭到托盘** |
| Linux 关闭行为 | **维持 `hide()`**（分析记录于 shortcuts-design §3.4） |
| Escape 优先级链 | **接受**"面板 > 编辑器失焦 > 关菜单/取消多选" |
| 替换功能 | 0.1.11 不启用 |
| Android | **本版补充 UI**（笔记内查找 + 全局搜索），无键盘快捷键 |

> 0.1.11 开发前**无遗留待决策项**；剩余为实现期事项（C4 迁移、性能复测、三端真机验收、交叉评审）。
