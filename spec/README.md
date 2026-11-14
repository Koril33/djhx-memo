# v1 产品与技术契约

版本：0.1.8界面/官网下载优化，保留0.1.7安装屏障及0.1.6客户端增量；数据/同步仍为0.1.4已发布契约，更新于2026-11-14。客户端/core/服务端的单条彻底删除使用同步协议 2，HTTP 路径仍为 /v1；必须配套升级，旧 0.1.3 Release 不变。桌面托盘与列表折叠见增量契约；普通同步、编辑和鉴权仍遵循原基线。实际检查和独立评审见[验证索引](../docs/validation.md)与[0.1.4 发布验证](../docs/releases/0.1.4-validation.md)，规范中的要求不自动表示已通过。

| 文档 | 定义内容 |
|---|---|
| [android-install-compatibility.md](android-install-compatibility.md) | 修复分支：Android系统安装器兼容入口、失败分类与恢复，未发布 |
| [client-0.1.8.md](client-0.1.8.md) | 0.1.8操作语义、账号入口、状态与更新排版、官网直链 |
| [client-0.1.6.md](client-0.1.6.md) | 0.1.6 本轮实现契约：多选、左滑、撤销、单个 Markdown 文件导入 |
| [automatic-updates.md](automatic-updates.md) | 0.1.5 Windows/Android自动更新及0.1.7安装屏障修正、签名目录；Linux自动安装暂缓 |
| [permanent-deletion.md](permanent-deletion.md) | 0.1.4 单条彻底删除、协议 2、Windows 托盘与桌面列表折叠 |
| [product-v1.md](product-v1.md) | 三端范围、编辑、账号、本机导入、托盘和产品验收 |
| [sync-protocol.md](sync-protocol.md) | 版本校验、冲突、重试、分页、恢复代次和客户端状态机 |
| [data-model.md](data-model.md) | 实体、存储边界、约束、迁移和术语 |
| [auth.md](auth.md) | 邀请注册、登录、设备凭据、注销和授权 |
| [api.md](api.md) / [openapi.yaml](openapi.yaml) | HTTP/SSE 行为说明与机器可读 API |
| [core-api.md](core-api.md) | UI 与核心库的接口职责和事件 |
| [core-bridge.md](core-bridge.md) | 当前 UniFFI/桌面 JSON 包装、调用与返回类型 |
| [error-codes.md](error-codes.md) | 稳定错误码和重试行为 |
| [conformance/scenarios.md](conformance/scenarios.md) | 首批一致性场景及期望结果 |

产品流程以 product-v1 为准，冲突与游标语义以 sync-protocol 为准，字段与 wire 类型以 data-model 及 OpenAPI 为准。发现文档冲突时先修正规范，不自行选择另一种实现。所有行为变化同步修改相关契约与场景。

v0.1 规划中关于冲突策略、Web 范围、共享前端和 CI 的旧表述已由本基线替代。架构取舍见 [ADR-0001](../docs/adr/0001-v1-baseline.md)。

## 尚未启用的提案

- 自动更新规范中的 Linux AppImage/DEB 安装适配、跨协议/schema 更新和信任根桥接尚未实现，不属于本轮 Windows/Android 交付能力。

0.1.6 的行为扩展以 [下一版本规范](client-0.1.6.md) 为准，已在0.1.6实现，实际检查见发布记录，不属于 0.1.5 已实现能力；Web 继续暂缓。
