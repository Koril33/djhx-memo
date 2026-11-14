# 项目文档

- Android0.1.9单端发行：[发布状态](releases/0.1.9-validation.md)、[行为](../spec/android-install-compatibility.md)、[修复验证](android-install-compatibility-validation.md)、[独立评审](reviews/android-install-compatibility-review.md)。Windows/Linux继续0.1.8，Civi新兼容入口仍待真机验证。

- 本轮：[0.1.8 界面与官网下载](../spec/client-0.1.8.md)，[实际验证/发行状态](releases/0.1.8-validation.md)、[独立评审](reviews/client-0.1.8-review.md)。0.1.6列表/导入已实现并保留；Web 暂缓。

- [quick-start.md](quick-start.md)：候选版安装、离线使用与接入自有服务器。
- [client-release.md](client-release.md)：GitHub/Gitea 发布流程；[0.1.4 三端验证与发布](releases/0.1.4-validation.md)、[0.1.4 同步/迁移交叉评审](reviews/client-0.1.4-review.md)；历史 0.1.3 构建：[Android](releases/0.1.3-android-validation.md)、[Linux](releases/0.1.3-linux-validation.md)。
- [0.1.5 三端发布与部署](releases/0.1.5-validation.md)：本轮构建、独立评审与 Android 待签名边界。
- 自动更新：Windows/Android 的[发布与密钥说明](update-publishing.md)、[0.1.5 验证](client-updates-0.1.5-validation.md)；原[17 项调研](research/rust-gui-auto-update-survey.md)与[设计](automatic-update-design.md)，Linux 仍待实现。
- [website-deployment.md](website-deployment.md)：官网、Nginx路由与双远程main的合并部署记录。
- [brand-guidelines.md](brand-guidelines.md)：DJHX Memo 可编辑母版、设计板、多端尺寸与统一替换步骤。
- [client-fixes-0.1.2-validation.md](client-fixes-0.1.2-validation.md)：0.1.2 下拉框文字与 Android 编辑滚动的复现、修正及实测。
- [ui-design.md](ui-design.md)：0.1.1 界面预览的共同视觉与移动/桌面布局。
- [architecture.md](architecture.md)：模块、数据流与跨端边界。
- [dev-setup.md](dev-setup.md)：新克隆项目的公共工具链入口、锁定版本与本机配置边界。
- 公共开发：[Linux](dev-setup-linux-portable.md)、[Windows](dev-setup-windows.md)；历史本机记录：[Linux](dev-setup-linux.md)、[Windows](dev-setup-windows-history.md)。
- [validation.md](validation.md)：无 CI 环境下的本地检查与 v1 验收。
- 实际验证：[核心](core-validation.md)、[服务端](server-validation.md)、[桌面](desktop-validation.md)、[Android](android-validation.md)。
- 独立评审：[核心](reviews/core-review.md)、[服务端](reviews/server-review.md)、[桌面](reviews/desktop-review.md)、[Android](reviews/android-review.md)。
- 展示增量交叉评审：[国际化、主题与品牌](reviews/presentation-review.md)。
- 0.1.1 界面预览：[桌面验证](desktop-ui-preview-validation.md)、[Android 验证](android-ui-preview-validation.md)、[独立复核](reviews/quiet-ui-review.md)。
- [operations.md](operations.md)：部署、备份、恢复与发布要求。
- [adr/](adr/README.md)：技术和产品取舍；以各条目标明的接受/提议状态为准。

产品行为和接口只在 [spec](../spec/README.md) 定义；路线图见 [项目计划](../djhx-memo-project-plan.md)。

- [AI 开发指南](ai-development.md)：任务入口、模块边界、注释、测试隔离与交付规则。
- [许可证说明](licensing.md)、[安全政策](../SECURITY.md)、[开源准备检查](open-source-readiness.md)。
