# v1 部署、备份与发布

状态：2026-11-07 已将 0.1.0 候选服务部署到 `https://memo.djhx.site`，完成公网 TLS、同步与 SSE 烟测。云部署证据见 [部署记录](cloud-deployment.md)，本机异地加密备份见 [说明](backup-local.md)；关机期间的异地 RPO、真实云主机故障恢复及完整 v1 验收不因部署成功而通过。原有隔离备份/恢复检查见 [server-validation](server-validation.md)。

## 1. 单实例部署

服务端部署于 Debian 12，自有服务器单实例 Go 服务，由 systemd 管理；反向代理提供 HTTPS。数据库放持久本地磁盘，服务账号最小权限，运行参数与数据分离。运行配置包含监听地址、数据库路径、独立 epoch 元数据路径、注册开关/邀请策略、限流及可信代理。当前日志仅记录启动地址及安全错误，不提供请求正文/认证字段日志，也没有动态日志级别。

备份运行元数据与 SQLite 分开管理，恢复数据库时按下文轮换 epoch。token、密码、邀请码不得进入版本库、进程参数日志或访问日志。

反向代理限制请求体为 8MiB（认证请求可更小），配置 SSE 不缓冲、允许长连接和大于 15 秒的空闲时限。/healthz 检测进程和数据库基本可用性，不返回配置或凭据。

提供本机管理入口生成/吊销邀请码、重置密码并吊销会话、停服轮换恢复 epoch、创建一致备份。管理命令只对服务器操作者开放，不暴露公开管理 API。

## 2. 备份目标

v1 自用上线前必须具备异地备份并完成一次恢复演练：目标 RPO ≤24 小时、RTO ≤2 小时。RPO 是服务器故障可能损失的最近云数据窗口，不是对全部未同步输入的保证。

默认每天用 SQLite 一致备份机制生成快照，加密复制到独立故障域，保留最近 7 个日备份和 4 个周备份；升级前额外备份。当前按项目负责人选择，由本机定时拉取云端公钥加密快照，安装和恢复命令见 [本机备份](backup-local.md)。本机关机或长期离线时没有新增异地副本，恢复运行后仅能补拉云端仍保留的快照，不能保证该期间的异地 RPO ≤24 小时。不得在 WAL 写入期间仅复制主 .db 文件。备份密码、恢复密钥与可读取的备份文件分开保存。

备份任务失败应通知操作者，检查最近成功时间及恢复可读性。应用依赖本地和云端多副本并不代替异地备份。Litestream 连续备份后置，满足实际需要时另立 ADR；当前未配置第三方存储服务。

## 3. 恢复流程

1. 停止对外写入和服务进程，保全现有数据库、WAL 及诊断材料。
2. 在隔离位置还原一致备份，验证完整性、schema 版本、账号数据和样本笔记。
3. 通过管理入口生成新 sync_epoch，写入独立运行元数据；不能复用备份里的旧代次，也不能让旧服务继续写入。
4. 启动服务，验证健康、登录、Push/Pull 和 SSE；明确告知恢复范围与备份时间。
5. 用带旧游标和待发内容的测试客户端验证 epoch 拒绝旧推送、保全本地恢复空间及重新拉取。
6. 按选择将恢复空间内容以新 ID 导入，不直接重新播放旧 outbox。记录实际数据损失窗口和恢复耗时。

每次版本迁移前备份；恢复演练至少首次上线前和重要存储/迁移变更后执行。季度复核备份可恢复性。数据库损坏、误删服务器和误恢复都需有对应演练记录。所有备份和客户端都不存在的内容无法保证恢复，产品不作“任何情况下永不丢数据”的绝对承诺。

## 4. 发布与回退

交付 Windows 安装包、Linux .deb、Android APK 和服务器二进制，附版本、Git SHA、SHA-256 校验、平台依赖和变更说明。v1 手动安装/升级；Android 始终使用同一发布签名，签名密钥离线保管，不入仓库。Windows 无代码签名时如实记录系统提示和发布来源。

Linux 安装说明列出 WebView/AppIndicator 依赖及 GNOME 扩展要求；窗口无法隐藏/恢复属于发布阻断问题。Windows 验证 WebView2 缺失时安装流程。真实设备之外的发行版或架构不标记为已验证。

服务端升级先备份，停止写入后迁移，保留旧二进制。只在 schema 向后兼容时回退二进制；还原旧数据库按恢复流程轮换 epoch。客户端遇到高版本 schema 禁止自动降级。发布前检查 [validation](validation.md) 并完成 7 天自用。

## 5. 当前实现的部署命令

Go 服务端已提供真实 CLI，工程入口与检查见 [server](../server/README.md) 及 [server-validation](server-validation.md)。下列是首次安装步骤，已有实例升级必须先按第 4 节保全数据。当前云主机为 Debian 12 amd64，Nginx 配置已对应 `memo.djhx.site`，使用现有 `/etc/letsencrypt/live/djhx-wildcard/` 通配符证书；其他实例须自行替换域名与证书路径。

```sh
# 在开发机 server/ 中构建，不要求云端安装 Go 或 C 编译器。
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -o ../dist/memo-server-linux-amd64 ./cmd/memo-server
```

