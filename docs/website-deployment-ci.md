# 官网部署 CI 构建 + 半自动脚本设计

> 0.2.2实施补充：个人源文件、两源核验、Linux别名与安全推广顺序见[下载镜像运行手册](download-mirror-deployment.md)及[验证记录](releases/0.2.2-validation.md)。以下历史部署/回滚说明不授权将已推广的签名current回退低序号；0.2.2使用独立持久feed与文件区。当前未执行生产部署。

- 状态：**设计草案，待交叉评审后再实施**。决策见 [ADR-0019](adr/0019-update-catalog-website-ci.md)。
- 范围：把"CI 构建 + 半自动部署"落地。所有可自动的步骤进 CI；推送服务器保留为维护者本机一条脚本，**查看摘要、确认后输入服务器 sudo 密码**完成，失败自动回滚。
- 现状基线：[website-deployment](website-deployment.md)（手工 SSH + Nginx 软链 + 健康检查）、[update-publishing](update-publishing.md)（目录签名与部署顺序）。

## 1. 目标与边界

| 项 | 目标 |
|---|---|
| CI 自动 | 构建 `website/dist/`、生成 `downloads.json`（版本从 tag 派生）、打包 `website/dist/` + `updates/stable/`（目录）为单个部署包 |
| 本机脚本 | 下载部署包 → 打印摘要（版本/文件/大小/将改变的软链目标）→ 确认 → 输入 sudo 密码 → 上传 + 软链切换 + nginx reload + 健康检查；失败回滚 |
| 不进入 CI | 服务器写权限（不放 SSH 部署钥）、sudo 密码、最终人工核验 |

## 2. CI 侧

### 2.1 触发

与目录签名同源：`on: release: published`（Release 公开后 URL 确定、资产可匿名下载）。放在 [catalog.yml](../.github/workflows/catalog.yml) 里追加一个 `bundle` job，`needs: [catalog]`，复用同一公开 Release 资产。

### 2.2 bundle job

1. checkout（含 `website/`、`scripts/`）。
2. setup-node（版本同 desktop，Node 22），`npm ci` in `website/`。
3. 用 tag 生成 `website/downloads.json`（版本、资产名按 `djhx-memo-<ver>-<platform>.<ext>` 确定性推导，与 `check-website-downloads.py` 的期望 URL 一致）。
4. `npm run build`（Vite + copy-notices）→ `website/dist/`。
5. 从 `catalog` job 取目录产物（`current.json` + `catalog-<seq>.json`），放到 `updates/stable/`。
6. 打成一个 tar.gz（`deploy-bundle-<ver>.tar.gz`），`upload-artifact` 上传（或附着 Release，与目录一致）。

> `downloads.json` 生成用一个小脚本（同 `build-catalog-manifest.py` 模式），不手工改提交；`check-website-downloads.py` 的浏览器回归仍在部署后由脚本/人工执行。

## 3. 部署脚本（本机，待实施）

`scripts/deploy-website.sh`（维护者 Windows 用 Git Bash / PowerShell 均可，首选 bash + OpenSSH）：

1. **取包**：`gh run download`（或从 Release 下载 bundle）。
2. **摘要**：打印版本、三个下载 URL、文件清单与字节数、线上当前 `current.json` 的 sequence → 本次 sequence、目标软链路径。**不直接改任何东西。**
3. **确认**：要求显式输入 `yes`（或 `--yes` 跳过，仅 CI/无人值守用，默认关闭）。
4. **sudo 密码**：交互读取一次，仅通过 `ssh -t … sudo -S` 的标准输入传递，不写入文件、历史或日志。
5. **上传**：`scp` 到服务器 staging 目录（如 `/var/www/memo.djhx.site-releases/<时间戳>-<commit>/`）。
6. **远程切换**（ssh 执行，需 sudo）：
   - `nginx -t` 通过后 `ln -sfn` 切换 `/var/www/memo.djhx.site` 软链；
   - 复制 `updates/stable/` 的 `catalog-<seq>.json`，原子替换 `current.json`；
   - `systemctl reload nginx`。
