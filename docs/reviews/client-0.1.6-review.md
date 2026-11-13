# 0.1.6 数据与平台独立交叉评审

日期：2026-11-13。维护者明确允许两个独立子代理评审，范围为相对 `f9f21c7` 的 core 列表操作、Markdown 导入与两端 UI 增量；未读取签名材料或真实笔记，未修改实现。源码修正后由原评审者再次复核，不能把本记录当作三端安装测试。

## core 结果

复核后未发现阻止合并的确定缺陷。批次逐项持有可写空间锁并使用 Immediate 事务，严格原 ID/generation，不沿 redirects 误恢复冲突副本，遵守 pending/purged 屏障。撤销保留冻结删除，旧确认后产生新 restore op_id 与正确 base_rev；导入复用 local_requests 幂等创建，编码/大小检查在创建前完成，不改 wire 协议或已发布迁移。

发现并修正批量/导入返回 NoteView 固定忽略 blocked outbox 的状态误报，现读取真实 `db::blocked`。补齐新错误码及公共 API 文档。新增回归覆盖部分失败/重复ID、新代次/永久删除屏障、redirect/只读恢复空间、冻结删除确认后恢复、BOM/CRLF/UTF-8/边界/幂等。

独立实跑 `cargo test --offline --locked --manifest-path core/Cargo.toml --lib`：43通过、0失败、0忽略；`cargo clippy --offline --locked --manifest-path core/Cargo.toml --all-targets -- -D warnings`：通过。新增真实HTTP双客户端导入/批量删除/撤销场景已读审，实际联调由发布执行者另记。

## 平台结果

复核后此前报告的问题均关闭，未发现阻止合并的新增平台缺陷：

- Windows 避免 busy 在输入法组合结束前使 CodeMirror 只读；完整批量/导入流程置于最终保存和输入屏障内。
- 两端确定导入校验错误清理请求缓存，允许重新选择；Android 完成、取消和读失败回调均校验原请求令牌，防止延迟文件进入新账号请求。
- Android 合并撤销组按ID保留最新删除代次；忙碌时点击撤销捕获原组并等待处理，不吞掉请求或恢复后来无关组。
- 搜索、取消、全选、复选框和返回在忙碌期间保持操作范围稳定；新建清理适用选择状态，多选顶栏替代普通顶栏。
- 左滑避让两侧返回边缘，按初始方向决定消费；slop前不交接指针，后续累计实际位移，速度不能越过50%距离门槛。
- Linux 原生文件过滤包含 `.md/.MD/.Md/.mD`。

平台评审为源码复核，未由评审者运行仪器测试或三端升级。实际结果与设备/工具限制见[0.1.6验证记录](../releases/0.1.6-validation.md)。

## 维护者UI反馈后的补充复核

维护者要求提升新交互的UI/UX后，平台评审者再次只读核对了上下文栏、SVG选择标记、行内Checkbox、三态全选、原目标导入确认和非阻塞反馈。发现并修正Undo与普通Snackbar互相关闭、旧成功提示残留等问题；选择行保留selected语义。Android为遵守既有LargeClass检查，将文件请求/不确定回执拆入MarkdownImportSession，未扩大告警抑制；评审者确认原token、空间、明确错误清理、未知回执保留和flush/composition屏障保持。该补充复核未实跑新版UI或升级安装，测试结果仍见发布记录。
