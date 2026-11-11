# 自动更新调研与设计检查记录

- 日期：2026-11-11；平台：Windows / PowerShell。
- 分支：`codex/automatic-update-research`。
- 基线：从本机 `1634835` 新建研究分支后，快进到另一主机已推送的 `93b0f18`（0.1.4 开发分支）；未合并、改写或发布 main。
- 范围：文档、证据索引与方案提案；未修改应用代码、依赖、迁移或安装包。

## 实际检查

| 检查 | 结果与边界 |
|---|---|
| 官方资料/固定源码 | 17 个独立产品或产品家族，42 个入选源码/配置文件；证据清单包含完整 commit、URL 与摘要；逐项说明能力和局限 |
| 网络 | 官方页面/GitHub 部分直连超时后，本机下载使用 `http://127.0.0.1:7897` 代理；仅本次命令生效，未写入公共配置，未关闭 TLS 校验 |
| 已有 CLI 功能 | 在 `desktop/` 执行 `.\node_modules\.bin\tauri.cmd signer sign --help` 成功，确认 `--app-version`；未读取/生成真实私钥，未签名或安装包 |
| Markdown/OpenAPI 结构 | `python scripts/check-docs.py` 通过：Markdown 本地目标、OpenAPI JSON/元数据/内部引用、12 个唯一操作；新增文档引用人工复核 |
| 差异/文件边界 | `git diff --cached --check` 通过；仅 13 个 Markdown/JSON 文档变化；证据 JSON 可解析、17 项去重、42 项大小/摘要与本机样本一致 |
| 密钥审计 | `python scripts/check-secrets.py --gitleaks .local/release-audit/go-bin/gitleaks.exe`：history/worktree/commit-metadata 均 0 findings；356 个公开源码文件。工具路径只属本机，不是公共安装要求；不据此保证无漏洞 |

源码样本的固定链接和摘要只保证取样可复核，不保证开发分支已公开发行，也不构成上游签名验证。没有复制下载的第三方实现到仓库。

## 未执行

- 未添加/编译 updater 或验签新依赖，未执行 core/server/desktop/Android 业务测试；纯文档变更不重复构建已有业务模块。
- 未部署 `/updates/`，未建立 APT/Flatpak/商店渠道，无 CI/Runner。
- 未执行 Windows、Linux、Android 的更新安装、生产证书覆盖、输入法保存屏障、升级失败恢复或密钥轮换实验。
- 未做独立交叉评审，未完成三端更新验收或 7 天试用。后续实现门槛见[提案场景](../spec/automatic-updates.md#u7-验收场景)。

## 交付边界

本轮提交只使研究和设计可审阅。新功能要通过后续实现 PR 和真实三端升级验证，不能从本记录推断当前客户端已支持自动更新。
