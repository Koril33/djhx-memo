# 0.2.3 开发方案

- 日期：2026-11-24；状态：**方案定稿，未实施**。
- 目标行为以 [spec/client-0.2.3.md](../spec/client-0.2.3.md) 为准；本文供后续开发、测试、评审和发行使用。
- 基线为已公开 `v0.2.2`；客户端 0.2.2、Android code17、core 0.2.2、server 0.1.4、协议 2、local schema4 / server schema2。本轮客户端 0.2.3、Android code18；core/server/协议/schema/包名/证书不变。

## 1 复审结论与范围

五项改动经 3 个 Claude 子代理交叉评审（桌面 UI、菜单、规范/流程三个维度）。结论：**无方向性错误，但第 1、3 项存在前提性事实错误或与现行规范冲突，第 2 项描述有误，须按下述已定稿取舍实施**。

| 项 | 结论 | 已定稿取舍 |
|---|---|---|
| 1 隐藏说明文本 | 成立，但“软件更新”页其实无 `.help-text`；范围收窄为设置弹窗内 5 处 `.help-text` | 全部折叠进“ⓘ”，需修订 `product-v1.md` §11 第 137 条 |
| 2 固定设置高度 | 描述有误，现状已能滚动，真实痛点是切 tab 跳动 | 改 `.dialog-content` 最小高度 + `scrollbar-gutter: stable` |
| 3 工具栏图标化 | “格式提示”不是开关，是打开问题列表 | 保留“打开问题列表”语义，仅图标化；不做 3b 开关 |
| 4 桌面删“选择笔记” | 成立，是修正与 `client-0.1.8:7` 的偏差 | 删除，记为 spec 偏差修正 |
| 5 安卓 Delete 下移 | 成立但描述不完整 | 两端对齐破坏性置底；Purge 一并置底、Restore 留上部、加分隔线 |

关键冲突及其化解：

- **“格式提示”前提错误**：`App.svelte:1440-1446` 的按钮是 `mode='source'; openLint()`（单向打开面板），非开关；且 `Editor.svelte` 内还有 `.lint-status` 同名 toggle。若硬改成开关会自锁、丢“打开列表”入口、单图标无法承载 5 种 lint 相位。**化解：保留打开列表语义，只做图标化，`client-0.2.2.md` 的 lint 行为不变。**
- **“4 个图标”与图标管线冲突**：全库 toggle 一律 `aria-pressed + class:chosen`，无成对字形；`generate-ui-icons.py` 对每个 SVG 无条件产出 Android `ic_ui_*.xml`。**化解：最多新增 1–2 个字形 + 状态样式。**
- **第 1 项与 `product-v1.md:137` 冲突**：`copySpaceHelp`/`exportHelp`/`lintHelp` 是数据安全事实。**化解：折叠后内容不删减，并修订 §137 为“按需展开后仍保留全部事实”。**
- **第 5 项与桌面现状冲突**：桌面 `noteActions` 破坏项在顶部。**化解：两端对齐置底。**

## 2 当前入口与影响面

| 入口 | 当前事实 | 实施方向 |
|---|---|---|
| [App.svelte](../desktop/src/App.svelte) | 设置弹窗 5 处 `.help-text`（2068/2006/2021/2101/2111）；工具栏文本按钮（1431-1446）；列表“更多”菜单含无图标“选择笔记”（1294-1301）；编辑器 `noteActions` 菜单（1447-1496） | 新增“ⓘ”内联展开；图标化工具栏；删菜单项；重排 `noteActions` |
| [style.css](../desktop/src/style.css) | `.settings-dialog` 已 `max-height` + `.dialog-content{overflow-y:auto}`；`.help-text`、`.text-button`、`.view-switch .chosen` | `.dialog-content` 加 `min-height` + `scrollbar-gutter:stable`；新增帮助展开/分隔线样式 |
| [Editor.svelte](../desktop/src/Editor.svelte) | `.lint-status` 状态条承载相位/计数/“检查一次”；`openLint` 为单向打开 | 保持不动（格式提示只改 App 侧头部按钮） |
| [Updates.svelte](../desktop/src/Updates.svelte) | `.update-muted` 混有实时状态与恢复提示 | 不纳入本范围，保持常驻 |
| [icons.ts](../desktop/src/lib/icons.ts) / [UPSTREAM.json](../assets/ui-icons/UPSTREAM.json) | Lucide 1.70.0 固定 34 字形，`info`/`columns-2`/`list-checks` 已在 | 最多新增 1–2 字形并重跑生成 + `--check` |
| [MainActivity.kt](../android/app/src/main/java/app/djhx/memo/MainActivity.kt) | 编辑态菜单：查找→删除/恢复(同槽)→彻底删除→导入→导出→状态→设置 | 拆分槽位、破坏项置底、加分隔线 |

