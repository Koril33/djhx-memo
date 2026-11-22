# ADR 0021 桌面 Markdown 格式检查

- 日期：2026-11-22；状态：**0.2.2已接入，构建/单元与Windows原生探针通过，草稿附件已核验；Linux原生矩阵仍待验收**。
- 目标：[0.2.2 规范](../../spec/client-0.2.2.md)与[开发文档](../client-0.2.2-development.md)。

## 选择

桌面使用 markdownlint 的浏览器实现检查正文，以 CodeMirror 官方 lint 扩展展示问题；独立 Worker 离线运行，默认关闭，首版只提示。Android 原生编辑器不引入 JS 运行时，也不扩展本版 lint 范围。

| 直接依赖 | 锁定版本 | 许可与维护来源 | 用途 |
|---|---|---|---|
| markdownlint | 0.42.2 | MIT；[作者仓库](https://github.com/DavidAnson/markdownlint)、[固定版本元数据](https://www.npmjs.com/package/markdownlint/v/0.42.2) | 浏览器中的规则引擎；发布元数据要求 Node >=22，与现有开发工具链范围相容 |
| @codemirror/lint | 6.9.8 | MIT；[官方仓库](https://github.com/codemirror/lint)、[固定版本元数据](https://www.npmjs.com/package/@codemirror/lint/v/6.9.8) | 范围提示、问题列表及定位 |

2026-11-22 已只读核对 npm 的版本、MIT 和完整性元数据。上述精确版本已安装并锁定，Vite构建、单元及Windows WebView2探针通过；Linux WebKit原生验收仍单独记录。引入时核查安全公告与传递许可，不批量升级依赖树。

2026-11-22实施补充：上述两包已精确锁定；增加仅开发用`@types/node 22.20.5`（MIT，[DefinitelyTyped](https://github.com/DefinitelyTyped/DefinitelyTyped/tree/master/types/node)维护），为Vite配置使用Node标准URL API提供类型，避免自行实现Windows/UNC路径换算。markdownlint传递依赖的KaTeX按[上游安全公告](https://github.com/KaTeX/KaTeX/security/advisories/GHSA-238p-pmpm-9mq7)以override精确锁定0.18.2（MIT），消除新引入的旧版本告警，不改其他既有工具链版本。

Worker构建选用`decode-named-character-reference`包的官方无DOM default/worker入口；其browser入口在模块加载时访问document，实际WebView2探针已发现该问题。Vite仅固定该已锁定模块的入口，仍使用markdownlint的浏览器I/O替身；未加载Node文件系统、在线规则或启用unsafe-eval。Worker的传递依赖纳入实际构建模块清单与随包许可。

## 接入条件

- 先用现有 Node/Vite/CodeMirror 锁文件环境验证浏览器入口与 module Worker；不得把 Node 文件访问、动态 require 或在线 CDN 带入运行包。
- Worker 使用本地随包脚本；保持 CSP 的同源约束，不为接入启用 `unsafe-eval` 或任意远程脚本。若需明确 `worker-src 'self'`，限于本地 Worker 并经原生验证。
- 采用固定白名单规则，不加载磁盘/远程配置或任意用户 JS。诊断消息以普通文本显示，不插入规则返回的 HTML。
- 引擎仅在开启或手动检查时加载；Worker 可终止，结果按正文版本隔离。1 MiB 上限、128 KiB 实时阈值与 M2 保护一并验收。
- package.json、package-lock、随包 npm 通知与构建脚本按实际产物更新。不得只提交两个顶层 MIT 名称而遗漏引擎解析器等传递许可。

## 备选与取舍

不接入 CLI：客户端无需启动 Node 子进程。不将 lint 送到服务器：离线及正文隐私保持。不改造 Android 为 Web 编辑器：该范围与本轮产品选择不符。不自行实现一组声称兼容 markdownlint 的规则：避免维护规则差异。
