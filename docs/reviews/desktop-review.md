# 桌面端独立交叉评审

- 日期：2026-11-04（Asia/Shanghai）
- 评审者：服务端实现协作者；未编写 desktop 业务源码
- 范围：`desktop/src/{App,Editor}.svelte`、`lib/{editor-session,preview-state,markdown,bridge,types}`、Tauri Rust 后端、权限/CSP 与导出/托盘生命周期
- 依据：[桌面协作规范](../../desktop/AGENTS.md)、[产品](../../spec/product-v1.md)、[core 桥接](../../spec/core-bridge.md)

## 发现与修复复核

| 发现 | 位置和影响 | 实现者修复与本轮核验 |
|---|---|---|
| P1：最后 flush 与隐藏/退出确认之间仍可输入 | `App.svelte` memo-lifecycle 监听完成 flush 后异步调用 native finish；窗口仍可编辑，新输入未包含在保存确认中 | `EditorSession.finishLifecycle` 等 IME 完成后建立 inputLocked，持续到 native 确认；App 的 lifecyclePending 同时禁止新操作、轮询与重复处理。独立复核并运行延迟 native ack 时输入不能越过屏障的回归 |
| P1：未知创建结果后续写被误标已保存 | `EditorSession.drain` 将重试 snapshot 作为 committed，而 core 对相同 local request ID 会返回最初已提交正文；重试期间续写可能未写入数据库 | 使用响应 Note.content 作为真正已提交基线；若当前缓冲更新则继续用返回 ID/generation 保存。回归模拟 A 已提交但响应失败、续写 AB、同 ID 重试返回 A，断言随后 save AB，独立执行通过 |
| P1：IME 组合期间自动刷新抬高本地基准 | `acceptRemote` 在组合未完成、尚无普通 edit 回调时可能接收新 generation | 实现者增加 composing 保护，flush 等 compositionend；代次冲突继续保留原缓冲，允许明确另存副本。相关单元回归独立执行通过；没有以此替代系统中文输入法实测 |
| P2：旧预览行号套到新正文 | `handlePreview` 先读取任务 line，再 flush 后使用最新 generation，未确认行号所对应源码仍是渲染快照 | PreviewState 绑定 source/profile/documentKey/noteId 和 DOM version；flush 后不匹配则拒绝旧任务点击并要求重试。两个回归覆盖换行移动任务、旧 DOM version、切换空间/文档和空草稿首次创建，独立执行通过 |
| P2：冷启动关闭可能丢失生命周期事件 | Rust emit_to 不代表前端已有 listener，过早建立 pending 后可能一直不再发事件 | frontend_ready 在注册监听后握手；未就绪不建立 pending，已有 pending 可安全重发，前端忽略同时重复处理。已复核代码，未在实际 WebView 做大量冷启动关闭压力验证 |
| P2：可见但未聚焦窗口停止前台同步 | 前台状态原同时要求 document.hasFocus，失焦即停 SSE/协调器 | 改为可见状态，失焦仍立即 flush；真正隐藏由 native 生命周期传 false。已复核代码；三端可见延迟的 UI 实测仍待单独记录 |

生命周期屏障还核对了文档切换入口：`memo-new` / `memo-sync`、Ctrl/Cmd+N/S、列表选择、全部/回收站、设置中的空间切换/登录/注销/导入和救援进入空间均经过 `run`。该入口在 `busy || lifecyclePending` 时立即返回；生命周期回调在第一个 await 前同步设置 lifecyclePending，再由 inputLocked 覆盖最终保存至 native 确认，因此屏障覆盖这些操作，而非只限制文本框。

上述修复没有将同步/冲突裁决放入 UI。前端只持有未提交缓冲与 core 返回的本地 generation；profile_id 跟随 UI 所读取空间，空间变化时原输入保留并提供救援导出。

## 正向核对

- 保存失败阻止常规切换、隐藏和退出，不展示成功；300ms 防抖及连续输入 1 秒提交上界有确定性计时测试。已发出的提交与后续输入串行，迟到列表结果不能回退到较旧 generation。
- generation 冲突不覆盖权威内容，另存新笔记需要明确操作；自动冲突焦点迁移在读取新目标后再次检查输入/空间状态，不能覆盖未提交缓冲。
- Markdown 原始 HTML 转义，图片不自动加载，链接只允许无凭据的绝对 HTTP(S) 并交系统浏览器；预览 HTML 不含来源注入的 IPC 属性。CSP 限制脚本、远程连接、iframe、form 和 object。
- 前端没有直接操作 SQLite 或读取明文 token。系统 keyring 回调只在 Rust/core，凭据服务失败由 core 选择进程内凭据并在 UI 提示。
- ZIP 由 core 一致读取生成，Rust 选择目标后使用同目录临时文件、sync_all 和不覆盖的发布方式；目标已存在或文件系统不支持时明确失败。救援正文路径只由原生文件选择器确定。
- 单实例插件配合 core 根锁；托盘菜单可打开、新建、同步、退出。Linux 初始化或托盘 host 检查失败时进入普通窗口模式，关闭前先保存，未依赖图标左键才能恢复。

## 本轮实际执行与边界

复用本机 Node `22.23.2`，路径 `$HOME/.local/share/pi-node/node-v22.23.2-linux-x64/bin`；没有另装依赖版本。独立执行：

```sh
cd desktop
npm test
npm run check
```

在包含上述修复的工作树中，Vitest **16 项通过**，Svelte/TypeScript **0 errors、0 warnings**。测试覆盖编辑缓冲、未知创建确认、输入屏障、IME 代次、HTML/链接/图片限制和任务源码快照。测试是代码级验证，不等同真实 Tauri WebView、系统输入法或安装包验收。

本评审未亲自执行 Windows/Linux 安装、GNOME/Wayland/X11 托盘、中文输入法、WebView2 缺失安装、原生文件对话框、冷启动关闭压力、系统磁盘满或 30 次完整 UI 延迟测量。实现者实际平台记录见 [desktop-validation](../desktop-validation.md)。core+Go 的自动 SSE 测量只证明共享协调器链路，见 [core-review](core-review.md)。未完成的三端清单与连续 7 天试用仍保留未完成状态。
