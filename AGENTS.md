# djhx-memo 全局协作规范

## 阅读顺序与当前状态

- 阅读 [README](README.md)、[项目计划](djhx-memo-project-plan.md)、[spec 索引](spec/README.md) 及工作目录中的 `AGENTS.md`。
- 当前公开客户端/core0.2.2、Android code17（保留空白页/两段式Ctrl+W、固定搜索及Android列表续写），新增桌面lint/同步滚动、三端下载源和Linux最新DEB浏览器入口；local schema4、server0.1.4/schema2、协议2。官网/个人源镜像、latest及签名current13已按维护者授权部署，见docs/releases/0.2.2-validation.md及docs/releases/0.2.2-deployment.md。不把CI/公网下载当Linux GUI或真实覆盖升级已验收。完整产品v1/三端性能与设备矩阵仍独立验收；连续7天试用已由维护者确认完成，逐日明细未入库，不替代三端矩阵。
- `spec/` 是产品与实现行为的唯一事实来源。改行为先改规范，规范与实现同批提交；不得把计划或占位内容当作已实现的能力。

## 工作方式

- 按任务范围修改目录，跨模块改动说明影响面。
- 文档使用简体中文，代码标识符和提交信息使用英文；提交遵循 Conventional Commits。
- 使用独立分支并推送远程；合并遵循 [贡献流程](CONTRIBUTING.md)。同步、迁移与鉴权变更必须经过交叉评审。
- CI/CD 采用 GitHub Actions（见 [ADR-0017](docs/adr/0017-github-actions-ci.md) 与 [CI/CD 计划](docs/ci-cd-plan.md)）：自建 Gitea 无法提供稳定的编译/打包虚拟机，故使用 GitHub 托管 runner 做构建、测试自动化、签名与发布草案。真实设备验收、更新目录部署与 Release 公开仍由维护者本地完成；继续执行并记录 [本地验证](docs/validation.md)，不宣称未运行的检查通过。
- 本地检查入口为 `bash scripts/validate-local.sh <模块>`，构建入口见各模块 README。文档变更检查链接、契约引用和 Git 差异。
- 引入依赖前查阅 ADR，说明理由、许可证、维护情况与锁定版本；未被现有 ADR 覆盖的新依赖或框架追加 ADR。

## 禁止事项

- 不使用客户端时间判断笔记新旧，不以自增 ID 作为跨设备笔记标识。
- 普通删除保留正文和墓碑；维护者已批准 0.1.4 单条彻底删除，按 spec/permanent-deletion.md 清除当前正文并保留永久删除标记，不实现自动清理。
- 不在 UI 层实现同步或冲突规则，不用 `dirty` 布尔值代替可靠待发送操作。
- 不在日志和错误信息中输出正文、密码、邀请码或 token；凭据不得入仓库。
- 不修改已发布迁移，只追加版本化迁移，并验证升级路径。
- 不为使测试通过而修改或删除既有测试，除非对应 spec 同步变更。

## 交付标准

- 行为变化有对应规范、文档和有效测试；公共接口有文档。
- core、server 按同一组声明式场景分别验证职责，并运行联调测试。
- 三端均属于 v1，不以 Windows 单端通过代替 Android/Linux 验收。
- 不开展暂缓范围：独立 Web、Apple、全局快捷键、系统分享。
- 文档规范的建立不代表已交叉评审；提交说明注明实际检查与未验证项。

## 新开发者、AI 与公开发布

- 公共工具链入口是 [docs/dev-setup.md](docs/dev-setup.md)，按任务选择 Linux/Windows；历史本机记录不是安装前提。AI 另读 [docs/ai-development.md](docs/ai-development.md)，仍以本文件和 spec 为约束。
- 0.1.5 在 0.1.4 基础上增加 Windows/Android 自动更新，按 spec/automatic-updates.md 实施；core/server/协议与 schema 基线不变。Windows 正式目录已部署，Android 原证书正式包已补齐并以序号2目录推广，debug/unsigned 仅保留资料用途；Linux 自动更新安装仍暂缓，本轮使用维护者授权的 Ubuntu 虚拟机验证和打包 DEB。本轮安装屏障独立评审见 docs/reviews/client-0.1.5-core-review.md 和 docs/reviews/client-0.1.5-platform-review.md；三端实际发布与未验收项见 docs/releases/0.1.5-validation.md，完整矩阵仍须单独完成，不能把版本号当成 v1 发布。
- 0.1.4 已公开发布，三端实际检查及独立同步/迁移评审见 docs/releases/0.1.4-validation.md 和 docs/reviews/client-0.1.4-review.md；不将该评审当作 0.1.5 安装屏障已复核。
- 原创内容按根 [LICENSE](LICENSE) 的 MIT 授权；保留第三方许可与来源，见 [docs/licensing.md](docs/licensing.md)。提交者对代码来源和AI辅助内容的可贡献性负责。
- 公共API注释说明行为、边界及错误；平台/并发修正解释原因，不堆砌逐行翻译。不得通过删除或弱化测试、宽泛屏蔽告警/密钥规则获得绿色结果。
- 按 [SECURITY](SECURITY.md)检查待公开源码、可达历史与提交元信息，不扫描后就宣称绝对无漏洞。维护者明确选择保留既有作者身份与完整历史；不擅自重写/强推或改变仓库可见性。
- 外部贡献者无原仓库写权限时使用fork/PR；具备授权的协作者按独立分支提交推送。CI 工作流的改动须经维护者审阅后再打 tag，不得由同一提交者既改 workflow 又自行发版。

