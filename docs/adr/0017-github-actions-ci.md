# ADR-0017：GitHub Actions CI/CD（构建、测试自动化与发布草案）

- 状态：已接受（构建/测试自动化与发布草案；工作流分阶段实施，见 [ci-cd-plan](../ci-cd-plan.md)）
- 日期：2026-11-19
- 替代：ADR-0001、ADR-0004、ADR-0005、ADR-0012、ADR-0016 中“暂缓/无 CI/Runner”的表述

## 背景

- 开发与评审在自建 Gitea 上进行，`main` 与发布 tag 推送到公开的 GitHub 仓库。
- 自建 Gitea 只提供代码托管与评审，**无法提供稳定的供编译/打包的虚拟机**：Gitea Actions 需要自备 runner，而 Windows、Linux、Android 三端没有稳定的自托管构建环境，Windows runner 尤其难以自备。
- 三端打包目前由 AI agent 在虚拟机中逐台手工编译、读日志、排错，速度慢、token 消耗大且不可重复。
- 公开 GitHub 仓库的标准托管 runner（`ubuntu-*`、`windows-*`）免费，且 `main`+tag 已经推送到 GitHub，触发方式与现有发布流程一致。

## 决策

采用 **GitHub Actions 作为 CI/CD 基础设施**，负责：

1. 自动化测试：core、server、desktop、Android 中能在托管 runner 上运行的部分。
2. 四类产物构建：服务端 Linux amd64 tar.gz、Linux DEB、Windows NSIS setup.exe、Android unsigned APK。
3. 生成校验文件并创建 **draft（草稿）Release**（仅 tag 触发）。

## 边界（不进入本轮 CI 范围）

- 签名：Tauri 更新签名私钥与 Android 原证书 keystore 暂不进入 GitHub（路线 B，本机签名）。
- 真机/模拟器安装验收、托盘/输入法/升级链路、三端设备矩阵、连续 7 天试用仍由本地人工完成。
- 官网静态签名目录部署、Release 由草稿转为公开仍由维护者核验后执行。
- Gitea 继续放代码与评审；日常开发分支仍靠本地 `scripts/validate-local.sh` 快速反馈（可选后续在 Gitea 部署 Linux `act_runner`，只跑 core/server 测试）。

## 理由

- 消除三端打包对 AI agent/虚拟机的依赖：发版只需推送 tag，几乎不再消耗 token。
- 与现有“Gitea 开发 → 合并 main → 推 main/tag 到 GitHub”的流程一致，不改变开发习惯。
- 公开仓库托管 runner 免费，成本为零。

## 影响

- 更新 `AGENTS.md`、`README.md`、`CONTRIBUTING.md`、`spec/`、相关 ADR 及验证/发布文档中“CI/Runner 暂缓”的表述。
- 新增 `.github/workflows/` 工作流与 [CI/CD 计划](../ci-cd-plan.md)。
- 本地验证与独立交叉评审纪律不变；真实设备验收仍是 v1 发布门槛。

## 备选方案

- 继续无 CI：三端打包慢、token 消耗大、不可重复，否决。
- Gitea Actions + 自备 runner：无稳定编译/打包虚拟机，否决。
- 全自动签名（密钥进 GitHub Secrets）：风险更高，留作后续可选（路线 A），需先完成 Environment + Required reviewers + tag ruleset 防护。
