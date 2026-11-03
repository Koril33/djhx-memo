# djhx-memo

面向个人和少数朋友的 Markdown 备忘录，支持本地优先保存和多设备云同步。

**v1 目标：Windows、Android、Linux 三端可日常使用。** 桌面端包含托盘；允许未登录记笔记，提供邀请码注册、登录、同步、搜索、回收站和 Markdown 导出。Web、Apple、全局快捷键、系统分享以及 CI/Runner 暂缓。

当前已建立 monorepo 与 v1 文档基线，尚无业务代码、应用构建或可执行测试。文档中的验收目标与计划命令不表示功能已实现。协议未发布，实施前仍需交叉评审。

## 从这里开始

- [项目计划与实施顺序](djhx-memo-project-plan.md)
- [产品行为与 v1 验收](spec/product-v1.md)
- [同步、数据与接口契约](spec/README.md)
- [架构与模块边界](docs/architecture.md)
- [本地验证与三端验收](docs/validation.md)
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
