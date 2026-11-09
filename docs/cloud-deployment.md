# memo.djhx.site 候选服务部署记录

日期：2026-11-07；配置分支：`codex/deploy-memo-domain`。这是 0.1.0 自用候选实例的部署检查，不代表正式 v1、完整三端 GUI 验收或连续 7 天试用通过。

## 主机与安装

- SSH：使用部署者配置的账号连接 `memo.djhx.site`；当时已核域名解析与密钥登录，安装系统服务和 Nginx 配置使用远端 sudo。公开记录不复制具体登录账号与公网 IP。
- 系统：Debian GNU/Linux 12，Linux 6.1.0-38-amd64，Nginx 1.22.1。
- 二进制：`0.1.0-candidate`，构建提交 `40491ab908d7edfbb73b9e4d786931ea41ffeb10`；部署前在本机与云端核对 SHA-256 均为 `5f0a9a2319fe7aca7bfcccd0713fac2a9112e13d9f57fd30e2eac70ae4804c7b`。此次不修改业务、鉴权、迁移或同步实现，也不新增 Go/Rust 依赖。
- `/usr/local/bin/memo-server`；许可证安装在 `/usr/local/share/djhx-memo/`；原样安装 [systemd 单元](../server/deploy/djhx-memo.service)，启用开机启动。
- 独立 `djhx-memo` 系统账号；`/var/lib/djhx-memo/`、`db/`、`runtime/` 为 0700，SQLite 与 epoch 为 0600。后端仅监听 `127.0.0.1:8080`；生产未开启 `--dev-http`。
- 配置前保全 `/etc/nginx` 至 `/var/backups/djhx-memo-deploy-20261107T000236Z/nginx-before.tar.gz`，该目录 0700。

## HTTPS 与既有站点

[Nginx 配置](../server/deploy/nginx.conf) 安装到 `/etc/nginx/conf.d/zz-djhx-memo.conf`，保留原 443 默认站点。域名为 `memo.djhx.site`，共享云端其他站点既有的通配符证书路径：

```text
/etc/letsencrypt/live/djhx-wildcard/fullchain.pem
/etc/letsencrypt/live/djhx-wildcard/privkey.pem
```

读取的证书 SAN 为 `*.djhx.site` 和 `djhx.site`，签发方 Let's Encrypt YE2，有效期为 2026-09-28 至 2026-12-27（UTC）。公钥链与主机名校验通过，全程未使用 `-k` 或关闭 TLS 校验。实测 TLS 1.2/1.3 握手成功；TLS 1.0/1.1 探测握手失败（返回 internal error，未据此宣称已独立验证共享默认站点的协议配置）。本轮未接入通配符证书的自动续期系统；续期后必须在 `nginx -t` 成功后 reload。

`nginx -t` 通过后 reload。首次立即健康探测收到 404，安装保护自动撤回新服务/站点并保留数据库；原配置 SHA-256 全部保持一致。再次安装时等待健康探测，第二次探测成功，符合 Nginx reload 异步切换时仍可能命中旧 worker 的现象；未覆盖数据库或轮换 epoch。

现有 `/etc/nginx/nginx.conf`、`sites-available/default` 和 `conf.d/portdata.djhx.site.conf` 在部署前后校验完全一致，`https://portdata.djhx.site/` 前后均为 200。新增站点两种协议均关闭访问日志；代理覆盖 XFF/XFP，仅信任回环代理；SSE 关闭缓冲、缓存与 gzip，读写超时 90 秒。

## 实际检查

