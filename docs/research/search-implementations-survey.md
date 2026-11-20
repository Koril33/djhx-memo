# 笔记内查找 & 全局搜索实现方案调研（开源交叉评估）

- 状态：0.1.11 搜索功能调研记录，2026-11-20。结论已提炼进 [搜索功能详细设计](../search-design.md) §10 与 §14；本文保留逐项目细节与"未查到/不确定项"，供后续实现核对。
- 调研范围：必查 VS Code、Zed；自选 Logseq、Joplin、SiYuan、Obsidian、Trilium、SilverBullet、QOwnNotes、CherryTree、Standard Notes、Helix、Kate/KWrite、ripgrep、fzf、Telescope.nvim、Zettlr。

## 结论速览

1. **Logseq 是最近参照**：同样 SQLite FTS5 + `trigram`，全文索引/查询放 DB worker 线程，用"精确标题 → FTS5 → 模糊"多路回退；明确记录 trigram 对 <3 字符短查询"天生噪声大"。
2. **引擎侧不上重方案**：VS Code（ripgrep 子进程）、Zed（自研并行扫描）、SilverBullet（tantivy）、Telescope（每次重跑 rg）都为"海量文件扫描"设计，与 1000 条×2KiB（~2MB）不成比例。在现有 FTS5 trigram 上补"精确子串校验 + 高亮/片段/计数 + 轻量排序"即可。
3. **笔记内查找直接用 `@codemirror/search`**：增量搜索、大小写/正则/整词开关、命中高亮、"1 of N"计数、next/prev、滚动定位、替换，把默认语义设为"字面子串 + 大小写不敏感"即覆盖需求。

## 各项目实现要点

### VS Code

- 笔记内查找（Ctrl+F）：Monaco 内置 find widget；`TextModel.findMatches` + JS `RegExp`；大小写/整词/正则/选区范围开关；输入即高亮，Enter 下一处、Alt+Enter 全选命中；Ctrl+H 展开替换；`findOnType` 控制"边输入边查"。
- 全局搜索（Ctrl+Shift+F）：由 **ripgrep 原生可执行文件**驱动；`ISearchService` 门面区分 textSearch/fileSearch；扩展可经 `FileSearchProvider`/`TextSearchProvider` 自定义来源；`RipgrepTextSearchEngine` 派生子进程 `rg`。
- 两套正则：工作区用 ripgrep（Rust regex，解析失败回退 PCRE2），已打开文件用 JS regex。
- 结果视图：树形按文件分组、每文件命中数、上下文行（`contextLines` 0/N）、预览内高亮；`files to include/exclude` + 排除规则。
- 增量/取消：ripgrep 进程可取消，结果流式回填。

### Zed

- Buffer search（Ctrl+F）：find bar；大小写/整词/正则开关；next/prev 用 Enter/Shift+Enter/F3/箭头按钮；**smart case**（`use_smartcase_search`），对文件内与项目搜索都生效。
- Project search（Ctrl+Shift+F）：自研（明确不用 ripgrep），但与 ripgrep 共用 Rust `regex` crate；扫描所有文件、遵守设置与 `.gitignore`；优先用内存 buffer 内容，否则回退文件系统。
- 两阶段 + 并行：阶段 1 逐文件判"是否至少一个命中"（并行线程 + channel）；阶段 2 对命中文件 load 成 buffer 再 `find_all_matches`。
- 性能：`select_biased!` 表达任务优先级——优先"确认已命中文件的完整匹配"而非"扫描新文件"，显著降低首命中延迟（Linux 仓库 ~3.8s → ~1.1s）。

### Logseq

- SQLite FTS5 + `trigram`，虚拟表 `blocks_fts`；全文索引/查询全在 DB worker 线程；重建索引也在 worker。
- 匹配：trigram 让子串匹配跨语言成立；`search-blocks` 多路回退：精确标题 → FTS5 → 模糊。
- 代价：trigram 对 <3 字符短查询"makes short queries noisy by design"。

### Joplin

- SQLite FTS4（已演进到 FTS5 探测）；内容实时索引，支持 Simple FTS + Full-Text Index。
- CJK：明确记录 FTS 不支持无拉丁词边界语言（CJK），检测到中文/日文/韩文/泰文时切"自定义非 FTS 搜索模式"（功能相同但更慢、无 BM25、通配符不受限）。
- 查询语法：单词 / 多词 AND / 短语引号 / 前缀；`/` 前缀切 basic search（字面匹配非字母字符）；大量 filter（`title:`/`body:`/`tag:`/`notebook:` 等）。
- 相关性：命中次数越多越靠前；多词看词间距；BM25 仅 FTS 模式。

