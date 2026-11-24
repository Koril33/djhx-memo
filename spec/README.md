# v1 产品与技术契约

发行基线：客户端/core0.2.2、Android code17，local schema4、server0.1.4/schema2、协议2，HTTP路径 /v1；更新于2026-11-22。0.2.2已公开，官网/个人源镜像及current13已部署，见[规范](client-0.2.2.md)、[开发文档](../docs/client-0.2.2-development.md)、[本轮验证](../docs/releases/0.2.2-validation.md)和[生产部署](../docs/releases/0.2.2-deployment.md)。发行与公网检查不替代真实覆盖升级/设备矩阵；同步、鉴权与删除继续遵守既有契约。

| 文档 | 定义内容 |
|---|---|
| [client-0.2.3.md](client-0.2.3.md) | 开发中：桌面设置说明按需展开、设置高度稳定、工具栏图标化、列表多选去重、两端破坏性菜单位置对齐；未实施 |
| [client-0.2.2.md](client-0.2.2.md) | 已接入：桌面可选lint/双向同步滚动，Linux最新DEB入口，个人服务器主源/GitHub备用与三端切源；实际平台/部署边界见验证记录 |
| [client-0.2.1.md](client-0.2.1.md) | 已实现/公开：桌面空白页精简/三个按钮，Android搜索区域水平约束；实际矩阵见发布记录 |
| [client-0.2.0.md](client-0.2.0.md) | 已实现/公开：大笔记、Android离线交互、排版、jade图标及Windows默认目录；完整矩阵仍以[实际记录](../docs/releases/0.2.0-validation.md)为准 |
| [client-0.1.13.md](client-0.1.13.md) | 已实现/公开：桌面空白页、两段式Ctrl+W、固定本机＋当前选中账号及普通回收站搜索/来源/布局、Android基础列表；验收状态见[实际记录](../docs/releases/0.1.13-validation.md) |
| [client-0.1.11.md](client-0.1.11.md) | 0.1.11快捷键（Ctrl+W关闭到托盘、Ctrl+F笔记内查找、Ctrl+Shift+F全局搜索）与本地全文搜索（相关性、高亮、片段、范围过滤）；设计见 [快捷键设计](../docs/shortcuts-design.md) 与 [搜索功能详细设计](../docs/search-design.md)，验证见 [0.1.11 记录](../docs/releases/0.1.11-validation.md) |
| [client-0.1.10.md](client-0.1.10.md) | 0.1.10品牌更新固定流程、sunrise图标与中英文名称、单篇Markdown导出、危险操作留白 |
| [android-install-compatibility.md](android-install-compatibility.md) | Android0.1.9系统安装器兼容入口、失败分类与恢复；该补丁发布时Windows/Linux仍0.1.8，0.1.10已统一三端 |
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

v0.1 规划中关于冲突策略、Web 范围、共享前端的旧表述已由本基线替代。CI/CD 基础设施的取舍见 [ADR-0017](../docs/adr/0017-github-actions-ci.md)；其余架构取舍见 [ADR-0001](../docs/adr/0001-v1-baseline.md)。

## 尚未启用的提案

- 自动更新规范中的 Linux AppImage/DEB 安装适配、跨协议/schema 更新和信任根桥接尚未实现；0.2.2仅增加Linux最新DEB浏览器入口，不启用自动检查/下载/安装。

0.1.6 的行为扩展以 [下一版本规范](client-0.1.6.md) 为准，已在0.1.6实现，实际检查见发布记录，不属于 0.1.5 已实现能力；Web 继续暂缓。
