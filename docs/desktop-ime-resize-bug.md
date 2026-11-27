# 桌面 Windows 输入法候选框「resize 后跳到左上角」问题记录

- 日期：2026-11-27；状态：**已修复并经维护者真机验证通过**。
- 影响范围：仅 Windows 桌面（Tauri 2 / WebView2）源码编辑器（CodeMirror 6）。Linux / Android 不涉及。
- 结论：这是 **WebView2 的已知缺陷**（上游未修复），不是本应用逻辑 bug；通过「窗口 resize 结束后做一次原生焦点往返（blur→focus）」规避。
- 相关代码：`desktop/src/App.svelte`、`desktop/src/Editor.svelte`。

## 1. 现象与触发条件

- 现象：中文输入法的**候选框/组合窗**偶尔出现在**屏幕左上角**（不是光标处），但上屏的文字仍落在编辑器光标位置，即「位置展示错，内容正确」。
- 触发条件（维护者真机实测）：
  1. **改变窗口大小**即触发（最稳定复现路径）。
  2. 触发后「关闭窗口到托盘 → 从托盘恢复」可恢复正常（直到下一次 resize）。
- 一个误导性的交互：**打开「Markdown 格式检查」（lint）后 resize 不触发**；关掉 lint 则触发。这曾让人误判为「lint 组合期装饰」所致（见 §3）。

## 2. 根因（上游缺陷）

