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

独立交叉评审见 [server-review](reviews/server-review.md)，core 的真实多库联调见 [core-review](reviews/core-review.md)。本机 Debian 13 VM 的 systemd、Nginx/TLS 与备份恢复结果见下文；2026-11-07 的 Debian 12 云主机、公网 TLS 与同步检查另见 [云部署记录](cloud-deployment.md)。本记录不代表完整三端设备矩阵或 7 天自用已通过。

## 2026-11-04 重启后复验与 Debian 13 VM

开发机重启后重新确认 Go 1.27.3，模块缓存 `/home/koril/go/pkg/mod`、构建缓存 `/home/koril/.cache/go-build` 均可用；二进制改放持久目录 `.local/server/memo-server`。再次运行 vet、golangci-lint（0 issues）、`go test -race -cover ./...`（通过，库覆盖率 74.8%）、CLI smoke，全部通过。独立 core/服务端联调另见 [core-review](reviews/core-review.md)。

独立交叉评审发现原设备吊销路径在 handler 认证与实际更新之间允许同设备重新登录，旧 actor 可误吊销替换后的新 token。现 `Store.revoke` 在单个事务中重新校验 actor 的 user/device/token_hash、有效期与吊销状态后更新；新回归验证旧 identity 无法吊销自己新会话或另一个设备，当前 identity 正常及重复吊销仍成功。Push 原有同事务复验、login 的密码哈希重读、register 邀请消费事务也复核。

在本机 QEMU/KVM **Debian 13.7 amd64** 虚拟机实测以下行为（该系统不是目标 Debian 12，不能据此宣称 Debian 12 已验收）：

- `CGO_ENABLED=0 GOOS=linux GOARCH=amd64` 构建二进制，原样安装 `server/deploy/djhx-memo.service`；`systemd-analyze verify` 通过，启用并启动后 ActiveState=active、SubState=running、NRestarts=0。
- 专用 `djhx-memo` 系统账号，StateDirectory 权限 0700，数据库及独立 epoch 权限 0600；服务仅监听回环 8080。
- 通过本机可信代理头 `X-Forwarded-Proto: https` 验证生产传输入口、邀请码注册、Push/Pull。此步骤仅验证应用的代理信任规则，不等同实际 TLS 证书或公网 HTTPS 验收。
- `systemctl restart` 后 epoch、账号及 token 稳定；在线 `VACUUM INTO` 快照可通过 integrity_check。
- 停止服务、整体保全原 db/WAL/SHM、恢复快照、轮换独立 epoch，再启动：旧 Push 得到 SYNC_EPOCH_MISMATCH，新 Pull 得到备份正文，备份后版本未混入。
- guest OS 完整 reboot 后启用的服务自动恢复 active；重新登录并 Pull 验证持久 epoch、账号、笔记与 head_seq 不变。
- 检查该服务 journal 不含测试正文、密码、邀请码和 token。临时验证记录不含密码或 token；VM 部署凭据没有进入仓库。

截至本节 2026-11-04 演练时，云端公网证书、异地加密上传、RPO/RTO 及 Debian 12 仍未验证；后续云部署见单独记录。VM 的测试账号及示例服务为本机验证用途。

### Debian VM 的真实 HTTPS 与 SSE 代理

在同一 Debian 13 VM 安装 Nginx `1.26.3-3+deb13u10`；将仓库 Nginx 样例仅替换域名和证书路径后 `nginx -t` 与 reload 通过。生成有效期 2 天、含 DNS/IP SAN 的临时验证证书；Python SSL 客户端显式信任该验证证书并执行主机名/IP 校验，未使用跳过证书验证。

真实 TLS 路径通过：HTTPS 注册及 Push；SSE 建连立即信号、实际提交信号、15 秒心跳；logout 后流 EOF；HTTP Host 命中样例返回 301 HTTPS 重定向。该私有 VM 验证不代表公网证书签发或云部署完成。最终替换为包含 `ab1886c` 吊销修复的纯 Go 二进制后保持原数据库/epoch，重新验证登录、Pull、TLS 与 SSE。

独立评审另发现纯 Markdown 标题标记（例如 `# `）派生标题为空时，Go 导出与 core 的“无标题”不一致，已修正并加入函数边界与真实 ZIP manifest 断言。此后再次执行 vet、lint（0 issues）、race/覆盖率测试均通过，服务库覆盖率 75.2%。
