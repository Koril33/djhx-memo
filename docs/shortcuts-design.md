# 快捷键设计：Ctrl+W / Ctrl+F / Ctrl+Shift+F

- 状态：§1–9 保留 0.1.11 设计及当时定稿，功能已实现；验证边界见 [0.1.11 记录](releases/0.1.11-validation.md)。**0.1.13 的两段式 Ctrl+W 与空白页见 §10，目标已定、未实施。** 本文为快捷键详细设计入口，产品行为以对应 spec 为准，后续修改同步维护。
- 关联文档：[搜索功能详细设计](search-design.md)、[0.1.11 目标规范](../spec/client-0.1.11.md)。
- 范围：仅 Windows/Linux 桌面端。Android 是触摸界面，不涉及本文的 Ctrl 组合键（系统返回键已有 BackHandler 处理，见 0.1.10 现状）。

## 1. 0.1.10 现有快捷键盘点

### 1.1 应用级（`desktop/src/App.svelte` 的 `<svelte:window on:keydown>`）

| 快捷键 | 行为 | 备注 |
|---|---|---|
| Ctrl/Cmd + N | 新建笔记 | 全局，编辑器聚焦时也生效 |
| Ctrl/Cmd + S | flush 保存 | 全局 |
| Escape | 关闭菜单；取消列表多选 | 全局 |
| Tab / Shift+Tab | 模态框内焦点陷阱 | 仅 `modal()` 包裹的模态框 |

### 1.2 编辑器（`desktop/src/Editor.svelte`，CodeMirror 6）

当前引入 `defaultKeymap` + `historyKeymap` + `indentWithTab`（`@codemirror/commands`）。**未引入** `emacsStyleKeymap`，也**未引入** `@codemirror/search`。

| 快捷键 | 行为 |
|---|---|
| Mod-Z / Mod-Shift-Z / Mod-Y | 撤销 / 重做 |
| Mod-A | 全选 |
| 方向键 / Home / End / PageUp / PageDown | 光标移动 |
| Backspace / Delete / Mod-Backspace / Mod-Delete | 删除 |
| Mod-Enter / Alt-Enter | 插入空行 |
| Tab | 缩进（`indentWithTab`） |

> 注意：`defaultKeymap` **不含** Ctrl+F / Ctrl+B / Ctrl+N 等 Emacs 风格绑定（那些在 `emacsStyleKeymap`，未引入）。因此 Ctrl+F / Ctrl+W / Ctrl+Shift+F 目前在编辑器内均为**空闲**。

### 1.3 Android（`MainActivity.kt`）

- 系统返回键（BackHandler）：编辑页返回列表；多选状态取消多选。无 Ctrl 组合键。

### 1.4 结论

0.1.10 没有任何 Ctrl+W / Ctrl+F / Ctrl+Shift+F / F3 绑定。三个目标快捷键均为空闲，可在不冲突的前提下引入。

## 2. 目标快捷键与边界

| 快捷键 | 功能 | 生效上下文 |
|---|---|---|
| Ctrl+W | 关闭到托盘（等同点击窗口关闭按钮） | 应用聚焦，全局 |
| Ctrl+F | 笔记内查找（当前打开的笔记） | 编辑器聚焦（有打开笔记时） |
| Ctrl+Shift+F | 全局搜索（跨笔记） | 应用聚焦，全局 |
| F3 / Shift+F3（及 Mod+G / Mod+Shift+G） | 笔记内查找：下一个 / 上一个 | 查找面板打开时 |

**关键边界：仅应用聚焦内生效，不做 OS 级全局热键。**

本项目 AGENTS 与 spec 中"全局快捷键暂缓"指的是 OS 级全局热键（应用不在前台也能触发，需 Tauri `globalShortcut` 或系统级注册）。本文的快捷键是 WebView 收到的 `keydown` 事件，仅本应用聚焦时触发，两者不冲突。应用已启用单实例（`tauri_plugin_single_instance`），保证同一时刻只有一个窗口能收到按键。窗口隐藏到托盘后键盘路径即失效，唯一恢复入口是托盘菜单/图标点击/单实例唤起——这要求托盘可用性成为 Ctrl+W 的正确性前提（见 §3.4）。

### 2.1 基础机制：全局 keydown 守卫（所有应用级快捷键统一遵守）

CodeMirror 6 的 keymap 命中时调用 `preventDefault()`，但**不 `stopPropagation()`**，事件仍会冒泡到 `<svelte:window on:keydown>`。因此 `App.svelte` 的全局处理器必须按以下固定顺序加三道守卫（**实现前用一个 spike 实测确认 `defaultPrevented` 行为**）：

