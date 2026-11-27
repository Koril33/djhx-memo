# 0.2.7 界面与编辑契约

- 日期：2026-11-26；状态：**开发前定稿，未实施**。评估见[开发文档](../docs/client-0.2.7-development.md)；实际验收以[本轮验证记录](../docs/releases/0.2.7-validation.md)为准。
- 基线：`v0.2.6`（客户端 0.2.6、Android code21、core 0.2.2、server 0.1.4、协议 2、local schema4 / server schema2）。维护者已确认本轮范围并就全部决策点定稿（见开发文档 §2）。
- 范围：回收站多选与批量彻底删除、桌面编辑区行号、Markdown 标题目录、桌面登录设备加载状态、Android 移动端预览美化。
- 版本：客户端 0.2.7、Android code22、**core 0.2.3（新增 `batch_purge`）**；server 0.1.4、协议 2、local schema4 / server schema2、包名/证书/签名身份保持。继承 [0.1.6](client-0.1.6.md)、[0.1.13](client-0.1.13.md)、[0.2.0](client-0.2.0.md)、[0.2.2](client-0.2.2.md)、[0.2.3](client-0.2.3.md) 未被本条替代的行为。

## 1 范围

| 范围 | 0.2.7 目标 |
|---|---|
| Windows/Linux + Android 回收站 | 多选：批量恢复（可撤销）、批量彻底删除（不可撤销） |
| Windows/Linux 编辑器 | 源码行号（逻辑行、跟随主题、始终开启） |
| Windows/Linux + Android 编辑器 | Markdown 标题目录（派生展示、点击跳转、开关可持久化） |
| Windows/Linux 设置 | 登录设备页独立加载状态，修复切账号残留与行内错误重试 |
| Android 预览 | 集中式 MarkdownStyle 排版美化，删除线语法对齐桌面 |

本版不改变同步协议、schema、服务端；不改变正文保存/同步/标题派生规则。core 新增 `batch_purge`（见 §2.1）。

## 2 回收站多选与批量彻底删除

本条替代 [client-0.1.6.md](client-0.1.6.md) §1“多选只操作当前普通列表、切到回收站退出多选”与“此版不提供回收站批量彻底删除或批量清空”，并替代 [permanent-deletion.md](permanent-deletion.md) §1“仅对回收站单条笔记提供彻底删除、不提供批量清空”。普通删除/恢复/单条彻底删除的既有边界不变。

- 回收站（`deleted=true` 列表）提供与普通列表同构的多选入口（桌面顶部 ListChecks 图标、Android 长按），入口唯一，不在“更多”菜单重复。
- 全选包含所有可见条目（含 `purge_state=pending`/`rejected`；`purged` 不在列表）。`pending` 条目置灰标记但可勾选。
- 工具栏动作：恢复（`rotate-ccw`）+ 彻底删除（`trash-2`，危险色，二次确认）。恢复走 `batch_set_deleted(deleted:false)`；彻底删除走新增 `batch_purge`。
- 批量彻底删除的二次确认在**打开确认框时**冻结 `(profile_id, id, generation)` 快照，确认时按快照提交；确认期间列表刷新不得改变提交范围。提交前走既有 flush/IME 屏障。
- 结果口径：恢复按“已恢复 N · 撤销”提示，撤销 = 把刚恢复条目重新移回回收站（`batch_set_deleted(deleted:true)`），仅撤销成功项、使用恢复成功后的代次；彻底删除按“已彻底删除 X · 待同步 Y”（账号空间 `pending`，含本就 `pending` 且本次幂等成功的条目，不重复入队）或“已彻底删除 N”（本机空间），不可撤销、不并入撤销模型；失败项保留选中并提示失败数（“已彻底删除 X · 待同步 Y · 失败 Z”）。
- 含当前编辑笔记的批次：仅当成功进入 `pending`/`purged`（彻底删除）或恢复成功（恢复）才关闭编辑器；失败或代次冲突的当前笔记保留编辑器。
- `pending` 条目恢复时返回 `PURGE_PENDING` 计入失败提示，彻底删除时幂等成功。

