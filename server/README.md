# Go 服务端

已实现 v1 HTTP API、SQLite 权威存储、邀请码注册登录、设备管理、幂等 Push、固定上界 Pull、SSE、完整 ZIP 导出和本机管理命令。行为以 [spec](../spec/README.md) 为准；同步、鉴权与迁移仍需独立交叉评审及三端联调后用于真实数据。

## 构建与本地运行

在 `server/` 中使用 Go 1.27.3：

```sh
go build -trimpath -o ../dist/memo-server-linux-amd64 ./cmd/memo-server
go vet ./...
go test -race ./...
golangci-lint run
python3 scripts/smoke.py ../dist/memo-server-linux-amd64
```

本地开发显式开启 HTTP，数据库和 epoch 分开存放，默认不开放跨域：

```sh
../dist/memo-server-linux-amd64 invite-create --db /tmp/djhx-dev/db/memo.db --epoch-file /tmp/djhx-dev/runtime/epoch
../dist/memo-server-linux-amd64 serve --dev-http --listen 127.0.0.1:8080 --db /tmp/djhx-dev/db/memo.db --epoch-file /tmp/djhx-dev/runtime/epoch
```

首条命令只在终端显示一次邀请码；不要把输出加入版本库、日志或截图。客户端服务器根 URL 为 `http://127.0.0.1:8080`，模拟器使用宿主机映射或 `adb reverse tcp:8080 tcp:8080`；注册接口见 [OpenAPI](../spec/openapi.yaml)。生产必须使用 HTTPS 反向代理，去掉 `--dev-http`。

## 管理入口

所有命令均显式使用同一 `--db`、`--epoch-file`（默认分别为 `./data/memo.db`、`./runtime/epoch`），从部署账号执行：

| 命令 | 额外参数 | 行为 |
|---|---|---|
| `serve` | `--listen`、`--trusted-proxies`、`--dev-http` | 生产监听仅允许回环；只信任配置 CIDR 的代理转发头 |
| `invite-create` | `--ttl 168h` | 32 字节随机邀请码，一次性，默认 7 天过期 |
| `invite-revoke` | `--id UUID` | 以管理 ID 吊销邀请码 |
| `password-reset` | `--username NAME` | `/dev/tty` 隐藏输入并二次确认，原子吊销所有设备 |
| `backup` | `--output NEW_FILE` | 在线 `VACUUM INTO` 一致快照，不覆盖已有文件 |
| `check` | 无 | SQLite integrity_check |
| `epoch-rotate` | 无 | 持有实例锁后原子轮换独立 epoch，运行中拒绝 |

`serve` 可使用 `--registration-disabled` 停止注册；`--login-failures` 默认 5（IP 与账号各 15 分钟窗口）、`--register-attempts` 默认每 IP 每小时 5 次。限流在单进程内存保存，重启重新计数；反向代理同时限制连接。Argon2 请求并发上限 4，超额返回 503 和 Retry-After。HTTP 不输出请求正文或认证字段；CLI 失败只输出安全错误类别和路径，不能输出密码或正文。

`version` 输出版本与构建提交；可通过构建 `-ldflags "-X main.version=0.1.0 -X main.commit=GIT_SHA"` 注入发布信息。设备 last_seen_at 最多每分钟落盘一次。

单实例锁位于 `<epoch-file>.lock`。运行时不要手动替换数据库或 epoch；已有数据库缺失 epoch 拒绝启动。迁移版本与 SHA-256 记录在数据库，发现高版本/校验错误时拒绝写入。

[部署与恢复](../docs/operations.md) · [实际检查记录](../docs/server-validation.md) · [依赖 ADR](../docs/adr/0006-server-dependencies.md)
