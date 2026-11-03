# server

Go 服务端，负责账号和设备认证、权威笔记副本、变更序号分配、push/pull、更新通知与导出；规划使用 SQLite WAL，部署到 Debian 12。

后续基于正式 spec 初始化 `go.mod`、服务入口、迁移和测试。Go module 路径应结合实际托管域名 `git.djhx.site` 确认，不直接采用规划中的 GitHub 示例地址。

当前没有服务实现或运行命令。参见 [项目规划](../djhx-memo-project-plan.md) 与 [规范目录](../spec/README.md)。