1. `if (event.defaultPrevented) return;` —— 编辑器（或任何内层组件）已消费该键，全局不再插手。
2. `if (event.isComposing || event.keyCode === 229) return;` —— IME 预编辑期一律不处理（中文/日文输入防误触）。
3. 上下文判定：目标是否在 `input/textarea/contenteditable`、是否有模态框打开、是否多选态。

另两条硬规则：

- 全局处理器**绝不用 `capture: true`**（否则守卫 1 失效）。
- 应用要接管的组合键（Ctrl+S / Ctrl+N / Ctrl+W / Ctrl+Shift+F）**必须显式 `preventDefault()`**，否则 WebView2/WebKitGTK 可能触发"网页另存为"等原生行为。
- 修饰键判定在 Windows/Linux 只认 `ctrlKey`，**不把 `metaKey` 当 Mod**（Linux 上 Super 属桌面环境）。macOS 非目标平台，预留 `isMac` 开关位即可。

## 3. Ctrl+W 关闭到托盘

### 3.1 现状（关闭按钮的既有链路）

点击窗口关闭按钮（`desktop/src-tauri/src/main.rs`）：

1. `WindowEvent::CloseRequested` → `api.prevent_close()` + `request_lifecycle(app, Lifecycle::Hide)`。
2. `request_lifecycle`：更新安装屏障、`frontend_ready` 检查、托盘可用性判断（无托盘 → `Lifecycle::Quit`）、去重后向前端发 `memo-lifecycle` 事件。
3. 前端 `App.svelte` 收到事件 → `editor.finishLifecycle(...)` 先 flush。
4. `finish_lifecycle` 命令：`saved=false`（保存失败）→ `show_window` 中止关闭；`saved=true` → 有托盘 `window.hide()`，无托盘 `app.exit(0)`。

### 3.2 方案：复用关闭链路，新增一个 Tauri 命令（不放开窗口权限）

前端在全局 keydown 拦截 `Ctrl+W`（仅 `ctrlKey`，不认 metaKey）后，调用新增 Tauri 命令：

```rust
#[tauri::command]
fn request_hide(app: tauri::AppHandle) {
    request_lifecycle(&app, Lifecycle::Hide);
}
```

前端 `native('request_hide')`。`request_lifecycle(Hide)` 正是 `CloseRequested` 分支所调用的同一入口，因此与标题栏 X、Alt+F4、任务栏"关闭窗口"行为一致（flush → 有托盘 hide / 无托盘 quit / 保存失败中止），且**不需要放开 `core:window:*` 前端权限**。

> 权衡：`getCurrentWindow().close()`（`@tauri-apps/api/window`）需在 `capabilities/main.json` 增加 `core:window:allow-close`，扩大前端权限面，故 0.1.11 弃用；`request_hide` 命令零权限扩张、等价语义，为优选。

### 3.3 为什么这样设计

- **语义完全一致**：Ctrl+W 就是"点关闭按钮"的键盘等价，自动继承保存失败中止、更新安装屏障、无托盘时退出、pending 去重、单实例等全部既有约束，不另写一套隐藏逻辑。
- **零冲突**：CodeMirror `defaultKeymap` 不绑定 Ctrl+W，编辑器内不冲突（`Ctrl-w`=删词的绑定在未引入的 `emacsStyleKeymap`）。
- **浏览器肌肉记忆**：Windows/Linux 浏览器 Ctrl+W = 关闭当前标签页；本应用无标签页，等价语义是"关闭当前窗口"，而关闭窗口 = 隐藏到托盘，映射自然。

### 3.4 边界与风险（决策已定）

