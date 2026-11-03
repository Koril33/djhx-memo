# server

Go 单实例服务端，提供邀请注册/登录、设备管理、权威笔记、逐项事务 Push、不可变日志 Pull、SSE 和云端救援导出。使用 SQLite WAL，部署到 Debian 12。

当前无 Go 工程或运行命令。实施时 module 为 `git.djhx.site/koril/djhx-memo/server`；SQLite 驱动及依赖版本在技术验证时记录 ADR、许可证和锁文件。

先阅读 [认证](../spec/auth.md)、[同步协议](../spec/sync-protocol.md)、[OpenAPI](../spec/openapi.yaml)、[模型](../spec/data-model.md) 和 [运维](../docs/operations.md)。不建设 CI/Runner。
