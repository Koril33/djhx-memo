# Go 服务端本机验证

日期：2026-11-03。分支：`codex/v1-implementation`。环境：Linux amd64，Go 1.27.3；golangci-lint 2.15.0（govet、staticcheck、ineffassign、unused、errcheck）。本记录描述实际执行，不代替云部署及三端验收。

## 已运行

在 `server/`：

```sh
gofmt -w .
go vet ./...
golangci-lint run
go test -race -cover ./...
go build -trimpath -o /tmp/djhx-memo-server ./cmd/memo-server
python3 scripts/smoke.py /tmp/djhx-memo-server
govulncheck ./...
```

- 格式化、vet、lint 通过（lint 0 issues）。
- `go test -race -cover ./...` 通过：服务库语句覆盖率 74.9%；CLI Go 单元覆盖率 0%，CLI 通过独立真实进程烟测验证，不能将该 0% 宣称为 CLI 已有 Go 单元覆盖。
- 共享 [fixtures.json](../spec/conformance/fixtures.json) 的 11 个声明式场景由 `TestSharedConformance` 真实创建账号、调用 HTTP Push 并查询权威 SQLite 断言。包含幂等、并发冲突、删除/恢复、过期删除、旧墓碑删除、非法基准、新建墓碑、ID 碰撞、冲突副本继续编辑。
- 故障验证：`after_note` / `before_commit` 注入失败，无 notes / changes / processed_ops / head 部分提交；重试及快照重启恢复结果一致。真实 `PRAGMA max_page_count` 触发 SQLITE_FULL 后实体和序号不变，解除限制后同操作成功。
- 12 个并发写入真实竞争，恰好一个接受、其余生成独立副本，无丢失序号。
- 认证：Argon2id PHC 基线、随机 token 仅哈希保存、一次邀请并发仅一个注册成功、注册提交前失败原子回滚、密码重置吊销、设备归属不可转移、过期拒绝、IP/账号登录限流、注册 IP 限流及 Retry-After。
- 账号隔离：同 note_id/op_id 可分别存在，Pull/ZIP 不串账号，跨账号设备吊销返回 404。
- 分页：固定 until 跨并发追加不变；非法/超前计数拒绝；最大正文以 U+0000 六倍 JSON 转义后仍可单项拉取，多项按实际字节分页，响应不超过 8MiB。
- SSE：首信号、真实提交信号、新连接替换旧连接、吊销断流、心跳路径。
- 请求：外层未知字段及超 8MiB 拒绝；单项未知/缺失/null 字段逐项失败；1MiB 字节边界、CR 拒绝、epoch 不匹配无副作用。
- ZIP：根目录 UUID 文件、逐字正文、manifest、标题、墓碑选项和跨账号隔离。
- 迁移：0→1 升级、失败回滚、已存校验检测、高版本拒绝；WAL、foreign_keys、FULL 持久性检查。
- epoch：重启稳定、现有数据库丢失元数据拒绝、双实例锁拒绝、原子轮换。
- 生产传输：未经明确代理信任的转发头无效；普通 HTTP 拒绝；可信代理 HTTPS 转发可用；healthz 查询迁移元数据。
- `govulncheck` 使用 Go 1.27.3 重新构建 v1.9.0 后完成：调用符号及所导入包发现 0 个受影响漏洞；所需模块中另有 1 个未被本服务导入/调用的漏洞，模块项为 `golang.org/x/crypto/openpgp` 的 GO-2026-5932（服务仅使用 argon2，从未导入 openpgp）。最初机器内工具由 Go 1.26.10 编译，不能解析 Go 1.27 标准库，已重建工具，未把初次失败记录为通过。

`server/scripts/smoke.py` 使用独立临时目录及动态回环端口，捕获敏感响应、不输出邀请码和 token；真实执行 CLI 邀请初始化 → 进程启动 → 注册 → Push/重复 Push → Pull → ZIP → 在线一致备份 → 运行中轮换拒绝 → SIGTERM 正常退出 → 保全旧库 → 恢复旧快照 → 轮换 epoch → 重启 → 旧 Push 拒绝 → 新 Pull 确认备份正文。脚本退出清理临时数据。

## 实际边界

独立交叉评审和 core 的真实多库联调由另一位协作者进行，结果应在合并记录中关联；本文件不提前宣称通过。没有在云 Debian 主机安装 systemd 单元、执行 Nginx 配置检查、安装公网证书或完成异地备份/RPO/RTO。示例为可部署材料，云端部署由项目负责人执行。没有宣称完成 7 天自用、三端安装、真实 Android 设备或 Windows 的验收。
