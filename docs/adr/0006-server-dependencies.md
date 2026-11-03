# ADR-0006：Go 服务端存储与密码依赖

- 状态：已接受实现方案；实现仍须独立交叉评审
- 日期：2026-11-03
- 影响：server、服务端部署及迁移

## 决策

Go module 使用 `git.djhx.site/koril/djhx-memo/server`，Go 工具链 1.27.3，锁定以下直接依赖并提交 `go.sum`：

| 包 | 版本 | 许可证及官方来源 | 理由与维护情况 |
|---|---|---|---|
| modernc.org/sqlite | v1.61.1 | BSD-3-Clause；[官方文档](https://pkg.go.dev/modernc.org/sqlite@v1.61.1)、[官方源仓库](https://gitlab.com/cznic/sqlite)；随包 SQLite 为公共领域 | 纯 Go SQLite 驱动，不要求部署机安装 C 编译器；提供完整 SQLite 事务、WAL 和 VACUUM INTO 一致备份。项目持续发布，传递依赖随 go.sum 锁定。 |
| golang.org/x/crypto/argon2 | v0.58.0 | BSD-3-Clause；[Go 官方扩展库](https://pkg.go.dev/golang.org/x/crypto@v0.58.0/argon2) | 使用 Go 团队维护的 Argon2id，实现 auth 规范的最低密码参数及独立随机盐。 |

HTTP、JSON、随机 UUID v4、token、SHA-256、ZIP、CLI、限流、SSE、配置、日志均使用 Go 标准库，不引入 Web 框架、JWT 或迁移框架。迁移文件嵌入二进制，记录 SHA-256 校验、高版本保护，单连接序列化写入，WAL + synchronous=FULL。

直接依赖固定版本，实际解析的传递依赖写入 go.mod / go.sum；升级需运行迁移、并发及恢复测试，不能只更新锁文件。分发包含直接和传递依赖的许可证说明，依赖漏洞检查结果单独记录于验证材料。

## 备选与影响

CGO SQLite 驱动需部署构建工具链，当前纯 Go 单实例服务更易在 Debian 部署。自行实现密码算法风险较高，采用官方扩展库。单个数据库连接适合个人和少量朋友，避免多个连接的 PRAGMA 配置不一致；真实性能在部署目标测量，扩展连接池需另行验证。
