# core 协作规范

遵循 [全局规范](../AGENTS.md)，全部禁止事项适用于本目录。

- 依 [同步协议](../spec/sync-protocol.md) 保存不可变待发快照、draft_base_rev 和更高本地代次；不得仅以 dirty 标记确认同步。
- UI 事件、网络操作和数据库事务有明确线程边界；兼容 Android/Windows/Linux，不依赖专有路径。
- Pull 更新 shadow 不得改变未同步编辑基准；确认旧代次不能清除新输入。
- 公开接口写 Rust 文档，crate 初始化时开启 missing_docs 检查。
- 工程初始化后本地运行格式、clippy、有效测试和 [一致性场景](../spec/conformance/scenarios.md)，无 CI 前提；同步和迁移变更需交叉评审。