### 2.1 `batch_purge` 接口

- 输入：`profile_id`（两端 UI 均显式携带，经 `self.profile()` 校验，与选中空间不符返回 `PROFILE_CHANGED`）、`items[{id, expected_generation}]`，上限 10_000，重复 id 返回 `INVALID_REQUEST`。
- 输出：`results[{id, ok, error?, purge_state?}]`（**精简视图**，不含正文/标题/冲突，避免大批次回执体积）。
- 逐项复用 `purge::request` 单条语义：已 `purged` 幂等 Ok（代次检查前）；代次不符 `LOCAL_GENERATION_CONFLICT`；非回收站 `PURGE_CONFLICT`；已 `pending` 幂等 Ok（**不重复写入 purge_requests、不计新增请求；回执仍为 pending，计入“待同步”**）；否则本机空间直接 `purged`、账号空间入队 `pending`。
- 逐项事务与可写校验、整批错误与逐项错误、中断后已提交结果，语义同 `batch_set_deleted`。

## 3 桌面编辑区行号

- 源码编辑器始终显示行号（不做开关）；CodeMirror `lineNumbers()`，软换行按逻辑行编号（不按视觉换行）。
- 行号 gutter 跟随既有主题 CSS 变量（暗色用 `var(--muted)`/`var(--border)`，不得出现深色界面中的固定浅色 gutter）；不新增硬编码色。
- 行号 gutter 会缩窄正文、改变软换行折行点与块高度，同步滚动在布局变化后重新测量。

## 4 Markdown 标题目录

目录是**派生展示**（与列表标题同口径，见 [product-v1.md](product-v1.md) §4），不修改正文、不参与保存/同步、两端各自本地解析。

- 标题识别采用**两端共享的识别范围**：仅 ATX（`#{1,6} `）与 setext（`===`/`---` 下划线）标题；排除代码围栏、引用/列表内标题；目录标签取纯文本（剥离行内加粗/斜体/链接/行内代码标记，解码 HTML 实体）。
- 桌面 `source`/`preview`/`split` 三模式、Android `source`/`preview` 两模式均显示目录开关。
- 目录开关：桌面默认关闭、图标展开/折叠，开关状态作为展示偏好持久化；Android 底部弹层不持久化（旋转恢复、每次进编辑页默认关闭）。
- 点击跳转**只滚动、不移动光标**：桌面 source 用 `EditorView.scrollIntoView`（不改选区）、preview 用 `data-source-from` 定位、split 滚源码；Android source 按逻辑行换算偏移后 `scrollTo`、preview 先切回 source 再跳。**Android 只读笔记（已删除/恢复空间）隐藏目录入口**（只读强制预览，无源码可跳）。
- 目录重建防抖，异步结果按笔记身份与正文快照作废；不逐事件重解析大笔记。
- 桌面编辑模式作为设备本地展示偏好持久化（全局一个值，与主题/语言同级，不写笔记、不参与同步）；仅持久化用户主动选择的模式，`Ctrl+F`/全局搜索跳转/lint 触发的自动切 source 属临时态、不写回；窄窗口分屏临时降级为 `source`、不改偏好、加宽恢复；模式切换不重建编辑器、不丢光标/选区/IME/撤销。

## 5 桌面登录设备加载状态

本条把 [client-0.2.0.md](client-0.2.0.md) §3.1 的 Devices 状态表适用范围扩展到 Windows/Linux。

- 设备列表读取使用独立状态（`devicesLoading/devicesRefreshing/devicesError/devicesStale/devicesSession/revoking/revokeError`），不切换全局 `busy`；三态：无缓存→加载、有缓存→后台刷新不闪空、失败→保留旧列表 + “未刷新” + 行内重试。
- 请求显式携带发起时 `profile_id`（复用 core 既有 `self.profile()` 校验，空间不符返回 `PROFILE_CHANGED`）；回执按会话序号与回执时 profile 双重作废；覆盖注销、切空间、同账号重新登录与重复刷新（按钮触发的在途刷新合并、吊销后强制刷新；旧回执按会话序号作废）。
- 吊销使用「当前活动吊销 id」而非会话序号来复位 `revoking`（刷新会在同会话内推进序号），保证同一会话可连续吊销多台设备，失败项行内提示并在成功刷新后清除。
- 切空间/重新登录后清空设备缓存；自吊销当前设备后 core 清凭据，呈现“吊销成功 + 需重新登录”并保留离线编辑；吊销成功与随后刷新失败是两个独立结果。
- 设备页说明“ⓘ”结构与文本选择（0.2.6 已修）不得回归。

