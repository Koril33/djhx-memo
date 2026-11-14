# 客户端公开发布

0.1.10统一发布Windows EXE、Linux DEB、原证书Android APK（code11），以及Windows更新签名和SHA256SUMS。实际分发、官网签名目录及升级结果以[0.1.10记录](releases/0.1.10-validation.md)为准；图形与名称的固定变更入口见[品牌流程](brand-change-workflow.md)。Windows/Linux跳过0.1.9，Android保留0.1.9系统安装器兼容入口。

下文旧版章节保留历史，不代表当前Android仍缺原证书。官网下载数据由[静态清单](../website/downloads.json)统一生成，同批部署完整构建并执行浏览器JS检查。保持旧标签/附件，不覆盖已公开版本。

Gitea 保存开发分支和评审记录。审核通过后，以保留历史的方式合并到 `main`，将同一主分支和发布标签推到 [GitHub 仓库](https://github.com/Koril33/djhx-memo)。发布者从同一发布提交分别构建各平台，GitHub Release 保存安装包、变更说明和 SHA-256 校验值。

0.1.5 的 Windows/Android 更新器与[本地发行工具](update-publishing.md)已实现，Windows 正式目录已部署；Android 已补齐原证书正式 APK，debug/unsigned 保留资料用途，AppImage 仍待实现。本文既有 0.1.3 Release 记录不改变；SHA256SUMS 不能替代更新签名，推送源码不等于发布安装包。

## 0.1.7 安装屏障补丁

[0.1.7](https://github.com/Koril33/djhx-memo/releases/tag/v0.1.7)修复登录账号后SSE长连接阻止安装屏障排空，三端继续使用原安装身份、更新信任根和Android证书（code8）。0.1.5/0.1.6旧进程需先切本机空间并等待旧流结束再安装，或正式安装包覆盖原目录。Linux仍手动DEB。实际源码/附件哈希、目录推广、测试及升级边界见[发布验证](releases/0.1.7-validation.md)。旧标签/资产保持不变。

## 0.1.6 客户端

本版加入多选trash/撤销/单Markdown导入及Android左滑，沿用0.1.5更新信任根、原APK证书和协议/schema。发行附件为`djhx-memo-0.1.6-windows-x64-setup.exe`、`.exe.sig`、`djhx-memo-0.1.6-linux-amd64.deb`、`djhx-memo-0.1.6-android.apk`及SHA256SUMS。实际构建输入、哈希、分发和三端升级进度见[发布验证](releases/0.1.6-validation.md)；不能把候选附件或目录推广单独当完整升级已通过。

## 0.1.5 客户端与 Android 附件

本次 Windows/Android 更新器保持协议 2、local schema 3；core/server 版本仍为 0.1.4，无需仅为客户端版本重新部署账号服务。完整本轮结果见[0.1.5 发布检查](releases/0.1.5-validation.md)，独立评审见[core](reviews/client-0.1.5-core-review.md)和[平台](reviews/client-0.1.5-platform-review.md)。

| 平台 | 附件与本次边界 |
|---|---|
| Windows x64 | `djhx-memo-0.1.5-windows-x64-setup.exe` 和 `.exe.sig`；Tauri 更新签名不等于 Authenticode |
| Linux amd64 | `djhx-memo-0.1.5-linux-amd64.deb`；继续系统包管理，无 Linux 自动安装能力 |
| Android 正式 | `djhx-memo-0.1.5-android.apk`；原证书、versionCode6，包含两种 ABI |
| Android debug | `djhx-memo-0.1.5-android-debug.apk`；独立测试身份，不覆盖正式安装 |
| Android unsigned | `djhx-memo-0.1.5-android-release-unsigned.apk`；需原证书签名，不可直接安装 |

本轮维护者先授权公开 debug/unsigned 作为临时构建资料，后已追加原证书正式 Android APK；操作与更新目录推广规则见[签名发布说明](update-publishing.md#7-本次-android-待签名附件例外)。Windows/Linux 资产与旧标签不替换，Android 正式下载与序号2签名目录现已使用0.1.5。

## 0.1.4 安装包与升级顺序

本次客户端、core 和服务端版本为 0.1.4，同步协议为 2。先完成[交叉评审](reviews/client-0.1.4-review.md)和[三端实际验证](releases/0.1.4-validation.md)，备份并升级服务端，再公开 Release；旧客户端会收到升级提示，不能继续与协议 2 服务端同步。HTTP 路径仍为 `/v1`，不表示完整产品 v1 已验收。

| 平台 | 构建入口 | GitHub 附件名 |
| --- | --- | --- |
| Windows x64 | `desktop/`：`npm run tauri build -- --bundles nsis -- --locked` | `djhx-memo-0.1.4-windows-x64-setup.exe` |
| Linux amd64 | `desktop/`：`npm run tauri build -- --config src-tauri/tauri.linux.conf.json --bundles deb -- --locked` | `djhx-memo-0.1.4-linux-amd64.deb` |
| Android | 按 Android README 重建双 ABI JNI/绑定，使用原证书签名 release APK | `djhx-memo-0.1.4-android.apk` |
| 服务端 Linux amd64 | `bash scripts/package-server.sh` | `djhx-memo-server-0.1.4-linux-amd64.tar.gz` |

Android 核验 `app.djhx.memo`、`versionCode=5`、两种 ABI 和原发布证书；Windows 当前包未做代码签名。附统一 `SHA256SUMS`，从公开附件重新下载逐一核对后才更新官网版本及下载链接。旧包、旧 tag 和既有 Git 历史保留。

2026-11-12 已发布 [v0.1.4](https://github.com/Koril33/djhx-memo/releases/tag/v0.1.4)，四个包、统一清单的公开下载及官网链接均已核验；云端已配套升级到协议 2，实际证据见上述发布记录。

服务端升级保留原 epoch，并保全旧二进制、数据库/WAL/SHM 和一致快照。新 schema 不能由旧程序直接打开；公网重新接收写入后，不能自动恢复旧备份而丢弃新写入。部署实际结果与公开附件以发布记录和 Release 状态为准。

## 0.1.3 安装包

发布标签为 `v0.1.3`。Windows 优先，Linux 和 Android 可从同一标签构建后追加。0.1.3 是客户端版本，v1 验收状态见[产品规范](../spec/product-v1.md)。

| 平台 | 构建入口 | GitHub 附件名 |
| --- | --- | --- |
| Windows x64 | 在 `desktop/` 执行 `npm ci` 后运行 `npm run tauri build -- --bundles nsis -- --locked` | `djhx-memo-0.1.3-windows-x64-setup.exe` |
| Linux amd64 | 在 Linux 主机的 `desktop/` 执行 `npm ci` 后运行 `npm run tauri build -- --config src-tauri/tauri.linux.conf.json --bundles deb -- --locked` | `djhx-memo-0.1.3-linux-amd64.deb` |
| Android | 按 [Android 构建与签名](../android/README.md#构建)生成并核验签名 release APK | `djhx-memo-0.1.3-android.apk` |

Windows 核验安装、启动和版本，Linux 核验 DEB 架构、安装和启动，Android 核验 `app.djhx.memo`、`versionCode=4`、两种 ABI 与原发布证书。随 Release 提供 `SHA256SUMS`，每行由小写 SHA-256、两个空格和附件名组成。追加平台时一并更新校验文件与版本说明，再从公开链接核对下载。

2026-11-09已补齐三个平台附件和统一校验清单，Windows保留原上传文件；实际来源、哈希及公开下载验证见[发布记录](website-deployment.md#github-release-附件)，本机构建验证见[Android](releases/0.1.3-android-validation.md)和[Linux](releases/0.1.3-linux-validation.md)。GitHub CLI/API登录与Git SSH推送是两种凭据能力，上传附件需要相应仓库的Contents写权限；令牌保存在系统keyring或仓库外私有配置，不写入命令示例、日志和源码。