0.1.6 依 spec/client-0.1.6.md 增加批量普通删除/严格代次撤销、Android左滑和单Markdown导入；core版本同步0.1.6，server/协议/schema不变。交叉评审见 docs/reviews/client-0.1.6-review.md；发布与三端升级结果只以 docs/releases/0.1.6-validation.md 为准。

0.1.7 修复实时同步占用更新屏障，按 spec/automatic-updates.md 执行，不改变同步协议/schema。实际结果见 docs/releases/0.1.7-validation.md；旧0.1.5/0.1.6需在本机空间结束旧连接后才能自动安装，不以该临时步骤冒充旧版登录状态自动安装已通过。

0.1.8整理七项UI/官网下载体验，行为见spec/client-0.1.8.md；core0.1.7/server0.1.4/协议与schema不变。账号与平台独立复核见docs/reviews/client-0.1.8-review.md，真实发布与三端升级状态见docs/releases/0.1.8-validation.md，不将Windows维护者验收或Android可访问树检查等同于完整设备/像素矩阵。

0.1.9为Android单端安装兼容补丁，Windows/Linux继续0.1.8。PR #21已合并，行为、独立评审、模拟器检查和真实发行状态分别见spec/android-install-compatibility.md、docs/reviews/android-install-compatibility-review.md、docs/android-install-compatibility-validation.md、docs/releases/0.1.9-validation.md。Civi新流程尚未实测，不以浏览器手动覆盖或版本发布代替真机验收；core/server/协议/schema不变。

- 0.1.10图形与名称调整按[固定流程](docs/brand-change-workflow.md)消费统一品牌配置，旧paper-v1与原包保留；不得通过改应用标识刷新图标。实际三端发行/测试见[0.1.10记录](docs/releases/0.1.10-validation.md)，评审见[独立记录](docs/reviews/client-0.1.10-review.md)。

- 0.1.11按 spec/client-0.1.11.md 增加 Ctrl+W关闭到托盘、Ctrl+F笔记内查找、Ctrl+Shift+F全局搜索及 core 跨空间搜索（FTS5 trigram＋C4触发器修正，追加迁移0004使 schema3→4）；两端全局搜索/命中片段/来源与笔记内查找落地。core 独立评审见 docs/reviews/0.1.11-core-impl-review.md，发布与未验收项见 docs/releases/0.1.11-validation.md；Android code12，协议2、server0.1.4不变。

- 0.1.12为 UI 精简：桌面列表头移除品牌行并把菜单并入标题行，Android 搜索过滤改 chips、命中高亮改主题色。client/core0.1.12、Android code13；无规范新增、无 schema/协议变更，见对应提交与 v0.1.12 Release。

- 0.1.13按 spec/client-0.1.13.md 增加桌面空白页与两段式Ctrl+W（先关笔记再关窗口，welcome/draft/note会话状态）、固定本机＋当前选中账号及普通回收站的全局搜索（core新增 local_and_current_account 及 profile 锚点校验、移除两端过滤控件、来源标注、修复桌面结果挤压），以及 Android 基础列表回车续写。client/core0.1.13、Android code14；server/协议/schema 不变。发布与 schema 兼容字段核实见 docs/releases/0.1.13-validation.md。

- 0.2.0已有五项范围的对应源码增量并公开发布（tag 4b10ac7），Q1–Q4/D1–D6已由维护者定稿；目标见spec/client-0.2.0.md，发行事实与验收边界见docs/releases/0.2.0-validation.md。0.2.1已实施桌面空白页精简及Android搜索布局并于2026-11-22公开，见spec/client-0.2.1.md及docs/releases/0.2.1-validation.md；Windows干净安装任务栏图标及Linux窗口图标/Ctrl+W均已由维护者确认正常，不属于本版修复。

- 0.2.2已实现并公开：桌面markdownlint默认关闭、对照按内容双向同步滚动默认开启，Android不做lint；Linux仅增加最新DEB浏览器入口，仍不自动检查/下载/安装；memo.djhx.site为主下载源、GitHub备用，三端更新页可切源。行为见spec/client-0.2.2.md，依赖/传输边界见ADR0021/0022，实际检查与未验收项见docs/releases/0.2.2-validation.md。维护者已审阅并合并PR #34中的workflow，后续追加授权公开Release、初始化下载路由和正式部署，结果见docs/releases/0.2.2-deployment.md；此授权不泛化为未来版本自动公开/部署。保持旧v1目录兼容、固定镜像路径和原签名，不因切源重置防回放。
