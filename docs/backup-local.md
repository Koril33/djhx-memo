# 云端加密备份与本机副本

本文描述 `memo.djhx.site` 到本机 `koril` 用户的备份方案。部署实际结果另见 [运维记录](operations.md)；脚本存在不表示定时任务已经安装或 RPO/RTO 已经通过。运维工具选择、确切版本和许可证见 [ADR-0009](adr/0009-backup-tools.md)。

## 数据与密钥

云端 [备份脚本](../server/deploy/backup-cloud.sh) 由 `djhx-memo` 运行，使用现有 `memo-server backup` 的 SQLite 一致快照，再运行 `memo-server check`。临时明文及 SQLite 附属文件仅放 `/var/lib/djhx-memo/backups/.job.*`，目录 0700；完成后删除。普通退出、失败及 SIGINT/SIGTERM 会清理临时目录，突然断电或 SIGKILL 无法保证执行清理，应在确认没有备份任务运行后人工检查遗留 `.job.*`。文件删除不代表 SSD 上已被安全擦除。

快照通过 OpenSSL CMS、AES-256-GCM 加密，RSA-3072 接收者公钥使用 OAEP/SHA-256 封装随机内容密钥。云端只保存 `/etc/djhx-memo-backup/recipient.pem` 公钥证书（root 所有，服务账号可读）；私钥只保存在本机 `~/.local/state/djhx-memo-backup/keys/private.pem`，权限 0600，父目录 0700。该证书用于备份加密，与 Nginx HTTPS 证书分别管理。CMS GCM 认证校验可以检出密文损坏；任何持有公钥者均可生成可解密密文，因此不证明来源。主机及 SSH 身份仍须可信。实现依据 [OpenSSL 3.0 CMS 文档](https://docs.openssl.org/3.0/man1/openssl-cms/)。

加密产物放 `/var/lib/djhx-memo-export/`，目录为 `djhx-memo:koril`、2750，文件为 0640。`koril` 通过 SSH 可读取密文，无该目录写权限。日备份命名 `memo-daily-YYYY-MM-DD.cms`（UTC）；同日成功重跑原子替换。周备份命名 `memo-weekly-YYYY-MM-DD.cms`，日期为该 ISO 周的星期一，保存该周第一份成功快照。云端按有效日期名称保留最近 **7 份日备份和 4 份周备份**；失败或长时间中断不会按日历年龄清空最后几份历史备份。保留清理只处理本目录中符合完整命名规则的常规文件。

本机 [拉取脚本](../scripts/backup-pull.sh) 默认将密文放 `~/.local/share/djhx-memo-backups/`（0700）。rsync 只接收符合日/周命名规则的根目录文件，先放在同一文件系统的私有 `.incoming.*` 暂存目录。`--checksum --link-dest` 复用没有变化的本机文件；不使用 `--inplace`。所有暂存快照都通过本机私钥完成 GCM 认证解密到 `/dev/null`，且最新日快照 mtime 不超过 26 小时，才逐文件原子晋升。失败保留之前成功副本及成功时间；`.partial` 和暂存文件不计为成功，普通失败及 SIGINT/SIGTERM 时清理。

本机不会跟随云端删除旧副本，可能积累多于 7+4 份，需定期检查磁盘容量并在确认存在可恢复副本后自行归档；脚本不自动删除本机历史。若云端已经停更超过 26 小时，全部通过认证校验后仍接收本机尚不存在的旧快照，但不会替换已有同名本机副本，本轮仍报失败、不更新最近成功时间。这样初次拉取也能保留可恢复的旧数据，同时暴露云端停更。每小时任务验证所有拉取密文的认证标签，SQL 完整性检查在云端生成时执行，本机 SQL 检查按下文演练执行。

## 定时任务与关机限制

云端 [systemd 服务](../server/deploy/djhx-memo-backup.service) 和 [timer](../server/deploy/djhx-memo-backup.timer) 每天 UTC 03:20（北京时间 11:20），加最多 5 分钟随机延迟生成快照，`Persistent=true` 在云主机恢复后补执行一次。服务禁网并限制目录写权限。现有管理 CLI 打开数据库时会以读写模式验证 schema、设置 WAL 和文件权限，所以服务额外允许精确的 `/var/lib/djhx-memo/db` 目录写入；`runtime/` 与公钥目录只读。备份失败不删除既有成功产物，由 systemd 记录失败及退出码。

本机 [user service](../server/deploy/user/djhx-memo-backup-pull.service) 和 [timer](../server/deploy/user/djhx-memo-backup-pull.timer) 每小时运行一次，最多延迟 2 分钟。`Persistent=true` 在用户管理器恢复时补执行一次，不会为每个错过的小时重复执行。这里保持 `Linger=no`，通常登录后用户管理器才运行，登出、休眠、断网及关机均可能暂停拉取。SSH 使用 `BatchMode=yes`、已核对的主机密钥及 `IdentityAgent=none`，不依赖桌面 SSH agent 的解锁状态。

**本机关机时不会新增异地副本。** 云端仍会按日生成并保留 7+4 份密文；本机启动并登录后补拉当时仍保留的快照，已经早于保留范围的历史点无法补回。`Persistent=true` 和开启 linger 都不能让关机的电脑接收数据。本方案不能保证关机或长期离线期间的异地 RPO ≤24 小时；云服务器与尚未拉走的备份同时丢失时，只能恢复到本机已有的最近副本。需要持续满足该目标时，应增加一直在线的独立备份接收端。RTO ≤2 小时仍需实测恢复演练。

拉取失败、认证失败或最新日快照超过 26 小时会使用户服务失败，并写 `~/.local/state/djhx-memo-backup/pull.status`，包含本轮检查时间、最近成功时间及最近云端快照 mtime。云端日任务失败造成快照停更后，本机下一轮可通过超过 26 小时的阈值察觉；刚过一次失败但旧快照尚在阈值内，不会立即报警。存在 `notify-send` 时首次进入失败状态尝试桌面通知，仅在工具成功返回后记为已通知；连续失败不重复已成功提交的弹窗，通知调用失败时下轮重试。通知服务返回成功不保证操作者实际看到弹窗；无桌面通知会话时仍须检查服务失败与状态文件。电脑关闭时也无法发送本机桌面通知，不使用邮件或其他外部消息服务。

## 首次安装与重复部署

以下是仓库根目录中的参考命令，云端安装步骤需要 sudo；已经生成的密钥必须复用，不能在重装脚本时覆盖。先在本机生成密钥、公钥证书：

```bash
(
  set -euo pipefail
  umask 077
  key_dir="$HOME/.local/state/djhx-memo-backup/keys"
  install -d -m 0700 "$key_dir"
  exec 9>"$key_dir/.generate.lock"
  flock -n 9
  if [[ -e "$key_dir/private.pem" || -e "$key_dir/recipient.pem" ]]; then
    printf '%s\n' '密钥或公钥已存在，请复用；拒绝覆盖。' >&2
    exit 1
  fi
  new_pair=$(mktemp -d "$key_dir/.generate.XXXXXXXX")
  trap 'rm -rf -- "$new_pair"' EXIT
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$new_pair/private.pem"
  openssl req -new -x509 -sha256 -days 3650 -key "$new_pair/private.pem" \
    -subj /CN=djhx-memo-backup-recipient -out "$new_pair/recipient.pem"
  chmod 0600 "$new_pair/private.pem" "$new_pair/recipient.pem"
  # 同一文件系统的硬链接不会覆盖已存在文件，先完成密钥对再逐文件安装。
  ln "$new_pair/private.pem" "$key_dir/private.pem"
  ln "$new_pair/recipient.pem" "$key_dir/recipient.pem"
  scp "$key_dir/recipient.pem" koril@memo.djhx.site:recipient.pem
)
```

请另外保存受保护的离线私钥副本，并记录其对应公钥。私钥丢失后，只有云端公钥和密文不能恢复数据；后续换新密钥时仍需保留旧私钥以解密旧副本。备份私钥、账号口令、邀请码和 token 不进入 Git。

将脚本及云端单元传到服务器暂存目录后，在云端安装（以下假设当前目录已有这些文件；首次建服务账号及业务服务见 [operations](operations.md)）：

```bash
set -euo pipefail
sudo install -d -o root -g djhx-memo -m 0750 /etc/djhx-memo-backup
if sudo test -e /etc/djhx-memo-backup/recipient.pem; then
  sudo cmp -- recipient.pem /etc/djhx-memo-backup/recipient.pem
fi
sudo install -o root -g djhx-memo -m 0640 recipient.pem /etc/djhx-memo-backup/recipient.pem
sudo install -d -o djhx-memo -g djhx-memo -m 0700 /var/lib/djhx-memo/backups
sudo install -d -o djhx-memo -g koril -m 2750 /var/lib/djhx-memo-export
sudo install -d -o root -g root -m 0755 /usr/local/libexec
sudo install -o root -g root -m 0755 backup-cloud.sh /usr/local/libexec/djhx-memo-backup
sudo install -m 0644 djhx-memo-backup.service djhx-memo-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl start djhx-memo-backup.service
sudo systemctl enable --now djhx-memo-backup.timer
sudo systemctl status djhx-memo-backup.service --no-pager
sudo systemctl list-timers djhx-memo-backup.timer --all
```

初次执行后应核对产物权限及 `koril` 只读可访问性。在本机安装拉取任务，不需要 sudo：

```bash
install -d -m 0755 "$HOME/.local/bin"
install -m 0755 scripts/backup-pull.sh "$HOME/.local/bin/djhx-memo-backup-pull"
install -d -m 0700 "$HOME/.config/systemd/user"
install -m 0644 server/deploy/user/djhx-memo-backup-pull.{service,timer} "$HOME/.config/systemd/user/"
systemctl --user daemon-reload
systemctl --user start djhx-memo-backup-pull.service
systemctl --user enable --now djhx-memo-backup-pull.timer
systemctl --user status djhx-memo-backup-pull.service --no-pager
cat "$HOME/.local/state/djhx-memo-backup/pull.status"
```

默认无需配置文件；可在 0600 的 `~/.config/djhx-memo-backup/config` 中为 `EnvironmentFile` 设置 `DJHX_BACKUP_HOST=koril@memo.djhx.site`、`DJHX_BACKUP_REMOTE_DIR=/var/lib/djhx-memo-export/`。可选绝对路径变量为 `DJHX_BACKUP_DEST`、`DJHX_BACKUP_STATE`、`DJHX_BACKUP_PRIVATE_KEY`、`DJHX_BACKUP_RECIPIENT`。systemd 的 EnvironmentFile 不展开 `$HOME`，自定义路径须写完整绝对路径。更换 SSH 主机前先人工核对并保存其 host key，不能关闭校验。重复部署只替换脚本和单元，复用现有私钥、公钥及历史副本。

排查入口：

```bash
journalctl --user -u djhx-memo-backup-pull.service -n 30 --no-pager
systemctl --user list-timers djhx-memo-backup-pull.timer --all
ssh koril@memo.djhx.site 'systemctl status djhx-memo-backup.service --no-pager'
```

## 隔离恢复演练

解密命令即使认证失败也可能先写出部分明文；只有命令成功后才能晋升为待检查数据库。以下全部在新建 0700 临时目录进行，不写 live DB、不复制旧 runtime。`snapshot` 选择一个实际存在的本机 `.cms` 文件；`server_bin` 使用与云端版本和 schema 匹配的已校验服务端二进制，升级 schema 时需同步更新演练工具。

```bash
set -euo pipefail
umask 077
snapshot="$HOME/.local/share/djhx-memo-backups/memo-daily-YYYY-MM-DD.cms"
server_bin="$(pwd)/dist/server/djhx-memo-server-0.1.0-linux-amd64/memo-server-linux-amd64"
key_dir="$HOME/.local/state/djhx-memo-backup/keys"
drill_dir=$(mktemp -d "${TMPDIR:-/tmp}/djhx-memo-restore.XXXXXXXX")
trap 'rm -rf -- "$drill_dir"' EXIT
openssl cms -decrypt -binary -inform DER -in "$snapshot" \
  -recip "$key_dir/recipient.pem" -inkey "$key_dir/private.pem" -passin pass: \
  -out "$drill_dir/snapshot.partial"
mv "$drill_dir/snapshot.partial" "$drill_dir/snapshot.db"
"$server_bin" check --db "$drill_dir/snapshot.db" --epoch-file "$drill_dir/unused-epoch"
```

这会验证 GCM 标签、SQLite 完整性、schema 版本与迁移校验。恢复真实服务时仍须严格执行 [停服、保全现有 DB/WAL、恢复及轮换 epoch](operations.md#6-一致快照与恢复命令)，不能直接覆盖运行数据库，也不能从旧备份复制 runtime 代次。

## 脚本验证

2026-11-07 在本机执行 `bash -n server/deploy/backup-cloud.sh scripts/backup-pull.sh` 与 `python3 server/scripts/backup-smoke.py` 通过。后者使用真实候选服务端、OpenSSL 与 rsync，在临时目录验证一致快照/check、RSA/GCM 加解密、破坏认证标签拒绝、最近 7/4 份保留、长故障后保留旧副本、每周首份不变、失败原子性及权限；本机拉取使用本地 SSH 传输替身连接真实 rsync 进程，验证过滤、同大小同 mtime 损坏的 checksum 检出、staging 不覆盖好副本、26 小时过期与失败通知去重。该传输替身不代表真实 SSH 或云端定时器验收；真实部署与恢复证据由运维记录单列。
