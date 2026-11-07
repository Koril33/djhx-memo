# ADR-0009：云端公钥加密备份与本机拉取工具

- 状态：已接受部署方案；实际部署检查见运维记录
- 日期：2026-11-07
- 影响：server/deploy、scripts、运维文档；不改变业务协议或数据库迁移

## 决策

使用操作系统提供的 OpenSSL CMS 将 Go 服务端一致快照加密为 AES-256-GCM AuthEnvelopedData，RSA-3072 公钥接收者采用 OAEP/SHA-256。私钥只保留本机与受保护的离线副本，云端只持有公钥。选择现有工具可以避免为备份再引入 Go/Rust 密码库，不增加包内依赖。

使用现有 rsync 经 OpenSSH 从本机主动拉取，服务端只开放密文读取权限；systemd system/user timers 分别调度云端生成与本机接收。采用私有暂存目录、认证解密及逐文件原子晋升，避免把传输未完成或标签损坏的文件覆盖到已成功副本。具体权限、7/4 保留与关机限制见 [backup-local](../backup-local.md)。不引入外部存储账号、CI、Runner 或外部通知服务。

## 版本、许可证与维护

以下为 2026-11-07 实测 `dpkg-query` 版本；使用 Debian 发布仓库提供的维护更新，不为锁死工具版本而冻结安全补丁。变更包版本后重跑隔离验证并更新记录。

| 工具 | 云端 Debian 12 | 本机 Debian 13 | 许可证及维护来源 |
|---|---|---|---|
| OpenSSL | 3.0.18-1~deb12u1 | 3.5.7-1~deb13u3 | Apache-2.0；[OpenSSL 项目](https://openssl-library.org/source/license/)，Debian 安全维护 |
| rsync | 3.2.7-1+deb12u4 | 3.5.0+ds1-0+deb13u1 | GPL-3.0-or-later；[rsync 项目](https://rsync.samba.org/)，Debian 维护 |
| OpenSSH client | 1:9.2p1-2+deb12u7 | 1:10.0p1-7+deb13u4 | BSD 与 ISC 等组件许可证；[OpenSSH](https://www.openssh.com/)，Debian 维护 |
| systemd | 252.39-1~deb12u1 | 258.1-1 | LGPL-2.1-or-later 等组件许可证；[systemd](https://systemd.io/)，Debian 维护 |
| Bash / coreutils / util-linux | 5.2.15-2+b10 / 9.1-1 / 2.38.1-5+deb12u3 | 5.2.37-2+b10 / 9.7-3 / 2.41.5-0+deb13u1 | 系统现有 shell、文件工具与 flock；GPL-3.0-or-later、GPL-2.0-or-later 等组件许可证，以 `/usr/share/doc/*/copyright` 为准，Debian 维护 |
| notify-send（可选） | 不需要 | libnotify-bin 0.8.6-1 | LGPL-2.1-or-later；[libnotify](https://gitlab.gnome.org/GNOME/libnotify)，桌面会话可用时尝试通知 |

[OpenSSL 3.0 CMS 官方文档](https://docs.openssl.org/3.0/man1/openssl-cms/) 明确支持 AES-GCM AEAD。不使用 `openssl enc` 拼接无认证的 CBC 加密。CMS 认证解密拒绝损坏不等于证明服务器来源，SSH 主机身份与操作系统权限仍是可信边界。密文格式及快照可读性以脚本隔离测试和真实恢复演练验证。

## 备选与限制

另装 age/GPG 或接入外部对象存储会增加运维与密钥管理依赖；当前使用已有 OS 工具满足用户要求的本机备份。用户电脑关机时无法接收新增副本，不能把该方案描述为持续满足异地 RPO ≤24 小时。若需要一直在线的异地副本、密钥轮换或更强来源认证，另行记录部署变更，不隐式引入新的云服务。