将二进制、许可证 [THIRD_PARTY_NOTICES](../server/THIRD_PARTY_NOTICES.txt)、[systemd 单元](../server/deploy/djhx-memo.service) 和 [Nginx 样例](../server/deploy/nginx.conf) 复制到服务器。云主机若是 ARM64，将构建的 `GOARCH` 改为 `arm64` 并在该架构验证。

```sh
sudo useradd --system --home /var/lib/djhx-memo --shell /usr/sbin/nologin djhx-memo
sudo install -m 0755 memo-server-linux-amd64 /usr/local/bin/memo-server
sudo install -d -o djhx-memo -g djhx-memo -m 0700 /var/lib/djhx-memo/db /var/lib/djhx-memo/runtime /var/lib/djhx-memo/backups
sudo install -m 0644 djhx-memo.service /etc/systemd/system/djhx-memo.service
sudo systemctl daemon-reload
sudo systemctl enable --now djhx-memo
curl --fail http://127.0.0.1:8080/healthz
sudo install -m 0644 nginx.conf /etc/nginx/conf.d/zz-djhx-memo.conf
sudo nginx -t
sudo systemctl reload nginx
# reload 异步切换 worker；等待此地址返回 200 后再确认部署成功。
curl --fail https://memo.djhx.site/healthz
sudo -u djhx-memo /usr/local/bin/memo-server invite-create --db /var/lib/djhx-memo/db/memo.db --epoch-file /var/lib/djhx-memo/runtime/epoch
```

配置 HTTPS 后通过外网根 URL 验证 healthz、注册、Push/Pull 与 SSE。部署前保全原配置，验证既有站点仍可用；以 `zz-` 文件名安装是为了保留现有 443 默认主机。生产应用仅允许来自可信代理的 `X-Forwarded-Proto: https`；样例仅信任本机代理，并由 Nginx 覆盖 `X-Forwarded-For`，不转发不可信链。不要把服务端口开放到公网。客户端 URL 使用 HTTPS 根路径。邀请码只显示一次，妥善传给受邀者，不能写入日志或仓库。

密码重置不接收密码参数或环境变量，要求真实终端隐藏输入，二次确认后吊销所有设备：

```sh
sudo -u djhx-memo /usr/local/bin/memo-server password-reset --db /var/lib/djhx-memo/db/memo.db --epoch-file /var/lib/djhx-memo/runtime/epoch --username USERNAME
```

服务进程一秒检查一次 SSE 凭据，因此管理命令重置后已有流也会断开；通过 API 吊销的连接立即通知关闭。

## 6. 一致快照与恢复命令

在线备份创建新文件，不覆盖现有快照，SQLite `VACUUM INTO` 保证单一一致读取视图。CLI 输出为明文 SQLite，权限 0600；自动任务在私有目录创建临时快照，校验后使用本机公钥加密，再由本机拉取。自动任务配置及私钥保管见 [本机备份](backup-local.md)，下列命令是原始管理入口。

```sh
sudo -u djhx-memo /usr/local/bin/memo-server backup --db /var/lib/djhx-memo/db/memo.db --epoch-file /var/lib/djhx-memo/runtime/epoch --output /var/lib/djhx-memo/backups/UNIQUE-TIMESTAMP.db
sudo -u djhx-memo /usr/local/bin/memo-server check --db /var/lib/djhx-memo/backups/UNIQUE-TIMESTAMP.db --epoch-file /var/lib/djhx-memo/runtime/epoch
```

每日调度、7 日/4 周保留、加密异地副本与失败状态应检查实际任务结果；本机关机时不能接收桌面通知或拉取快照。季度演练及持续复核仍由操作者执行。不能把云主机同盘 snapshot 当作已经完成异地备份。

恢复顺序（`RESTORED.db` 是已验证完整性的独立快照，不能在服务仍运行时覆盖）：

```sh
sudo systemctl stop djhx-memo
# 先把当前 db/（包含可能存在的 -wal、-shm）整体移到独立保全目录。
# 再将 RESTORED.db 安装为以下路径，所有者 djhx-memo、权限 0600。
sudo -u djhx-memo /usr/local/bin/memo-server check --db /var/lib/djhx-memo/db/memo.db --epoch-file /var/lib/djhx-memo/runtime/epoch
sudo -u djhx-memo /usr/local/bin/memo-server epoch-rotate --epoch-file /var/lib/djhx-memo/runtime/epoch
sudo systemctl start djhx-memo
curl --fail http://127.0.0.1:8080/healthz
```

运行中轮换会因实例锁失败。正常重启保持 epoch；旧 DB 但缺失独立 epoch 会拒绝启动。恢复时永远生成新 epoch，不复制备份中的 runtime 目录。旧客户端 Push/Pull 收到 SYNC_EPOCH_MISMATCH 后必须保全本地；客户端恢复流程由 core 与三端联调验收。

本机自动测试覆盖快照可打开、幂等记录恢复、高版本及校验保护、丢失 epoch 保护；目标 Debian 12 的 systemd、Nginx、公网证书与同步烟测见 [云部署记录](cloud-deployment.md)。隔离恢复演练不等同真实云主机故障恢复或持续满足 RPO/RTO，检查边界必须分别记录。
