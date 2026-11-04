# djhx-memo

面向个人和少数朋友的 Markdown 备忘录，支持本地优先保存和多设备云同步。

**v1 目标：Windows、Android、Linux 三端可日常使用。** 桌面端包含托盘；允许未登录记笔记，提供邀请码注册、登录、同步、搜索、回收站和 Markdown 导出。Web、Apple、全局快捷键、系统分享以及 CI/Runner 暂缓。

当前为 **0.1.0 自用候选实现**：Go 服务端、Rust 核心与三端客户端已建立，正在完成目标系统安装包和交叉验收。协议与 v1 均未正式发布，连续 7 天日常试用尚未完成。实际检查与限制见各模块验证记录，不能把构建成功等同于全部发布条件通过。

## 从这里开始

- [项目计划与实施顺序](djhx-memo-project-plan.md)
- [产品行为与 v1 验收](spec/product-v1.md)
- [同步、数据与接口契约](spec/README.md)
- [架构与模块边界](docs/architecture.md)
- [开发环境与本机配置边界](docs/dev-setup.md)
- [本地验证与三端验收](docs/validation.md)
- [核心验证](docs/core-validation.md) / [服务端验证](docs/server-validation.md) / [桌面验证](docs/desktop-validation.md) / [Android 验证](docs/android-validation.md)
- [部署、备份与恢复](docs/operations.md)
- [已接受决策](docs/adr/0001-v1-baseline.md)
- [协作规范](AGENTS.md) / [贡献流程](CONTRIBUTING.md)

## 仓库结构

```text
djhx-memo/
├── spec/          # 产品行为、同步、模型、接口和一致性场景
├── docs/          # 架构、开发、验证、运维、ADR
├── core/          # Rust + SQLite：本地数据与客户端同步状态机
├── server/        # Go + SQLite：账号、权威版本、同步和 SSE 通知
├── android/       # Kotlin + Compose，经 UniFFI 调用 core
├── desktop/       # Tauri 2 + Svelte + TypeScript，Windows / Linux
├── web/           # 暂缓，仅保留说明；桌面前端归 desktop
└── apple/         # 暂缓，仅保留说明
```

`.github/workflows/` 为此前留下的空目录，不配置工作流，也不作为交付要求。当前没有 CI 或 Runner；实施后的质量检查在开发机与目标设备上运行并记录结果。

## 构建与使用

安装和首次使用见 [候选版使用说明](docs/quick-start.md)。

本机工具链加载：`. "$HOME/.config/djhx-dev/env.sh"`；新机器先阅读开发说明。各工程提交 Cargo/npm/Gradle 锁文件，构建步骤见 [server](server/README.md)、[core](core/README.md)、[desktop](desktop/README.md)、[android](android/README.md)。本地检查入口：`bash scripts/validate-local.sh core|server|integration|desktop|android`，每次选择一个模块。

客户端首次打开即可离线记录，本机空间不会自动上传。部署服务端并配置 HTTPS 根地址后，在客户端使用管理员生成的一次性邀请码注册；登录后可明确选择将本机笔记复制到账号空间。部署命令与备份恢复见 [operations](docs/operations.md)，云服务器由项目负责人部署。

安装包和校验值输出到本机忽略目录 `dist/`，不会把二进制、运行数据或发布签名密钥提交 Git。

服务端独立打包入口为 `bash scripts/package-server.sh`，输出静态 Linux amd64 二进制及部署资料压缩包；不会部署到云服务器。
