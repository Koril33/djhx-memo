# ADR-0019：更新目录签名与官网部署的自动化边界

- 状态：已接受（目录签名 CI 化已实施；官网部署为 CI 构建 + 半自动脚本，设计见 [website-deployment-ci](../website-deployment-ci.md)）
- 日期：2026-11-20
- 延伸：ADR-0017 中"更新目录签名与官网部署仍在维护者服务器（catalog 钥不进入 GitHub）"的边界

## 背景

- ADR-0017 已把三端构建、package 签名与 draft Release 交给 GitHub Actions；但**更新目录签名**与**官网部署**仍是维护者本机手工步骤。
- 目录签名用一把与 package 钥**不同**的 minisign catalog 钥（`catalog-2026-01`），当前仅以 DPAPI 存于维护者 Windows 主机；目录 `sequence` 必须严格递增，`current.json` 需原子替换。
- 官网部署把 `website/dist/` + `/updates/stable/` 推送到维护者服务器 `memo.djhx.site`，经 Nginx 软链切换、健康检查与回滚，当前无脚本、纯手工。
- 两个步骤都必须在 **Release 公开后**才执行：URL 才确定，且不得先推广尚不可下载的包。

## 决策

1. **更新目录签名 → CI 自动化**（方案 A）：新增 `catalog` 工作流，`on: release: published`。从公开 Release 下载资产 → 构造 manifest → 用 catalog 钥（GitHub Secret `DJHX_CATALOG_SIGNING_KEY`，与 `TAURI_SIGNING_PRIVATE_KEY` package 钥分开）经 [prepare-update-catalog.py](../../scripts/prepare-update-catalog.py) 签名 → 产物附着回 Release。`sequence` 读线上 `current.json` +1。
2. **官网部署 → CI 构建 + 半自动脚本**：CI 负责所有可自动的步骤（构建 `website/dist/`、更新 `downloads.json`、打包部署产物），推送服务器保留为维护者本机一条脚本，**查看简要信息后输入服务器 sudo 密码**完成 rsync + 软链切换 + nginx reload + 健康检查（失败自动回滚）。
3. **不把服务器写权限交给 CI**：不向 GitHub 放 SSH 部署钥；服务器写操作仍由维护者 sudo 密码在脚本内触发。

## 边界

- 两把 minisign 钥（package、catalog）现在都进 GitHub Secrets，但**用途与 secret 分离**：package 钥签 `.exe.sig`，catalog 钥签目录，不得混用。
- Android 原证书（JKS）签名仍由 CI 内完成（ADR-0017），目录里只带 `certificate_sha256`（稳定常量）供客户端与 `apksigner` 校验，Rust 验证器只校验其格式与文件哈希。
- 真机覆盖升级、目录有效性复核、撤回操作仍按 [update-publishing](../../docs/update-publishing.md) 本地执行；脚本不自动做"无新版本也重签"。

## 影响

- 新增 `.github/workflows/catalog.yml`、`scripts/build-catalog-manifest.py`；后续新增官网部署脚本（见 [website-deployment-ci](../website-deployment-ci.md)）。
- `config/update-trust.json` 不变（仍只放公钥）。
- 维护者一次性动作：把 `catalog.key.dpapi` 导出为 GitHub Secret `DJHX_CATALOG_SIGNING_KEY`（同 Android keystore 导出流程）。

## 备选方案

- 目录签名继续本机手工：每版发版都要本机 DPAPI + 手工 manifest，否决。
- 官网全自动 SSH 部署（GitHub 放部署钥）：安全面扩大（CI 持生产服务器写权限），暂不采用；若日后接受，再单独评审。
