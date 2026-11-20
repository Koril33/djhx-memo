# 搜索性能基准

可重复执行的搜索延迟基准。用维护者 blog 语料（`C:\Project\blog`，180 篇 Markdown）构造隔离内存 SQLite（镜像 `notes` + `notes_fts` trigram），跑与 `core::db::search_notes` 等价的候选检索 SQL（≥3 字符走 FTS trigram、<3 字符全表 LIKE），统计 P50/P95/P99。

只测 SQL 召回这一主导开销；打分与 segments 生成作用于已命中的正文，在该规模下可忽略。实现后按 [搜索设计 §8](../../docs/search-design.md) 复测端到端。

## 运行

```powershell
cargo run --locked --manifest-path tools/search-bench/Cargo.toml --release -- <blog目录>
# 默认 C:\Project\blog
```

输出：语料规模 + 每个 query 的路径（FTS/LIKE）、命中数、P50/P95/P99（毫秒）。

基线（2026-11-20，180 篇 / 1.56 MiB，内存 SQLite）：最坏情况（2 字中文全表 LIKE）P99 ≈ 1.6ms，比 200ms 目标低两个数量级。