## 3 逐项设计

### 3.1 桌面设置说明文本按需展开

- 新建 `Help.svelte`（或复用既有 `<details data-menu>` 范式）：`<summary>` 为 `info` 图标按钮（`role="button"` + `svelte-ignore a11y_no_redundant_roles`，WebKitGTK AT-SPI 要求），`aria-label`/`title` 为“查看说明”，内容为说明文本。
- 五处 `.help-text` 逐一改为该组件，`aria-describedby` 指向被说明控件。展开不抢焦点、Esc/外点/再次点击关闭，并修 `modal()` 的 Escape 优先级：先关闭内层 `details[data-menu][open]`，无内层弹层再关对话框。
- 说明文本不删减；`Updates.svelte` 的 `.update-muted` 保持原样。

### 3.2 桌面设置弹窗高度稳定

- `.dialog-content { min-height: calc(100vh * 2 / 3); scrollbar-gutter: stable; }`，不加 `height`；`.settings-dialog` 保持 `max-height: calc(100vh - 48px)`。该值为初始值，实测切 tab 后冻结。
- 实测切 tab 时 `scrollTop` 是否被浏览器钳位（`.dialog-content` 是跨 tab 复用节点）；若残留，切 tab 时重置 `scrollTop = 0`。
- 验收须覆盖：中英 × 浅深 × 最小窗口宽度 × 原生 select 不裁切 × 关闭图标不裁切。

### 3.3 桌面编辑器工具栏图标化

- “对照同步滚动”：`mode==='split'` 时渲染图标按钮 `arrow-down-up`，`aria-pressed={preferences.splitScrollSync}` + `title`/`aria-label`，点击写 `preferences.splitScrollSync`（与设置同源联动，已天然联动）。开/关用 `class:chosen`/active 样式区分。
- “格式提示”：`preferences.markdownLintEnabled` 时渲染图标按钮 `spell-check`，`aria-label`/`title`，点击维持 `mode='source'; openLint?.()`。`Editor.svelte` 状态条不动。
- 图标：`arrow-down-up`、`spell-check` 均不在现有 34 字形内，为新增；写入 `UPSTREAM.json`（files + 逐文件 SHA256 + 非规范名 aliases），跑 `python3 scripts/generate-ui-icons.py` + `--check`，在 ADR-0011 末尾追加一行（照 0.1.8 句式）。新增会带入同名 Android 未用 drawable，如实记录 Android lint 警告数变化，不加 baseline/tools:ignore。

### 3.4 桌面列表多选入口去重

- 删除 `App.svelte:1294-1301` 的“选择笔记”菜单项；`selectNotes` 键保留（顶部按钮仍用）。
- 顺手给 `pendingMarkdown` 取消项补 `x` 图标，消除菜单内最后一个无图标项。
- 提交说明记为“修正与 client-0.1.8 的偏差”。

### 3.5 破坏性操作菜单位置（两端对齐）