- **IME 组合期间**：守卫 2（`isComposing`/`keyCode 229`）统一忽略，避免中文输入法组合时误触发；与现有 Ctrl+N / Ctrl+S 一并补上此保护（决定：是）。
- **模态框内**：忽略（Escape 负责关模态框，避免"隐藏后恢复还挂着一个半填完的确认框"）。**决定：不触发。**
- **🔴 Readline 肌肉记忆（Linux/终端用户）**：终端里 Ctrl+W = 删除前一个词，GTK 文本框也普遍支持；这批用户在编辑器里按 Ctrl+W 会意外让窗口消失。缓解：(a) 显式绑定 Ctrl+Backspace = 删除前一个词，给出正确出口；(b) 设置项允许关闭 Ctrl+W。
- **🔴 托盘不可见的残留风险（Linux）**：现有 `tray_host_available()` 用 gdbus 探测 `org.kde.StatusNotifierWatcher`，失败时降级为"正常窗口模式"（`Lifecycle::Quit`）。但 watcher 注册成功 ≠ 图标真实可见（vanilla GNOME 无扩展、用户禁用扩展等）。**决定：0.1.11 维持现状 `hide()`（Windows/Linux 一致）**；本文保留下列分析供未来参考——若实测发现 Linux 托盘静默不可见导致窗口不可找回，再改为 **Linux 默认 `minimize()` 而非 `hide()`**（最小化保留任务栏/Dock 入口，恢复不依赖托盘），并把"关闭到托盘"做成显式设置项（默认关）；无论何时都保证单实例插件"再次启动时 show + unminimize + set_focus 已有窗口"作为最后兜底。此点与 [product-v1 §8](../spec/product-v1.md) 的托盘约束一致，需在 Ubuntu 24.04 GNOME Wayland + X11 实测记录。
- **产品语义（已定稿）**：Ctrl+W 定义为"关闭窗口到托盘"（窗口级）。若未来引入多笔记 tab，该语义必须改为"关闭当前笔记"（窗口级另用 Ctrl+Shift+W）。现按"本项目不做 tab、Ctrl+W=窗口级"定稿。
- **保存失败**：沿用现有"flush 失败则 `show_window` 中止关闭"（隐藏≠退出，进程与内存缓冲仍在，但仍阻止隐藏以保持"失败让窗口留在眼前 + 可重试"的同一心智模型）。
- **首次隐藏提示**：首次通过 Ctrl+W 隐藏时提示一次"窗口已隐藏到托盘/任务栏，如何恢复"（持久化标记只提示一次）。

## 4. Ctrl+F 笔记内查找

### 4.1 方案：采用 CodeMirror 官方 `@codemirror/search`

在 `Editor.svelte` 引入 `@codemirror/search`：

- `search({ createPanel, top: true })`：用 `createPanel` **自定义面板外壳**（输入框 + 命中计数 + 上/下一个 + 大小写开关 + 关闭），查找/高亮/跳转复用 CM 的 `setSearchQuery` / `findNext` / `findPrevious` / `matchHighlighter`（关闭面板后仍高亮命中）。
- `searchKeymap`：绑定 `Mod-f`（打开查找）、`F3`/`Mod-g`（下一个）、`Shift-F3`/`Mod-Shift-g`（上一个）。
- `highlightSelectionMatches()`：高亮所有命中（可选）。

这是 CodeMirror 官方、与 VSCode/Monaco find widget 同源的成熟方案；仅面板外壳少量自研（因为默认 `searchPanel` 自带替换框且无隐藏开关，而本版不启用替换），查找/高亮/跳转引擎全部复用 CM。

### 4.2 关键决策

- **默认面板 vs 自绘面板**：选 `createPanel` 自绘轻量外壳，**唯一目的是关掉替换框并自守 IME Enter 二义**（默认面板两件事都做不到）；查找语义仍走 `SearchQuery{ literal:true, caseSensitive:false }`。
- **大小写/正则/整词开关**：第一版只保留"大小写"开关（默认不敏感，与全局搜索一致）；不提供正则/整词/替换。
- **替换**：第一版**不启用**（面板无替换框），避免与 Markdown 预览同步、撤销栈、IME 组合的交互复杂度。后续若做，需单独评审。
- **窗口级绑定 Ctrl+F**：窗口级 handler **绑定** Ctrl+F（守卫 1 `defaultPrevented` 保证编辑器聚焦时由 CM 的 `searchKeymap` 先消费、窗口级不重复处理；编辑器未聚焦时由窗口级接管"聚焦编辑器并开面板 / 无笔记无动作"）。这正是 §4.3 触发语义得以成立的前提。
- **Ctrl+Shift+F 在窗口级绑定**：`Mod-Shift-f` 在 `searchKeymap`/`defaultKeymap` 均未被占用，编辑器聚焦时 CM 不消费、冒泡到全局接管，安全。
- **keymap 顺序**：`searchKeymap` 放在 `defaultKeymap` 之前（二者无重叠键，顺序只是保险）。注意 `searchKeymap` 会一并带入 `Mod-d`（选中下一个）、`Mod-Alt-g`（全部选中）等，须在 §6 冲突矩阵登记、必要时显式覆盖。
- **IME 下的 Enter 二义**：自绘面板输入框自守 `isComposing` 门禁（compositionstart/end），中文输入时 Enter 不上屏触发查找下一个（见评审 A2 实测）。

