# 项目文档

> 2026-11-21 基线为 `v0.1.13 / d73bace`（三端/core0.1.13、Android code14、local schema4、server0.1.4/协议2），公开状态见[发布记录](releases/0.1.13-validation.md)，设备矩阵仍待补。0.2.0已完成[计划复审](reviews/client-0.2.0-plan-review.md)；追加授权的玉白母版/多端资源接入与[本地检查](brand-jade-ivory-validation.md)完成，其余工作包/整版发布尚未完成。

> 状态说明：发布、模块验证和评审记录按日期保留当时状态；代码版本见上方最新基线，产品行为以 [spec](../spec/README.md) 为准，协作规则见 [AGENTS](../AGENTS.md)，试用与完整验收状态见 [validation](validation.md)。[11 月 18 日项目报告](project-status-2026-11-18.md)是历史快照；历史记录中的“7 天试用未完成/待完成”不代表当前。

- Android0.1.9单端发行历史：[发布状态](releases/0.1.9-validation.md)、[行为](../spec/android-install-compatibility.md)、[修复验证](android-install-compatibility-validation.md)、[独立评审](reviews/android-install-compatibility-review.md)。当时Windows/Linux继续0.1.8；0.1.10已统一三端，Civi新兼容入口仍待真机验证。

- 本轮：[0.1.10 品牌与单笔记导出](../spec/client-0.1.10.md)，[实际验证/发行状态](releases/0.1.10-validation.md)、[独立评审](reviews/client-0.1.10-review.md)。0.1.8界面/官网下载与0.1.6列表/导入已实现并保留；Web 暂缓。

- [quick-start.md](quick-start.md)：0.1.10 安装、离线使用与接入自有服务器。
- [client-release.md](client-release.md)：GitHub/Gitea 发布流程；[0.1.4 三端验证与发布](releases/0.1.4-validation.md)、[0.1.4 同步/迁移交叉评审](reviews/client-0.1.4-review.md)；历史 0.1.3 构建：[Android](releases/0.1.3-android-validation.md)、[Linux](releases/0.1.3-linux-validation.md)。
- [0.1.5 三端发布与部署](releases/0.1.5-validation.md)：本轮构建、独立评审与 Android 待签名边界。
- 自动更新：Windows/Android 的[发布与密钥说明](update-publishing.md)、[0.1.5 验证](client-updates-0.1.5-validation.md)；原[17 项调研](research/rust-gui-auto-update-survey.md)与[设计](automatic-update-design.md)，Linux 仍待实现。
- [website-deployment.md](website-deployment.md)：官网、Nginx路由与双远程main的合并部署记录。
- [brand-guidelines.md](brand-guidelines.md)：当前品牌素材、设计板、多端尺寸与平台适配。
- [client-fixes-0.1.2-validation.md](client-fixes-0.1.2-validation.md)：0.1.2 下拉框文字与 Android 编辑滚动的复现、修正及实测。
- [ui-design.md](ui-design.md)：0.1.1 界面预览的共同视觉与移动/桌面布局。
- [architecture.md](architecture.md)：模块、数据流与跨端边界。
- [dev-setup.md](dev-setup.md)：新克隆项目的公共工具链入口、锁定版本与本机配置边界。
- 公共开发：[Linux](dev-setup-linux-portable.md)、[Windows](dev-setup-windows.md)；历史本机记录：[Linux](dev-setup-linux.md)、[Windows](dev-setup-windows-history.md)。
- [validation.md](validation.md)：本地检查与 v1 验收。
- [ci-cd-plan.md](ci-cd-plan.md)：GitHub Actions 构建/测试自动化与发布草案的建设计划；决策见 [ADR-0017](adr/0017-github-actions-ci.md)。
- 实际验证：[核心](core-validation.md)、[服务端](server-validation.md)、[桌面](desktop-validation.md)、[Android](android-validation.md)。
- 独立评审：[核心](reviews/core-review.md)、[服务端](reviews/server-review.md)、[桌面](reviews/desktop-review.md)、[Android](reviews/android-review.md)。
- 展示增量交叉评审：[国际化、主题与品牌](reviews/presentation-review.md)。
- 0.1.1 界面预览：[桌面验证](desktop-ui-preview-validation.md)、[Android 验证](android-ui-preview-validation.md)、[独立复核](reviews/quiet-ui-review.md)。
- [operations.md](operations.md)：部署、备份、恢复与发布要求。
- [adr/](adr/README.md)：技术和产品取舍；以各条目标明的接受/提议状态为准。

