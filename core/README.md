# core

Rust 核心库，规划使用 SQLite（rusqlite）承载本地优先存储、版本化迁移、同步引擎、冲突处理、FTS5 搜索和 Markdown 导出。

- Android 通过 UniFFI 调用；Tauri 后端直接链接。
- 同步与冲突规则只在核心库中实现一次，客户端 UI 保持薄层。
- 后续初始化 `Cargo.toml`（crate 名为 `djhx-memo-core`），并基于正式 spec 实现与测试。

当前仅预留 `src/`，没有可构建的 crate。参见 [项目规划](../djhx-memo-project-plan.md) 与 [规范目录](../spec/README.md)。