- Android：拆 Delete/Restore 槽位；编辑态顺序改为 查找 → 恢复(仅 deleted) → 导入 → 导出 → 状态 → 设置 → `HorizontalDivider` → 删除(!deleted) / 彻底删除(deleted&&!purged)。列表态不动。
- 桌面 `noteActions`：恢复(仅 deleted) → 复制文字 → 导出当前笔记 → 分隔线 → 移入回收站(!deleted) / 彻底删除(deleted&&!purged)。
- “导入 Markdown”在编辑态出现的既有问题本轮不处理，记录为已知项。

## 4 验收矩阵

实际已测/未测以 [0.2.3 验证记录](../docs/releases/0.2.3-validation.md) 逐项区分；Windows 与 Linux 分别记录，Android 单独记录，含改动前后截图。

| ID | 场景 | 必须满足 |
|---|---|---|
| H01 | 五处说明展开/关闭、Esc/外点/焦点返回 | 内容完整不删减；不抢焦点；读屏可读（aria-describedby） |
| H02 | 六 tab 切高度、原生 select、关闭图标 | 不跳动；不裁切；滚动不横向抖动 |
| H03 | 编辑器图标 `aria-pressed`/可访问名、lint 面板 | 同步滚动开/关态可辨；格式提示仍打开列表；`.lint-status` 不变 |
| H04 | 列表多选入口、只读空间、退出选择 | 入口唯一化；只读/退出路径无回退 |
| M01 | 两端菜单顺序、破坏项分组、分隔线 | 破坏性置底；恢复留上部；任一时刻至多一个破坏项 |
| M02 | Android 列表态菜单、编辑态菜单、TalkBack | 列表态设置仍末位；48dp 命中区；读序正确 |

## 5 规范与文档同步清单（同批提交）

1. 新建 [spec/client-0.2.3.md](../spec/client-0.2.3.md)（已完成）。
2. 修订 `product-v1.md` §11 第 137 条：说明文本由“常驻保留”改为“按需展开后仍保留全部事实”。
3. [spec/README.md](../spec/README.md) 增加 client-0.2.3 索引行；[AGENTS.md](../AGENTS.md) 更新当前状态段与 0.2.3 条目；[docs/validation.md](../docs/validation.md) 更新基线行。
4. [docs/ui-design.md](../docs/ui-design.md) 新增“0.2.3 控件与设置整理”节（对齐 0.1.6/0.1.8 两节先例）。
5. ADR-0011 末尾追加一行（若新增图标）。
6. 新建 [docs/releases/0.2.3-validation.md](../docs/releases/0.2.3-validation.md)（含截图）。

## 6 实施顺序和交付

1. 先做桌面设置（3.1 + 3.2，存在顺序依赖：先固定内容区高度，再做内联展开，展开才不会导致弹窗跳动），再做工具栏图标化（3.3，含图标管线）与菜单（3.4、3.5）。
2. 桌面与 Android 各自验证后同批提交独立分支/PR；本版不改 core/server，无需重建 JNI/UniFFI。
3. 检查清单：`bash scripts/validate-local.sh desktop` + `npm run lint`（`--max-warnings 0`）+ `npm run format:check`；`bash scripts/validate-local.sh android`（对比 detekt 17 项与 lint 警告数）；`python3 scripts/generate-ui-icons.py --check`（若动图标）；`python3 scripts/check-docs.py`；`git diff --check`。Windows 原生 WebView2 与目标 Linux GUI 分别验收，不能互相代替；Android 菜单顺序建议补 Compose UI 测试，否则仅手测记录。
4. 公开/官网/更新目录推广由后续任务验收；本版不改下载源/更新目录字段。

本轮四项关键取舍已由维护者定稿（格式提示仅图标化、说明文本全部折叠、两端对齐置底、0–2 字形 + 状态样式）。图标定为同步滚动 `arrow-down-up`、格式提示 `spell-check`（新增，须按固定 Lucide 1.70.0 提交 `b826f0c` 核实存在）；正文区最小高度取窗口三分之二（`calc(100vh * 2 / 3)`）。`spell-check` 与设置页说明“ⓘ”所用的 `info` 语义区分，不混用。无剩余产品方向待确认项。
