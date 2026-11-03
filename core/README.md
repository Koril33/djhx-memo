# core

Rust 本地优先核心，负责 SQLite 存储与迁移、持久 outbox、客户端同步状态机、搜索、回收站、导出和空间隔离。Android 经 UniFFI 调用，Tauri Rust 后端直接链接。

当前仅有目录与规范，没有可构建 crate。实施时初始化 `djhx-memo-core`，使用 rusqlite 随包 SQLite；具体版本和锁文件在工具链验证后提交。

先阅读 [产品规范](../spec/product-v1.md)、[同步协议](../spec/sync-protocol.md)、[模型](../spec/data-model.md)、[UI 接口](../spec/core-api.md) 和 [一致性场景](../spec/conformance/scenarios.md)。服务端负责权威版本与冲突裁决，core 负责保存本地修改并落实客户端流程。