### 4.3 触发语义

- 编辑器聚焦时：Ctrl+F 打开笔记内查找面板。
- 编辑器未聚焦但已有打开笔记时：Ctrl+F 聚焦编辑器并命令式 `openSearchPanel`。
- 无打开笔记时：**无动作**（不转发到全局搜索；用户可用 Ctrl+Shift+F 显式发起全局搜索）。**决定：无动作。**
- **预览态**：WebView2/WebKitGTK 无可用的原生 Ctrl+F 查找 UI，且给渲染态 DOM 做 range 高亮成本高。第一版：预览态按 Ctrl+F = 切到编辑态再打开查找面板（不承诺预览态原地查找）。

### 4.4 Escape 优先级链（indentWithTab 焦点陷阱的逃生口）

`indentWithTab` 让编辑器吃掉 Tab，纯键盘用户无法 Tab 出编辑器。Escape 需按优先级承担逃生口，顺序固定：

1. 查找面板打开 → Escape 关面板（CM 消费，`defaultPrevented`，不冒泡）。
2. 无面板、编辑器聚焦 → Escape 使编辑器失焦（焦点移到列表），而非空转冒泡去关菜单。
3. 无面板、非编辑器聚焦 → Escape 关菜单 / 取消多选。

此优先级链需写测试：①面板开时 Escape 不取消列表多选；②模态框内 Escape 不穿透到编辑器。

## 5. Ctrl+Shift+F 全局搜索

窗口级 `keydown` 拦截 `Ctrl+Shift+F`（守卫通过后），打开全局搜索浮层（命令面板式居中浮层，复用现有模态框的 Escape 关闭 + Tab 焦点陷阱），焦点落搜索框。触发 core 新 `search_notes` 动作。范围过滤、相关性排序、命中高亮、上下文片段、跳转与性能设计详见 [搜索功能详细设计](search-design.md)。

冲突：CodeMirror `defaultKeymap`/`searchKeymap` 不绑定 Mod-Shift-F；浏览器内 Ctrl+Shift+F 无统一语义（部分浏览器是"再次搜索"），本应用内独占安全。

> 关键提示：账号空间搜索在第一版是**纯本地搜索已同步到本机的内容**，UI 必须显式说明（如"仅搜索已同步到本机的笔记"），否则用户误判为服务端全量搜索、漏结果时当成数据丢失 bug。这是最易产生信任危机的点。

## 6. 快捷键冲突矩阵

| 上下文 | Ctrl+W | Ctrl+F | Ctrl+Shift+F | Ctrl+N | Ctrl+S | Escape | F3/Mod-G |
|---|---|---|---|---|---|---|---|
| 编辑器聚焦 | 关闭到托盘 | 笔记内查找（CM 面板） | 全局搜索 | 新建 | flush | 面板开→关面板；否则编辑器失焦（§4.4） | 笔记内下一个 |
| 笔记列表聚焦 | 关闭到托盘 | 聚焦编辑器开面板；无笔记→无动作 | 全局搜索 | 新建 | flush | 关菜单/取消多选 | — |
| 模态框聚焦 | 忽略 | 忽略 | 忽略 | 忽略 | flush（允许，纯保存无状态跳转） | 关闭模态框 | 忽略 |
| 预览聚焦 | 关闭到托盘 | 切编辑态 + 开面板 | 全局搜索 | 新建 | flush | 关菜单 | — |

> 模态框内的 Tab 焦点陷阱与 Escape 由 `modal()` 动作管理；应在模态框内屏蔽窗口级 Ctrl+N/W/F/Shift+F（守卫 3 一刀切），仅放行 Ctrl+S。

## 7. 平台差异与已知风险