## 6 Android 移动端预览美化

本条仅改 Android 预览的排版与配色，不改 core、不改正文、不改标题派生规则；桌面预览不变（两端不对称写入已知项）。安全边界延续 [product-v1.md](product-v1.md) §4：原始 HTML 仅文本、图片仅占位、链接走 `safeLink` 白名单。

- 新增集中样式类 `MarkdownStyle`（`@Immutable` data class）+ `LocalMarkdownStyle`，从应用既有 `MaterialTheme`（固定品牌色）派生，不写死色值、不启用 Material You；渲染器只读 `LocalMarkdownStyle`，语法色留在 `CodeKind` 不动。
- 样式阶梯（字号/行高/字重/颜色）：正文 16/27 Normal onSurface；H1 28/36、H2 22/30、H3 18/26、H4 16/24 SemiBold onSurface；H5 14/20 SemiBold、H6 14/20 Medium onSurfaceVariant。行高随系统字体缩放（sp）；`lineHeightStyle = LineHeightStyle(Center, None)`；标题 `LineBreak.Heading`、正文 `LineBreak.Paragraph`（minSdk 29 生效）；不额外打包字体、不写 `includeFontPadding`（Compose 1.7 默认已关闭）。
- 间距采用“每块 spaceBefore、首块为 0”模型，移除 `Arrangement.spacedBy`；段落 12、H1 28/12、H2 24/8、H3 20/6、H4–H6 16/4、标题紧跟标题减半（最小 8）、列表 12、引用/代码块 12、分隔线 24/24；紧凑列表 4、松散列表 8（`isTight()`）。
- 强调：Emphasis `Italic`、StrongEmphasis `SemiBold`；删除线 `Strikethrough`（`~~x~~` 与 `~x~`，扩展默认）渲染 `LineThrough`；已完成任务项文字 `onSurfaceVariant`+`LineThrough`。
- 列表：无序每级缩进 20dp、标记按层级循环 `•/◦/▪`；有序右对齐按最大序号位数计算、悬挂缩进沿用 Row+weight、`fontFeatureSettings="tnum"`；任务项视觉缩小 Checkbox（~20dp）+ 局部 `LocalMinimumInteractiveComponentSize`（不压到 24dp 以下），可操作区与图形分离定义。
- 代码块等宽 13/20、`surfaceContainer`、圆角 8dp、内边距 12dp、`softWrap=false + horizontalScroll`、保留 `CodeHighlights` 语法高亮；行内代码 0.9 倍字号、`surfaceContainerHighest` 背景；引用左侧 3dp `outlineVariant` 竖线 + 左缩进 12dp；分隔线 1dp `outlineVariant`；HTML 保持纯文本；表格不在范围（无 tables 扩展）；图片继续占位。
- 页面水平边距 20–24dp、宽屏最大 640dp 居中、顶部底部 24dp；大笔记（沿用 128 KiB 判断）至少关闭 `LineBreak.Paragraph`。

## 7 验证与交付

按 [本轮验证记录](../docs/releases/0.2.7-validation.md) 逐项记录实际结果；Windows/Linux/Android 分别标记，不能互相代替。覆盖升级至少包含当前公开 v0.2.6 并在三端分别记录。core `batch_purge` 属删除语义变更，须独立交叉评审并验证原协议下逐笔收敛、冲突拒绝、原 ID 不复活；桌面设备页涉及鉴权并发，须独立鉴权评审。CI 按既有工作流构建并签署 Windows 更新附件与原证书 APK 并创建 draft；真实安装/真机/设备矩阵与官网/更新目录推广仍由后续任务验收。
