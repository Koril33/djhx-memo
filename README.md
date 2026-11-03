# djhx-memo

跨平台、多端云同步的 Markdown 备忘录，面向个人和少数朋友使用。

本仓库采用 monorepo，当前完成目录初始化，尚未初始化各语言工具链或实现业务功能。项目范围、技术选型与路线图见 [项目规划文档](djhx-memo-project-plan.md)。

## 仓库结构

```text
djhx-memo/
├── djhx-memo-project-plan.md   # 原始规划草案
├── AGENTS.md                  # 全局协作规范
├── .github/workflows/         # CI 工作流预留
├── docs/                      # 架构、开发与运维文档
│   └── adr/                   # 架构决策记录
├── spec/                      # 待细化的协议与契约
│   └── conformance/           # 跨语言一致性场景
├── core/                      # Rust 核心库：本地存储、同步与冲突处理
│   └── src/
├── server/                    # Go 服务端：认证、同步 API 与通知
├── android/                   # Kotlin + Jetpack Compose 客户端
├── desktop/                   # Tauri 桌面客户端（Windows / Linux）
├── web/                       # TypeScript 前端及桌面端共享组件
└── apple/                     # 仅预留说明，v1 不实现
```

Android 通过 UniFFI 调用 `core`，桌面端通过 Rust 直接链接 `core`。桌面端与 Web 端共享前端组件；共享包布局及前端框架在后续里程碑确定。Web v1 倾向于仅在线使用，尚待正式决策。

## 开发起点

先阅读 [协作规范](AGENTS.md) 与 [契约目录说明](spec/README.md)。下一步为 M0：细化并评审协议、数据模型、错误码、OpenAPI 和一致性用例，再建立各模块工具链、统一命令与 CI。

当前没有可运行的应用、构建命令或测试套件；各模块 README 记录其职责和后续初始化内容。
