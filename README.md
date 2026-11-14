# djhx-memo

当前 **[Android0.1.9/code10](https://github.com/Koril33/djhx-memo/releases/tag/v0.1.9)** 已发布安装兼容补丁，Windows/Linux继续0.1.8。修复与实际发行、模拟器正式升级结果见[0.1.9记录](docs/releases/0.1.9-validation.md)；Civi新安装流程尚未完成真机验收。

**[0.1.8三端版](https://github.com/Koril33/djhx-memo/releases/tag/v0.1.8)** 统一操作图标与红底删除，整理账号入口、保存同步及更新页面，并修正官网下载直链。实际检查与升级结果见[0.1.8验证](docs/releases/0.1.8-validation.md)；保留[0.1.7安装屏障修正](docs/releases/0.1.7-validation.md)。core仍0.1.7、server仍0.1.4，协议/schema不变；完整设备矩阵与7天试用仍待完成。

0.1.4 已公开发布，其三端验证、原发布签名和协议 2 部署记录见[发布验证](docs/releases/0.1.4-validation.md)。

面向个人和少数朋友的 Markdown 备忘录，支持本地优先保存和多设备云同步。

**v1 目标：Windows、Android、Linux 三端可日常使用。** 桌面端包含托盘；允许未登录记笔记，提供邀请码注册、登录、同步、搜索、回收站和 Markdown 导出。Web、Apple、全局快捷键、系统分享以及 CI/Runner 暂缓。

0.1.4～0.1.8 已公开发布，当前客户端保持其协议 2 与 schema 基线，core新增本地列表/文件入口。Windows/Linux/Android 客户端已实现，公开安装包以 Release 列出的平台为准。已执行核心/服务端联调及目标系统的部分实际使用验证；完整设备矩阵与连续 7 天日常试用仍待完成，完整产品 v1 尚未正式发布。实际结果和限制见各模块验证记录。

## 从这里开始

- [项目计划与实施顺序](djhx-memo-project-plan.md)
- [产品行为与 v1 验收](spec/product-v1.md)
- [同步、数据与接口契约](spec/README.md)
- [架构与模块边界](docs/architecture.md)
- [开发环境与本机配置边界](docs/dev-setup.md)
- [本地验证与三端验收](docs/validation.md)
- [核心验证](docs/core-validation.md) / [服务端验证](docs/server-validation.md) / [桌面验证](docs/desktop-validation.md) / [Android 验证](docs/android-validation.md)
- [部署、备份与恢复](docs/operations.md)
- [品牌母版、图标尺寸与统一替换](docs/brand-guidelines.md)
- [0.1.2 控件与滚动修正验证](docs/client-fixes-0.1.2-validation.md)
- [0.1.1 界面设计](docs/ui-design.md) / [桌面预览验证](docs/desktop-ui-preview-validation.md) / [Android 预览验证](docs/android-ui-preview-validation.md)
- [已接受决策](docs/adr/0001-v1-baseline.md)
- [协作规范](AGENTS.md) / [贡献流程](CONTRIBUTING.md) / [AI 开发指南](docs/ai-development.md)
- [MIT 许可与第三方边界](docs/licensing.md) / [安全与隐私](SECURITY.md) / [开源准备检查](docs/open-source-readiness.md)

## 仓库结构

```text
djhx-memo/
├── spec/          # 产品行为、同步、模型、接口和一致性场景
├── docs/          # 架构、开发、验证、运维、ADR
├── core/          # Rust + SQLite：本地数据与客户端同步状态机
├── server/        # Go + SQLite：账号、权威版本、同步和 SSE 通知
├── android/       # Kotlin + Compose，经 UniFFI 调用 core
├── desktop/       # Tauri 2 + Svelte + TypeScript，Windows / Linux
├── website/       # 产品介绍与下载的静态官网
├── web/           # 暂缓，仅保留说明；桌面前端归 desktop
└── apple/         # 暂缓，仅保留说明
```

`.github/workflows/` 为此前留下的空目录，不配置工作流，也不作为交付要求。当前没有 CI 或 Runner；实施后的质量检查在开发机与目标设备上运行并记录结果。

## 构建与使用

安装和首次使用见 [候选版使用说明](docs/quick-start.md)。面向访客的产品介绍与下载页源码位于 [website/](website/README.md)。公开版本及安装包通过 [GitHub Releases](https://github.com/Koril33/djhx-memo/releases) 分发。

新开发者先按[公共环境入口](docs/dev-setup.md)选择 [Linux](docs/dev-setup-linux-portable.md)或 [Windows](docs/dev-setup-windows.md)及所需模块。维护者的本机 env.sh、VM、已生成 JNI 和私有签名不是新 clone 的前提。各工程提交 Cargo/npm/Gradle 锁文件，构建步骤见 [server](server/README.md)、[core](core/README.md)、[desktop](desktop/README.md)、[android](android/README.md)。本地检查入口：`bash scripts/validate-local.sh core|server|integration|desktop|android`，每次选择一个模块。

客户端统一显示 **DJHX Memo**。语言支持简体中文、英文和跟随系统；外观支持浅色、深色和跟随系统；时间默认显示为上海（UTC+08:00）。入口与偏好说明见 [候选版使用说明](docs/quick-start.md#语言外观与显示时区)。

客户端首次打开即可离线记录，本机空间不会自动上传。候选服务默认地址为 `https://memo.djhx.site`，使用管理员生成的一次性邀请码注册；登录后可明确选择将本机笔记复制到账号空间。该域名根路径提供[产品官网](https://memo.djhx.site)，同步API仍在 `/v1`；当前没有独立Web笔记客户端。部署检查见 [云部署记录](docs/cloud-deployment.md)，备份恢复见 [operations](docs/operations.md)。

安装包和校验值输出到本机忽略目录 `dist/`。发布时将安装包与校验值上传为 GitHub Release 附件，详见[客户端发布流程](docs/client-release.md)。

Windows / Android 已接入自动更新，入口为设置中的“软件更新”；Linux 适配继续后续实施。启用、密钥主机边界与人工发布见[更新发布说明](docs/update-publishing.md)，原[17 项调研](docs/research/rust-gui-auto-update-survey.md)保留供参考。

服务端独立打包入口为 `bash scripts/package-server.sh`，输出静态 Linux amd64 二进制及部署资料压缩包；不会部署到云服务器。

## 贡献与许可

原创代码与文档采用 [MIT](LICENSE)，便于学习、修改和复用，也允许第三方商用；分发时保留版权及许可声明。依赖、图标和安装器模板继续遵守各自许可，见[许可说明](docs/licensing.md)。贡献前阅读 CONTRIBUTING 与相应 AGENTS；AI 协作者遵守相同 spec、验证和隐私规则。

服务端当前依赖 Unix 文件锁，Windows 开发者通过 WSL/Linux 开发服务端。Windows 桌面客户端使用原生 MSVC/WebView2，Android 使用原生 PowerShell 或 Linux Bash 完成 JNI、绑定生成和 APK 打包。参见 [Windows 开发说明](docs/dev-setup-windows.md)与 [Android 构建入口](android/README.md#构建)。