### SiYuan

- SQLite FTS5 + 自定义分词器 `siyuan` 与 `han_insensitive`；`siyuan-note/sqlite-fts5-siyuan-tokenizer` 是"SQLite FTS5 中文单字分词器"（unigram，每个汉字一个 token）。
- 通过单字分词解决中文无词边界；`han_insensitive` 处理大小写不敏感；FTS5 只做召回，结构化过滤走原生 block 查询。

### Obsidian

- 核心 Search 是 core plugin，**不维护持久化倒排/全文索引**，按需读取（内存缓存的）内容逐字匹配；社区插件 `obsidian-indexed-search` 靠 IndexedDB trigram 索引喂候选集提速，反证核心本身是扫描。
- 匹配：大小写不敏感子串；`/regex/` 正则；`path:`/`tag:`/`line:`/`block:` 算子；`match-case:`/`ignore-case:` 局部覆盖。
- 结果：实时（输入即更新）+ 上下文片段 + 高亮，按文件分组。

### Trilium

- 未查到使用 SQLite FTS/倒排索引；"逐条匹配"渐进式：先精确匹配（覆盖 90%+），结果不足 5 条自动加模糊匹配（编辑距离）。
- 归一化：大小写、变音符不敏感；属性表达式（`#label`、`~relation`）；**精确匹配永远排在模糊前**。

### SilverBullet

- 全文搜索用 **tantivy**（Rust，类 Lucene）；ADR-013：服务端每空间维护索引、页面变更即更新；可选 embedding 语义搜索。

### QOwnNotes

- SQLite `LIKE '%word%'` 子串匹配；`notes.sqlite` + `:memory:` 共享缓存 SQLite 做高速索引；笔记内 Ctrl+F 支持大小写/整词/正则；作者对 SQLite 侧做复杂匹配（词边界）明确克制。

### CherryTree / Standard Notes / Helix / Kate

- CherryTree：XML 存储，多范围查找替换（当前/选中/选中+子节点/全部节点），扫描式。
- Standard Notes：端到端加密，服务端只存密文，搜索完全在客户端解密后内容上；笔记内 Ctrl/Cmd+F 结果导航、大小写、highlight-all、替换。
- Helix：全局搜索 picker 用正则（`grep_searcher` Rust crate）；PR #5652 让全局搜索搜内存 buffer 而非重读磁盘；编辑器内 `/` 查找 + `n/N` 跳转、smart-case、增量。
- Kate/KWrite：增量搜索、多行搜索替换、正则、跨多文件搜索替换；Search-in-Files 插件按"打开文件/文件夹+子目录/当前文件"范围。

### 引擎/CLI 参照

- ripgrep：Rust `regex`（leftmost-first，无反向引用/环视），`-P` 回退 PCRE2；mmap + 并行遍历；行导向；smart-case；二进制跳过 + 字面量优化；`grep-searcher` crate 提供 mmap/SIMD/二进制检测。
- fzf：FuzzyMatchV1/V2 + ExactMatchNaive（子串 + 大小写 + 加分：词边界/camelCase/连续块/首字符）；`--filter`+`--no-sort` 可当模糊 grep。
- Telescope.nvim：`live_grep` 每次输入重跑一次 `rg` 子进程；finders + sorter 解耦。

## 对本项目的可借鉴点（要点，已并入设计）

- UI 侧多参考：快捷键分层全球一致（Ctrl+F 笔记内 / Ctrl+Shift+F 全局 / Esc 关 / Enter 打开/下一处 / F3 上一处）；结果面板"输入即搜 + 命中数 + 高亮片段 + 上下键选择 + Enter 打开 + Esc 关"；`<mark>` 高亮；开关最小集 v1 仅"大小写"。
- 引擎侧按需裁剪：不引入 ripgrep/tantivy/自研倒排；不为中文上词典分词；保持 FTS5 trigram 候选 + Rust 精确子串校验，补排序/高亮/片段。
- 排序：推荐"命中次数 + 标题加权 + 时间兜底"，不推荐 bm25（trigram 下对短词/CJK 偏噪）。
- 高亮/片段：Rust 自算 offset 生成 segments，不用 FTS5 `highlight()`/`snippet()`（trigram 下错位、且 <3 字符 LIKE 路径无 FTS 行）。