- **Linux/Wayland**：本文快捷键是 WebView `keydown`，不受 Wayland 全局热键协议限制（那是 OS 级热键的问题）。GNOME 抢占的是 Super 系、Ctrl+Alt+方向、Alt+F2/F4 等；Ctrl+W/Ctrl+F/Ctrl+Shift+F/F3/Ctrl+G 均不在 DE 全局绑定内。Wayland 真实限制在"程序化置顶/抢焦点"（`set_focus` 受 xdg-activation 限制，恢复窗口需实测）与"托盘图标依赖 host 扩展"（影响 Ctrl+W 恢复路径）。fcitx/ibus 输入法可能先消费部分组合键，需在 Ubuntu 24.04 GNOME（X11 + Wayland）实测。
- **Windows**：应用内快捷键无系统级冲突；系统保留的是 Win 组合等。注意"高级键盘设置"里 Ctrl+Shift 可被配置为切换输入语言（默认是 Left Alt+Shift，部分中文用户改过），此时 Ctrl+Shift+F 可能顺带切布局；风险可接受，若实测严重备选键位为 Ctrl+Shift+P 或 Ctrl+K。
- **输入法**：所有新增窗口级 handler 统一加 `event.isComposing` 保护（守卫 2），中文组合输入不得被快捷键中断（与 AGENTS"不中断 IME"一致）。
- **WebView 原生默认行为**：Ctrl+S（另存为）、Ctrl+F（WebView2 可能自带查找）、Ctrl+W 的原生默认行为必须被显式 `preventDefault()` 完全压制（守卫硬规则）；双平台实测，尤其 Ctrl+S。
- **单实例**：`tauri_plugin_single_instance` 保证无多窗口按键竞争，并作为托盘失效时"再次启动唤起已有窗口"的兜底。

## 8. 已确认决策（2026-11-20，维护者定稿）

1. Ctrl+W 在模态框内：**不触发**。
2. Ctrl+F 无打开笔记时：**无动作**。
3. 顺带为现有 Ctrl+N/S 增加 `isComposing` 保护：**是**（纳入守卫 2 统一改造）。
4. 替换功能是否进入 0.1.11：**不启用**。
5. Ctrl+W 产品语义：**窗口级关闭到托盘**（本项目不做多笔记 tab）。
6. Linux 关闭行为：**维持 `hide()`**（现状）；`minimize()` 加固方案与理由保留在 §3.4 供未来参考。
7. Escape 优先级链：**接受**"面板 > 编辑器失焦 > 关菜单/取消多选"（§4.4）。
8. Android：无键盘快捷键，本版补充**笔记内查找 UI + 全局搜索 UI**（复用 core），见 [搜索功能详细设计](search-design.md) §11 与 [0.1.11 规范](../spec/client-0.1.11.md) §6。

## 9. 依赖与代码变更

- 新增前端依赖 `@codemirror/search`（与既有 CodeMirror 6 同源，版本随桌面 `package.json` 锁定）。按 [AGENTS](../AGENTS.md) 需在引入前补一条 ADR 记录许可与锁定版本（MIT，CodeMirror 官方维护，与既有 `@codemirror/*` 一致）。
- Ctrl+W 新增 Tauri 命令 `request_hide`（`request_lifecycle(app, Lifecycle::Hide)`）并注册进 `generate_handler!`，前端 `native('request_hide')`；**不放开 `core:window:*` 权限**（Linux 本版维持 `hide()`，不改 `finish_lifecycle`）。
- core/Rust 侧搜索改动见 [搜索功能详细设计](search-design.md)（`db::search_notes` + `search_notes` 动作 + C4 触发器迁移）。

## 10. 0.1.13 定稿增量（未实施）

产品规则见 [0.1.13 §2](../spec/client-0.1.13.md#2-桌面空白页与会话)，实施与保存/过渡用例见 [开发文档 §3](client-0.1.13-development.md#3-w1桌面空白页与会话状态)。本节替代旧版“Ctrl+W 永远等同 X”的目标，其他按键与原生 lifecycle 保留。

| 上下文 | Ctrl+W | Ctrl+F | Ctrl+Shift+F |
|---|---|---|---|
| 打开草稿/笔记 | 最终输入保存确认后关当前文档，回空白页 | 保持笔记内查找 | 全局搜索 |
| 空白页 | 既有 request_hide；有托盘隐藏、无托盘退出 | 无动作 | 全局搜索 |
| composing/模态框 | 忽略 | 保留既有守卫 | 保留既有守卫 |
| 会话过渡或安装锁定中 | 忽略重复导航，不排队隐藏 | 不穿过保护 | 不穿过保护 |

冷启动进入空白页，托盘恢复原页面；新建直达草稿，成功切空间/分区与删除当前笔记后回空白页，导入成功继续打开。欢迎页提示 New Note Ctrl+N、Search Notes Ctrl+Shift+F、Close Window Ctrl+W；不把 Ctrl+F 提示为全局搜索。

长按/自动重复不能把一次关文档变成隐藏窗口；flush 失败保留原缓冲和救援。X、Alt+F4、任务栏关闭、托盘退出继续直接走窗口级安全 lifecycle，不增加空白页步骤。Android 不新增空白页或 Ctrl 快捷键。
