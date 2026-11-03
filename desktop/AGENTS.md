# desktop 协作规范

遵循 [全局规范](../AGENTS.md)，其中的全部禁止事项同样适用于本目录。

- Rust 后端链接 `core`；UI 不实现同步或冲突逻辑，与 `web` 复用前端组件。
- 重点验证托盘、快捷键、关闭前落盘，以及 Linux 的 fcitx / ibus 和 Wayland 行为。
- 工程初始化后运行 Rust 与前端对应的格式化、静态检查和测试；Windows、Linux 均需验证。
