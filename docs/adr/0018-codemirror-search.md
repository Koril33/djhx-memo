# ADR-0018 CodeMirror 笔记内查找

桌面编辑器（CodeMirror 6）引入官方 [@codemirror/search 6.7.2](https://www.npmjs.com/package/@codemirror/search/v/6.7.2)，为 0.1.11 的 Ctrl+F 笔记内查找提供引擎。该包为 MIT 许可、CodeMirror 官方维护，与既有 `@codemirror/state`、`@codemirror/view`、`@codemirror/commands` 同源、同一发布节奏，版本随 [desktop/package.json](../../desktop/package.json) 精确锁定。

只复用查找/高亮/跳转引擎（`SearchQuery`、`setSearchQuery`、`findNext`、`findPrevious`、`matchHighlighter` 经 `highlightSelectionMatches()` 暴露），面板外壳通过 `search({ createPanel, top: true })` 自绘，关闭默认 `searchPanel` 自带的替换框与无隐藏开关的约束。默认语义为字面匹配 + 大小写不敏感（`literal:true, caseSensitive:false`），与全局搜索契约一致，不启用正则/整词/替换。

键位与交互行为见 [快捷键设计](../shortcuts-design.md) 与 [搜索功能详细设计](../search-design.md)。