这是 WebView2 的已知问题 [WebView2Feedback #5675](https://github.com/MicrosoftEdge/WebView2Feedback/issues/5675)（状态 Open，无上游修复）。同族问题还有 [wry#1908](https://github.com/tauri-apps/wry/issues/1908)、[tauri#16391](https://github.com/tauri-apps/tauri/issues/16391)（同应用多窗口切换触发）、[Reach#104](https://github.com/alexandrosnt/Reach/issues/104)（Tauri 应用 resize 触发）。

底层机理（与 #5675 报告者给出的一致）：宿主窗口 resize 后，WebView2 的 TSF 文本存储（text store）**不再上报 caret 矩形**——`OnCaretBoundsChanged → TSFBridge::OnTextLayoutChanged → TSFTextStore::SendOnLayoutChange → ITextStoreACPSink::OnLayoutChange` 这条链路不触发，`GetTextExt` 返回陈旧矩形或 `TS_E_NOLAYOUT`。输入法拿不到有效 caret 矩形时回退为**全零矩形**，于是把候选框锚到工作区左上角（屏幕原点）。

为什么「托盘恢复即修复」：原生窗口的 **HWND 焦点往返**会重建 TSF 文本存储关联、重新上报 caret 矩形，从而把输入法锚回原位——这与「移动窗口」「在窗口内点一下」能临时恢复是同一类自愈。

## 3. 排查过程

### 3.1 假设一：lint「组合期装饰派发」——被 A/B 否决

最初怀疑打开 lint 时，`LintController` 在输入法组合期间派发 `setDiagnosticsEffect` 装饰事务、改动 DOM、触发 CodeMirror 重绘而干扰 IME。但维护者实测方向相反：**开 lint 不触发、关 lint 才触发**，与该假设矛盾（若真是组合期装饰，开 lint 应更糟）。

### 3.2 决定性实验：`view.composing` 在组合起始瞬间为假

读 CodeMirror 6.43.15 源码发现（`@codemirror/view/dist/index.js`）：

- `view.composing` 等价于 `inputState.composing > 0`，而 `compositionstart` 只把它置 **0**，要到首次 DOM 变更（`applyDOMChange`）才 `++`。
- 因此原 lint 派发门控 `if (view && lintModule && !view.composing)` 在「组合刚开始」那一段窗口里是**开着的**：每次组合开始都会真的派发一次 `setDiagnosticsEffect`（清空诊断），触发 `docView.update` 返回 `redrawn`，随后 `measure()` 里 `docView.updateSelection(true)` 强制重写 DOM selection → Chromium 重新上报 caret bounds → IME 被重新锚定。

也就是说：**lint 开启之所以「免疫」，是因为它每次组合开始都偶然派发了一次强制重绘，把 resize 造成的陈旧布局顶掉了**；这恰好也印证了「任何引起 caret bounds 真实变化的事件都能治愈」。

实验（提交 `ee4b5cc`）：把门控从 `!view.composing` 改成 `!view.compositionStarted`（CodeMirror 的正确谓词，`inputState.composing >= 0`，从 `compositionstart` 起即为真）。结果：**lint 开启也开始跳左上角**——机理确认。

### 3.3 复审（Claude 子代理）

对「resize 触发 + blur/focus 修复方案」做过一次独立复审，关键结论：

- 根因 = WebView2 #5675，机理成立（非本应用逻辑）。
- lint 免疫的机理（§3.2）是「偶然强制重绘」，不是 30px 几何差异。
- 修复方向：`view.requestMeasure()` 无效（不碰 DOM selection）；`dispatch({effects: scrollIntoView})` 不可靠；宿主侧 `ImmSetCompositionWindow/ImmSetCandidateWindow` 已被社区验证无效；WebView2 无可用 flag。可靠的是**原生焦点往返**。
- 焦点往返的副作用：`contentDOM.blur()` 会触发既有 blur handler → `editor.flush()`（保存/刷新列表/可能重抛冲突错误），必须加抑制旗标；要用 `view.focus()` 而非 `contentDOM.focus()`；要 gate 焦点归属与组合状态；组合中 settle 要延后补偿；blur 与 focus 之间要**延时**，否则 Chromium 可能按帧把两者合并成 no-op。

## 4. 修复方案（最终）

**方案**：窗口 resize 结束（尾沿防抖）后，若编辑器有焦点、无模态、窗口可见、且不在组合中，执行一次**原生焦点往返**：`contentDOM.blur()` → 延时 50ms → `view.focus()`，强制 WebView2 重建 TSF 文本存储关联、重新上报 caret 矩形。

代码（提交 `f1e4056`）：

- `desktop/src/App.svelte`
  - `handleEditorBlur()` 增加 `if (reanchoring) return;` 抑制自家 blur 触发的 flush（`reanchoring` 旗标）。
  - `reanchorEditor()`：gate 条件 `!editor.composing && !view.compositionStarted`；焦点归属 `editorFocused && modalDepth === 0 && document.hasFocus() && visibilityState === 'visible'`；`blur()` → `setTimeout(focus, 50)`；`try/finally` 之外用 `reanchoring` 包裹全程。
  - `onMount` 加 `window.addEventListener('resize', changedWindow)`，`changedWindow` 尾沿防抖 **180ms** 后调用 `reanchorEditor()`；cleanup 与 `onDestroy` 都清理定时器。
  - `onComposition` 回调里，组合结束（`active === false`）时若存在 `pendingReanchor` 则消费之（覆盖「resize 时正在组合」的边缘 case）。
- `desktop/src/Editor.svelte`（保留 `ee4b5cc` 的修正）：lint 装饰派发门控由 `!view.composing` 改为 `!view.compositionStarted`，修掉组合起始窗口误派发（这是独立的正确性修正，无论本 bug 都保留）。

关键细节：

- **延时 focus 50ms**：同一 task 内 blur+focus 的 TSF 净变化为零，可能被 Chromium 按帧批量合并而不产生重关联，故 focus 延后一拍。
- **`view.focus()` 优于 `contentDOM.focus()`**：`EditorView.focus()` 内部用 `focusPreventScroll`（不把滚动条拽到光标）+ 屏蔽 MutationObserver（避免把这次焦点往返误读成文档变更）+ 从 state 重写 DOM selection。
- **门控用 `editor.composing` + `view.compositionStarted`**，不用 `view.composing`（后者在组合起始瞬间为假，会吞掉正在拼的字）。

## 5. 尝试过但被否决的方案

- **装饰切换强制重绘**（提交 `9ffab48`，已回退）：resize 结束后派发一次隐形 `Decoration.mark` 添加再移除，期望触发 `docView.update → redrawn → updateSelection(true)` 重新上报 caret。**维护者真机实测无效**——说明在真实 WebView2 上，纯 DOM 重绘不会让 TSF 重新上报 caret 矩形（与 #5675「页面侧 caret rect 不对」的排除一致）。故回退，改用 §4 的原生焦点往返。
- `view.requestMeasure()`：measure 不碰 DOM selection，除非 `redrawn` 为真才 `updateSelection(true)`，纯 measure 无效。
- `dispatch({effects: scrollIntoView})`：光标已可见时是 no-op，不重写 DOM selection。

## 6. 相关上游 issue

- [WebView2Feedback #5675 — IME composition window jumps to top-left after host resize](https://github.com/MicrosoftEdge/WebView2Feedback/issues/5675)
- [tauri-apps/wry #1908 — IME composition/candidate UI loses caret positioning](https://github.com/tauri-apps/wry/issues/1908)
- [tauri-apps/tauri #16391](https://github.com/tauri-apps/tauri/issues/16391)
- [alexandrosnt/Reach #104 — Resizing also triggers the IME candidate window offset](https://github.com/alexandrosnt/Reach/issues/104)
- 社区同类修复思路：[hctty27/openshell#235](https://github.com/hctty27/openshell/pull/235)（延后 refocus）、[boltomli/uniterm#2](https://github.com/boltomli/uniterm/pull/2)（blur/refocus 强迫重算）

## 7. 验证与回归

- 本地：`svelte-check` 0 错、eslint 0、prettier 通过、61 项单测通过、`vite build` 通过。
- 真机（维护者，Windows + 中文 IME）：
  1. resize 后输入中文，候选框停在光标处，lint 开/关均通过；
  2. resize 后立刻打字，焦点/光标不丢、编辑器状态不出现「保存中」闪烁（`reanchoring` 抑制 flush 生效）；
  3. 拼中文时 resize，上屏后再输入一次候选框正常（`pendingReanchor` 补偿生效）；
  4. 普通连续输入不吞字、托盘恢复焦点正常、代码块高亮不受影响。

## 8. 后续注意

- 若未来 WebView2 运行时升级后仍复现，优先检查本方案的 **50ms 延时**是否被合并：可先加大到 200ms（社区修复用过 200~700ms）。
- 当前修复只覆盖**源码编辑器**（`sourceView`）。若后续在 `FindPanel` 输入框、笔记列表搜索框、全局搜索框里也遇到 resize 后候选框错位，应按同一思路对**当前 `document.activeElement`** 做焦点往返，而非只针对编辑器。
- `main.rs` 的 `show_window` 已把 `set_focus` 延后 80ms（注释即为此类 WebView2 IME 问题而写）；本文档所述修复走的是**前端 DOM 焦点往返**，与 Rust 侧窗口激活的移焦延后是两处互补、不冲突的机制。
