# 客户端公开发布

Gitea 保存开发分支和评审记录。审核通过后，以保留历史的方式合并到 `main`，将同一主分支和发布标签推到 [GitHub 仓库](https://github.com/Koril33/djhx-memo)。发布者从同一发布提交分别构建各平台，GitHub Release 保存安装包、变更说明和 SHA-256 校验值。

## 0.1.3 安装包

发布标签为 `v0.1.3`。Windows 优先，Linux 和 Android 可从同一标签构建后追加。0.1.3 是客户端版本，v1 验收状态见[产品规范](../spec/product-v1.md)。

| 平台 | 构建入口 | GitHub 附件名 |
| --- | --- | --- |
| Windows x64 | 在 `desktop/` 执行 `npm ci` 后运行 `npm run tauri build -- --bundles nsis -- --locked` | `djhx-memo-0.1.3-windows-x64-setup.exe` |
| Linux amd64 | 在 Linux 主机的 `desktop/` 执行 `npm ci` 后运行 `npm run tauri build -- --config src-tauri/tauri.linux.conf.json --bundles deb -- --locked` | `djhx-memo-0.1.3-linux-amd64.deb` |
| Android | 按 [Android 构建与签名](../android/README.md#构建)生成并核验签名 release APK | `djhx-memo-0.1.3-android.apk` |

Windows 核验安装、启动和版本，Linux 核验 DEB 架构、安装和启动，Android 核验 `app.djhx.memo`、`versionCode=4`、两种 ABI 与原发布证书。随 Release 提供 `SHA256SUMS`，每行由小写 SHA-256、两个空格和附件名组成。追加平台时一并更新校验文件与版本说明，再从公开链接核对下载。
