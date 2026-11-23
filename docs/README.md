# 项目文档

> 当前公开基线（2026-11-22）：客户端/core0.2.2、Android code17，local schema4、server0.1.4/schema2、协议2。发行与未验收项见 [0.2.2 验证](releases/0.2.2-validation.md) 与 [生产部署](releases/0.2.2-deployment.md)；产品行为以 [spec](../spec/README.md) 为准，协作规则见 [AGENTS](../AGENTS.md)，完整 v1 验收见 [validation](validation.md)。

## 入口

- 使用：[quick-start](quick-start.md)　安装、离线使用与接入自有服务器。
- 开发：[dev-setup](dev-setup.md)　公共工具链入口（[Linux](dev-setup-linux-portable.md) / [Windows](dev-setup-windows.md)）。
- 架构：[architecture](architecture.md)　模块、数据流与跨端边界。
- 验收：[validation](validation.md)　本地检查与三端 v1 清单。
- 运维：[operations](operations.md)　部署、备份、恢复；[云部署](cloud-deployment.md)；[下载镜像运行手册](download-mirror-deployment.md)。
- CI/CD：[ci-cd-plan](ci-cd-plan.md)　GitHub Actions（决策见 [ADR-0017](adr/0017-github-actions-ci.md)）。
- 品牌：[brand-guidelines](brand-guidelines.md)　母版与尺寸；[brand-change-workflow](brand-change-workflow.md)　变更流程。
- 合规：[licensing](licensing.md)　[安全政策](../SECURITY.md)　[开源准备](open-source-readiness.md)　[AI 开发指南](ai-development.md)。

## 产品与实现契约（spec）

产品行为、同步、数据、接口与一致性场景只在 [spec](../spec/README.md) 定义，改行为先改规范。核心契约：产品范围 [product-v1](../spec/product-v1.md)、同步 [sync-protocol](../spec/sync-protocol.md)、数据 [data-model](../spec/data-model.md)、鉴权 [auth](../spec/auth.md)、接口 [api](../spec/api.md) 与 [openapi](../spec/openapi.yaml)、core 桥接 [core-api](../spec/core-api.md) / [core-bridge](../spec/core-bridge.md)、错误码 [error-codes](../spec/error-codes.md)、一致性场景 [scenarios](../spec/conformance/scenarios.md)。

## 版本事实记录

发布验证与独立评审按日期保留于 [releases](releases/) 与 [reviews](reviews/) 目录；历史记录不代表当前。近期版本：0.2.2、0.2.1、0.2.0、0.1.13、0.1.11、0.1.10、0.1.9、0.1.8、0.1.6、0.1.5、0.1.4、0.1.3。发布流程见 [client-release](client-release.md)。

## 设计与决策

- 架构决策：[adr/README](adr/README.md)，以各条目标明的接受/提议状态为准。
- 搜索与快捷键：[search-design](search-design.md) / [shortcuts-design](shortcuts-design.md)。
- 自动更新：[automatic-update-design](automatic-update-design.md)、[update-publishing](update-publishing.md) 与 [调研](research/rust-gui-auto-update-survey.md)。