## 未查到/不确定项（如实标注）

- Obsidian 核心 Search 的确切索引结构：官方未公开"是否在内存维护内容缓存索引"；"无持久化倒排索引、按需读取"是据 `obsidian-indexed-search` 插件设计意图**推断**。
- Logseq 最终排序公式：仅查到"多路回退 + FTS/标题分级"，未查到统一 BM25/时间加权公式细节。
- Trilium 是否使用任何索引：文档只描述"逐条匹配 + 归一化 + 编辑距离"，未明确"无索引"。
- SiYuan `siyuan` vs `han_insensitive` 精确分工：确认 `siyuan` 仓库是单字分词器，`han_insensitive` 具体职责未逐字核实。

## 参考来源（URL）

- VS Code：[Search Issues](https://github.com/microsoft/vscode/wiki/Search-Issues)、[Search Service and Engines](https://deepwiki.com/microsoft/vscode/11-workbench-ui-architecture)、[FileSearchProvider #226668](https://github.com/microsoft/vscode/issues/226668)、[Find as you type](https://neutrondev.com/vs-code-find-as-you-type/)
- Zed：[Project Search 官方博客](https://zed.dev/blog/nerd-sniped-project-search)、[buffer_search.rs](https://github.com/zed-industries/zed/blob/e4937eb0/crates/search/src/buffer_search.rs)、[use_smartcase_search](https://zed.rust-lang.net.cn/docs/reference/all-settings)
- CodeMirror 6：[@codemirror/search](https://codemirror.net/docs/ref/#search)、[highlightSelectionMatches](https://npmx.dev/package-docs/@codemirror/search/v/6.7.2)、[自绘面板 discuss](https://discuss.codemirror.net/t/replace-default-search-panel-with-my-own-component/5885)
- Logseq：[Search and Command Palette](https://deepwiki.com/logseq/logseq/9.1-search-and-command-palette)、[搜索实现](https://opendeep.wiki/logseq/logseq/editor-core.search)
- Joplin：[Searching 官方文档](https://joplinapp.org/help/apps/search/)、[1.1 搜索引擎更新](https://github.com/laurent22/joplin/blob/144ed593ccfd3ed4bb92e4e22046bb486cffc5b9/readme/news/20200913-163730.md)
- SiYuan：[Full-Text Search](https://deepwiki.com/siyuan-note/siyuan/9.1-full-text-search)、[中文单字分词器](https://github.com/siyuan-note/sqlite-fts5-siyuan-tokenizer)
- Obsidian：[indexed-search 插件](https://github.com/mnaoumov/obsidian-indexed-search)、[0.8.0 changelog](https://obsidian.md/changelog/2020-07-14-desktop-v0.8.0/)
- Trilium：[Under the hood](https://docs.triliumnotes.org/user-guide/concepts/navigation/search/under-the-hood)、[matching](https://docs.triliumnotes.org/user-guide/concepts/navigation/search/matching)
- SilverBullet：[ADR-013 Server-Side Search](https://github.com/silverbulletmd/silverbullet/blob/b9a7e6126fa7466f4fa5cb6c356d7430a7efe025/docs/ADR/013%20Server-Side%20Search.md)
- QOwnNotes：[SQLite LIKE 搜索解析](https://blog.gitcode.com/6263d096445b7740c4fc3dfb829a6230.html)、[词边界 issue #3191](https://github.com/pbek/QOwnNotes/issues/3191)
- Helix：[pickers](https://raw.githubusercontent.com/sarvex/helix/refs/heads/main/book/src/pickers.md)、[#5652 全局搜索搜 buffer](https://github.com/helix-editor/helix/issues/5597)
- Kate：[Search-in-Files 插件](https://docs.kde.org/stable_kf6/en/kate/kate-application-plugin-searchinfiles.html)
- ripgrep：[高级搜索优化](https://ripgrep.org/ripgrep-advanced-search-optimization-for-modern-development-environments/)
- fzf：[algo.go](https://github.com/junegunn/fzf/blob/master/src/algo/algo.go)、[manpage](https://manpages.ubuntu.com/manpages/resolute/man1/fzf.1.html)
- Telescope.nvim：[README](https://github.com/nvim-telescope/telescope.nvim)
- Zettlr：[全局搜索](https://docs.zettlr.com/en/file-manager/search/)