产品行为和接口只在 [spec](../spec/README.md) 定义；路线图见 [项目计划](../djhx-memo-project-plan.md)。

- [AI 开发指南](ai-development.md)：任务入口、模块边界、注释、测试隔离与交付规则。
- [许可证说明](licensing.md)、[安全政策](../SECURITY.md)、[开源准备检查](open-source-readiness.md)。

- [brand-change-workflow.md](brand-change-workflow.md)：Logo与中英文名称的固定变更、归档、恢复及三端发布流程。
- [0.1.10验证](releases/0.1.10-validation.md) / [独立评审](reviews/client-0.1.10-review.md)。
- [0.1.10 安全/架构/UI 综合调研](reviews/quality-audit-0.1.10.md)：已按独立核验更正 M1、测试记录与架构表述，区分已复现缺陷、候选风险和未证实的历史自述。
- [PR #27 调研报告事实核验](reviews/quality-audit-0.1.10-verification.md)：独立复核 M1/M2、测试命令及架构/规范引用，区分已复现、需修正和历史证据不足的结论。
- [下一版客户端讨论稿](client-next-discussion.md)：0.2.0 范围确定前的历史讨论，图片仍暂缓；最新选择见下列开发前文档。
- [0.2.0 计划复审](reviews/client-0.2.0-plan-review.md) / [开发准备](client-0.2.0-development.md) / [目标规范](../spec/client-0.2.0.md)：基于0.1.13核对五项范围、冲突/风险与Q1–Q4待决策；Q5母版已确认并完成[多端资源接入/检查](brand-jade-ivory-validation.md)，其余实施与整版发布未完成。
- [0.1.13 开发文档](client-0.1.13-development.md) / [行为契约](../spec/client-0.1.13.md)：空白页/两段式Ctrl+W、固定搜索/来源/布局、Android列表已实现且Release公开；39项用例及设备/升级完成情况仍以[实际记录](releases/0.1.13-validation.md)为准，不能按公开状态自动通过。
- [0.1.13 早期评估](client-0.1.13-assessment.md)：保留 0.1.12 代码核对、桌面挤压验证与标题栏调查；“全部账号”与旧待定表为历史提议，后续开发以定稿文档为准。标题栏仅记录，不纳入本版。
- [0.1.11 快捷键与搜索设计](shortcuts-design.md) / [搜索功能详细设计](search-design.md) / [版本规范](../spec/client-0.1.11.md)：Ctrl+W 关闭到托盘、Ctrl+F 笔记内查找、Ctrl+Shift+F 全局搜索（含 Android 笔记内查找/全局搜索 UI）。已实现并本地测试通过，验证见 [0.1.11 记录](releases/0.1.11-validation.md)。交叉评审见 [Claude 独立评审](reviews/0.1.11-shortcuts-search-claude-review.md)，开源方案调研见 [搜索实现调研](research/search-implementations-survey.md)。
- [2026-11-18 全端静态评审](reviews/static-review-2026-11-18.md) / [项目现状报告](project-status-2026-11-18.md)：一次性只读评审，覆盖 main、0.2.0 准备分支与 CLI 调研分支；提出 X1-X4 优先缺陷、0.2.0 拆分建议与调研文档内网信息处置，不改变 spec 或产品行为。
