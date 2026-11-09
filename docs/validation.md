# 本地质量检查与 v1 验收

## 1. 无 CI 的检查方式

项目没有 CI/Runner，v1 不部署它们。提交人执行检查并记录：提交 SHA、平台、工具版本、命令、结果和未运行原因。另一位协作者评审同步、迁移与鉴权实现；没有评审或未通过必要检查的变更不能因“远程无 CI”而直接投入真实数据。

业务工程已建立。入口为 `bash scripts/validate-local.sh core|server|integration|desktop|android`，每次选择一个模块；工具与原生构建前置条件见各模块 README。真实结果分别记录于 [core](core-validation.md)、[server](server-validation.md)、[desktop](desktop-validation.md)、[Android](android-validation.md)，独立评审见 [核心](reviews/core-review.md)、[服务端](reviews/server-review.md)、[桌面](reviews/desktop-review.md) 和 [Android](reviews/android-review.md)。云端实际检查另见 [部署记录](cloud-deployment.md) 与 [配置评审](reviews/deployment-review.md)。环境阶段的仓库外探针不等同于这些业务检查。

文档结构检查入口为 `python3 scripts/check-docs.py`（Markdown 本地目标、OpenAPI JSON/元数据/内部引用/operationId），再执行 `git diff --check`；该脚本不冒充完整 OpenAPI 3.1 语义校验或业务接口测试。

2026-11-08 的客户端 0.1.1 展示增量另见[桌面界面预览验证](desktop-ui-preview-validation.md)、[Android 界面预览验证](android-ui-preview-validation.md)及[独立复核](reviews/quiet-ui-review.md)。本轮服务端、core、协议和迁移没有变化，不以此前检查替代本轮界面验证，也不重复宣称完整三端发布清单已通过。

2026-11-08 的 0.1.2 下拉框文字与 Android 编辑滚动修正见[增量验证](client-fixes-0.1.2-validation.md)；既有验证按原日期保留，不将其当作本轮全部重新执行。

## 2. 必需的本地检查

| 范围 | 格式/静态检查 | 行为验证 |
|---|---|---|
| core | rustfmt、clippy 拒绝警告 | 本地 CRUD、迁移、搜索、outbox、故障注入和一致性场景 |
| server | gofmt、go vet、选定 Go lint 工具 | go test -race；认证、越权、幂等、事务回滚和分页 |
| Android | Kotlin 格式、detekt、编译 | 单元测试、绑定测试、关键仪器测试和 arm64 真机 |
| desktop | prettier、eslint、TypeScript 检查，Tauri Rust 检查 | 编辑器测试、Windows/Linux 安装与托盘/输入法 |
| 集成 | 契约及数据格式检查 | 真实服务端 + 多个独立 core 数据库，重启/断网/恢复 |
| 文档 | 链接/锚点、OpenAPI 校验 | 命令与实际工程一致 |

`spec` 改动复核所有依赖模块；core 变化复核 Android 与桌面绑定；server 变化执行 core 联调。不同模块职责通过同一组场景的不同适配器断言，不把服务端单测等同于客户端可靠性。

## 3. 三端发布清单

每项记录 Windows / Android / Linux 的实际结果，目标设备与系统信息必须保留。禁止预先勾选。

- [ ] 未登录、断网启动和笔记 CRUD；已提交内容正常重启后完整保留。
- [ ] 中文输入法、emoji、长文、Markdown 预览、任务勾选和常规撤销/复制。
- [ ] 一字/两字中文、英文混合搜索，回收站过滤、删除恢复与本地 ZIP 导出。
- [ ] 邀请注册、登录、本机笔记可选导入、注销重登与账号隔离。
- [ ] 三端互相编辑同步，前台性能达到产品目标。
- [ ] 冲突、同时删除编辑、响应丢失重试、分页中断和通知丢失。
- [ ] 同步期间继续输入、服务端先提交但客户端未知、进程崩溃后的 outbox。
- [ ] 设备吊销、过期凭据、安全凭据服务不可用。
- [ ] Windows/Linux 托盘打开、新建、同步、退出及关闭窗口行为。
- [ ] Linux Wayland 与 X11 冒烟，fcitx/ibus 与托盘不可用时的退化行为。
- [ ] Android 后台调度、进程结束、断网恢复与重新前台同步。
- [ ] 磁盘满/只读、迁移失败/升级、高版本数据库保护。
- [ ] Debian 重启、SSE 反向代理、异地备份和旧备份恢复演练。
- [ ] 三种安装包可安装、升级，卸载/数据目录行为明确。
- [ ] 实测依赖许可证、锁文件和应用版本完整，日志无正文/凭据。

性能条件、数据规模和 P95 指标见 [产品规范](../spec/product-v1.md)，每组测量至少 30 次并记录样本及设备；无法达到时先定位并更新实现，不擅自降低目标。

## 4. 连续 7 天自用

每天至少跨两个目标端编辑并同步，7 天内覆盖全部三端；实际包含一次断网编辑、一次冲突处理、一次导出核验。记录日期、构建 SHA、设备、触发步骤、预期/实际行为及修复结果，不记录敏感正文。

阻断发布：已提交内容丢失、静默覆盖、串账号、持续无法本地保存、不可恢复的同步卡死、无法找回主窗口。体验问题可分级排期，但不得用“多数情况可用”豁免这些故障。