1. 上传后先以非特权账号在独立临时数据库执行原有 `server/scripts/smoke.py`：注册、Push/Pull、幂等、ZIP、一致备份、停服恢复、epoch 轮换及旧推送拒绝全部通过；退出清理临时库。此项不使用正式数据库。
2. 正式实例 `/healthz` 通过本机回环、云主机本地 TLS 和开发机公网 TLS 均返回 200 / `{"status":"ok"}`；HTTP 返回 301 到固定 HTTPS 域名。
3. 公网 `/v1/devices`、`/v1/events` 无凭据返回 401 / `AUTH_REQUIRED`。客户端伪造 XFP 为 `http` 后仍由代理覆盖并按 HTTPS 进入鉴权；带 Origin 的健康请求按现有契约返回 400。
4. 使用正式库中独立的合成测试账号 `deploy_check_20261107` 验证公网 TLS 邀请注册、第二设备登录、Push、重复 Push 响应一致、Pull 正文一致与 ZIP 内容核对。测试笔记通过正常 Push 进入墓碑保留；没有物理删除或建立操作者账号。
5. 同一路径 SSE 首信号 53.9ms、实际提交通知 56.8ms、首个心跳 15.05s；logout 后流 EOF，原 token 再访问为 401。两个测试设备 token 均已吊销；测试账号与墓碑依规范保留。时间是单次部署烟测，不作为产品 P95 验收。
6. `systemctl restart djhx-memo` 后健康恢复、epoch 的 SHA-256 保持一致；最终 `ActiveState=active`、`SubState=running`、`NRestarts=0`。已启用服务，未重启承载其他项目的整台云主机，不能把本次服务重启等同云主机重启验收。

给操作者注册用的一次性邀请码单独保存于开发机私有文件，不进入源码、日志、命令参数或安装包；仍按默认 7 天期限与一次使用规则管理。

## 备份与未验收项

按项目负责人选择，已安装 [本机异地加密备份](backup-local.md)：

- 云端 `djhx-memo-backup.timer` 已启用并 active，每日 UTC 03:20（北京时间 11:20）加随机延迟运行；首次 `djhx-memo-backup.service` 的 `Result=success`、`ExecMainStatus=0`。云端实际 OpenSSL 3.0.18 完成一致快照、SQLite 检查及 AES-256-GCM/RSA-OAEP 加密。
- `/var/lib/djhx-memo-export/` 为 `djhx-memo:<备份读取组>`、2750，已生成当日日快照与本周首份快照，均为 0640。云端按名称保留最近 7 个日备份和 4 个周备份；明文临时目录检查无遗留。
- 本机 `djhx-memo-backup-pull.timer` 已启用，每小时运行。真实 systemd user service 通过 SSH/rsync 拉取、GCM 认证解密及新鲜度检查，连续两次手动运行均 `Result=success`、`ExecMainStatus=0`；`pull.status` 为 `ok`。密文目录 0700、文件 0600，云端与本机 SHA-256 一致。本机保留独立历史，不跟随云端删除，需后续管理容量。
- 私钥仅保存在本机 `~/.local/state/djhx-memo-backup/keys/`，未上传服务器。云端与本机公钥证书指纹一致；私钥和目录的创建时间确认为本次新建，没有覆盖既有密钥。操作者仍须另存受保护的离线私钥副本。
- 用实际拉取的加密快照在本机新建隔离目录解密并运行 `memo-server check`，验证幂等记录、设备吊销、墓碑和同步序号。为隔离副本生成新的 epoch 后启动临时服务，登录与 Pull 正常，原云端 epoch 的 Push 返回 `SYNC_EPOCH_MISMATCH`，完成后移除隔离明文。这次数据量下从解密到接口核对为 0.121 秒，仅是本机演练耗时，不代表真实云主机故障 RTO。
- `bash -n`、四个单元在实际安装路径的 `systemd-analyze verify`、`python3 server/scripts/backup-smoke.py` 通过；隔离测试覆盖标签损坏、同大小/mtime 篡改、传输失败、陈旧快照、保留策略及旧副本保护。独立复审补充验证重复生成命令拒绝覆盖旧私钥。

本机当前 `Linger=no`，通常登录后用户管理器才运行；登出、休眠、断网或关机时不能持续获得新增异地副本。恢复后只补拉云端仍保留的快照，不能将此方案标记为无条件达到 RPO ≤24 小时。云端日任务、小时拉取与保留删除尚未经历长期自然运行，后续需按状态和演练持续复核。

完整三端 GUI 互通、物理 arm64 Android、未覆盖桌面输入法/系统组合、7 天自用与实际云主机故障恢复仍见 [验收清单](validation.md)，不得预先通过。部署配置独立评审见 [记录](reviews/deployment-review.md)。
