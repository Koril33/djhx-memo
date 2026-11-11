# Go 服务端

当前版本为 **0.1.4**，新增单条彻底删除、Windows 托盘左右键及桌面列表折叠。账号同步要求协议 2 服务端与相应客户端配套升级；既有 0.1.3 Release 不改变。三端实际测试、交叉评审、安装包和发布状态见[0.1.4 发布验证](../docs/releases/0.1.4-validation.md)。

已实现 v1 HTTP API、SQLite 权威存储、邀请码注册登录、设备管理、幂等 Push、固定上界 Pull、SSE、完整 ZIP 导出和本机管理命令。行为以 [spec](../spec/README.md) 为准；已有 [独立交叉评审](../docs/reviews/server-review.md) 及 core 联调，完整三端验收仍待完成。候选实例 `https://memo.djhx.site` 的实际部署检查见 [云部署记录](../docs/cloud-deployment.md)。

## 构建与本地运行

服务端当前以 Linux 为目标：`epoch.go` 使用 Unix `syscall.Flock`，不能直接在原生 Windows Go 环境编译。Windows 开发者使用 WSL2/Linux 虚拟机并按[Linux 公共入口](../docs/dev-setup-linux-portable.md)操作；不要将 Windows 桌面客户端能原生编译等同于服务端也已移植。

在 `server/` 中使用 Go 1.27.3：

```sh
go build -trimpath -o ../dist/memo-server-linux-amd64 ./cmd/memo-server
go vet ./...
go test -race ./...
golangci-lint run
python3 scripts/smoke.py ../dist/memo-server-linux-amd64
```

发布包从仓库根目录执行 `bash scripts/package-server.sh`，输出 `dist/server/djhx-memo-server-0.1.4-linux-amd64.tar.gz`、二进制、部署资料、许可证和 SHA-256 清单。版本与当前提交写入二进制及 `BUILD.txt`；此入口只构建，不连接或升级生产服务。

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

`version` 输出版本与构建提交；可通过构建 `-ldflags "-X main.version=0.1.4 -X main.commit=GIT_SHA"` 注入发布信息。设备 last_seen_at 最多每分钟落盘一次。

单实例锁位于 `<epoch-file>.lock`。运行时不要手动替换数据库或 epoch；已有数据库缺失 epoch 拒绝启动。迁移版本与 SHA-256 记录在数据库，发现高版本/校验错误时拒绝写入。

[部署与恢复](../docs/operations.md) · [实际检查记录](../docs/server-validation.md) · [依赖 ADR](../docs/adr/0006-server-dependencies.md)
