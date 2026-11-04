# v1 产品与技术契约

版本：0.1.0（未发布的实现基线），更新于 2026-11-04。产品选择依据项目负责人授权确定；工程已进入自用候选实现与三端验收，实际检查和独立评审见 [验证索引](../docs/validation.md)，规范中的要求不自动表示已通过。

| 文档 | 定义内容 |
|---|---|
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
