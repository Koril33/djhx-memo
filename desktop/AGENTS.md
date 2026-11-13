# desktop 协作规范

遵循 [全局规范](../AGENTS.md)，全部禁止事项适用于本目录。

- 前端归 desktop，不提前创建共享 Web 包；Rust 后端链接 core，UI 不实现同步逻辑。
- 托盘为首版必需；菜单可恢复窗口、创建笔记、同步和退出，不依赖 Linux 图标左键事件。
- 保存失败不误报完成；关闭隐藏与退出分别处理，托盘不可用时窗口必须可找回。
- Markdown 禁止危险 HTML/协议和外部图片自动加载，不能访问 Tauri 特权命令。
- 本地执行前端/Rust 检查，Windows/Linux 实测按任务范围记录；全局快捷键、分享、自启继续后置。维护者已授权 0.1.5 Windows 自动更新，按 spec/automatic-updates.md 实施；Linux 自动更新安装暂缓；维护者已授权本轮 Ubuntu 24.04 VM 的 DEB 构建与测试，按实际结果记录。