7. **健康检查**：HTTPS 首页 200 + 三个下载 URL 302/200 + `/updates/stable/current.json` 的 sequence 与签名字段核对。
8. **回滚**：任一步失败，软链切回上一个版本目录、恢复旧 `current.json`、`nginx -t`+reload，报告具体错误；不删新 staging 目录（留现场）。
9. **旧版本目录**：保留最近 N 个（如 3 个），更旧的清理（可选，默认不删）。

> 服务器侧只增一个幂等的 `deploy` 辅助（软链切换 + nginx + 健康检查），不重启 Go 进程、不触碰笔记数据库/epoch/鉴权（沿用 website-deployment.md 的硬约束）。

## 4. 顺序与目录

- 完整发布顺序：tag push → [release.yml](../.github/workflows/release.yml) 构建 + draft → 维护者公开 → [catalog.yml](../.github/workflows/catalog.yml) 签目录 + 打包 → 本机 `deploy-website.sh` 部署 → `check-website-downloads.py` 浏览器回归（可选）。
- 目录 `sequence` 由 `build-catalog-manifest.py` 读线上 `current.json` +1，保证严格递增；重复运行会因 sequence 已占用而拒绝（prepare-update-catalog.py 已有校验）。

## 5. 风险与决策（交叉评审后定稿）

| 项 | 决定/说明 | 状态 |
|---|---|---|
| sudo 密码传递 | 经 `printf | ssh "sudo -S -p '' bash 远程脚本"` stdin，不入文件/历史/日志；脚本为文件、密码为 stdin，二者不冲突。sudoers NOPASSWD（仅限 deploy 命令）留作后续硬化 | 已定 |
| bundle↔catalog 交接 | catalog job 增补 `upload-artifact`，bundle job `download-artifact` 同 run 取目录 | 已定 |
| `downloads.json` 每平台版本 | v1 三平台同版本（单 tag 派生）；历史单平台发版（如 0.1.9）由手工改，生成器不支持分叉 | 已定 |
| 健康检查 reload 异步窗口 | 部署脚本已加"5 次重试 × 2s"（对应 website-deployment.md 记录） | 已定 |
| 服务端 sequence 单调校验 | 部署脚本切换前校验 `新 sequence > 线上 sequence`，否则拒绝 | 已定 |
| 服务器 SSH 用户与路径 | 需维护者提供可 sudo 的部署账号与 `/var/www` 布局（`DJHX_DEPLOY_SSH_TARGET`/`DJHX_DEPLOY_WWW_ROOT`） | 待维护者确认 |
| 软链切换 + current.json 原子性 | 二者为两次原子操作、存在短暂窗口；旧目录保留、`nginx -t` 前置、失败回滚 | 已设计（已知边界） |
| Release immutability | catalog/bundle 附着回公开 Release 依赖 `immutable=false`（当前已核）；若策略变更需改用新版本追加 | 已设计（依赖项） |
| catalog 私钥是否带口令 | 需确认 `DJHX_CATALOG_SIGNING_KEY` 为无口令 minisign 私钥；带口令则另设 `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` | 待维护者确认 |
| APK 证书交叉校验 | catalog job 已加 `keytool -printcert -jarfile` 硬校验（与常量不一致即 fail） | 已实施 |
| 目录 30 天有效期 | prepare-update-catalog.py 默认 30 天；公开后应尽快部署，否则目录过期需重签更高序号 | 已设计 |
| 浏览器回归 | 部署后由 `check-website-downloads.py` 或人工执行 | 待确认是否纳入脚本 |
| 无新版本重签目录 | 脚本不做；仍按 update-publishing.md 人工 | 已定 |

## 6. 验收

- CI：`catalog` job 产出签名目录（附 APK 证书交叉校验）；`bundle` job 产出 `site/` + `updates/stable/` 打包，`downloads.json` 版本正确。
- 本机脚本：在非生产 staging 先试跑（软链切换 + 回滚各一次），再对生产执行；记录摘要与回滚日志。
- 部署后：公网 HTTPS 首页 200、三平台下载直链可达、`current.json` 可匿名获取且签名/sequence 正确。
