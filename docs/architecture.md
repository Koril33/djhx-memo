# v1 架构

## 模块与数据流

```text
Android Compose ── UniFFI ──┐
                           ├── Rust core ── HTTPS JSON / SSE ── Go server
Windows/Linux Tauri ────────┘       │                              │
       │                       本地 SQLite                  权威 SQLite WAL
  Svelte / CodeMirror
```

UI 展示与平台适配留在各客户端；本地写入、搜索、outbox 和客户端同步状态机只在 core 实现。服务端校验权限、分配版本/序号、裁决冲突并保留不可变变化日志。共同规则由 spec 定义、跨语言一致性场景验证，不声称全部服务端业务也在 Rust 中执行。

本机空间没有服务端依赖。账号空间的本地 SQLite 是读写入口，服务器是同步后的权威副本。凭据服务通过平台适配接口提供，笔记正文不交给 WebView 直接访问数据库。

## 目录边界

- `core/`：Rust 库、迁移、协议客户端、同步协调器、UniFFI 接口和测试。
- `server/`：Go 服务、管理命令、迁移、HTTP/SSE 和授权测试。
- `android/`：Compose UI、生命周期、WorkManager 与安全凭据适配。
- `desktop/`：Tauri Rust 包装及本地 Svelte/Vite/TypeScript 前端，托盘与文件集成。
- `web/`、`apple/`：暂停，仅保留说明，不创建实际工程。
- `spec/`：唯一行为契约；`docs/`：实现理由和验证/运维指南。

v1 不拆共享前端包，不提前建设浏览器存储或 WASM。将来增加独立 Web 时，再从 desktop 抽取稳定组件，先记录 ADR。

## 持久性与并发

SQLite 写入、索引和同步状态有明确事务边界；本地每空间一个协调器，同笔记最多一个冻结待确认操作。桌面使用单实例策略。Android 前后台协调器复用或通过锁串行访问，同一数据库不能由两个引擎互相覆盖。

网络不在数据库写事务中长时间等待。SSE 更新信号在服务端提交后发出，丢失由 Pull 补偿。服务端 v1 单实例运行，数据库位于持久本地磁盘，不引入多副本写入或共享网络文件系统。

schema 版本、API 版本和恢复 epoch 各司其职，不能用一个数字兼任。具体行为见 [数据模型](../spec/data-model.md) 与 [同步协议](../spec/sync-protocol.md)。
