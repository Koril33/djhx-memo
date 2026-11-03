# ADR-0002：M0 原生绑定验证依赖

- 状态：用于技术验证
- 依据：[v1 基线](0001-v1-baseline.md)

验证程序使用 rusqlite 0.41.1（MIT，rusqlite 项目维护）及其 bundled 功能（SQLite 公有领域），验证目标设备无需依赖系统 SQLite。使用 UniFFI 0.32.2（MPL-2.0，Mozilla 维护）从 Rust 导出 Kotlin 绑定，验证跨语言调用、错误和事件回调。

- [rusqlite 上游](https://github.com/rusqlite/rusqlite)
- [UniFFI 上游](https://github.com/mozilla/uniffi-rs)
- [JNA 上游](https://github.com/java-native-access/jna)

Kotlin 主机验证使用 JNA 5.17.0 加载共享库（Apache-2.0 或 LGPL-2.1-or-later，由 JNA 项目维护）。Rust 验证工程提交依赖锁文件。探针数据库仅用于测试，禁止传入实际笔记数据库。
