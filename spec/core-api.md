# UI 与 core 的接口边界

0.1.4 增量以[单条彻底删除与协议 2 契约](permanent-deletion.md)为准，替代下文旧基线中不提供永久删除及保留正文的对应范围。普通删除/恢复行为不变；永久删除不生成冲突副本。

状态：语言无关的接口契约；Rust/UniFFI 和 Tauri 包装名称可遵循各语言风格，行为不得变化。具体类型定义随实现提交，本轮不生成绑定代码。

## 1. 接口分组

| 操作 | 输入/输出 | 要求 |
|---|---|---|
| open_profile / select_profile | 本机或服务器+账号标识 → ProfileInfo | 复核数据库路径和 schema；切换前 flush，隔离后台任务 |
| list_notes / get_note | profile、分页/回收站条件 → NoteView | 只读本地；列表稳定排序；分页游标由 core 生成 |
| create_note | profile、首份非空白正文、客户端本地请求 ID → NoteView | core 生成 UUID；同本地请求 ID 重试返回同一笔记，创建与幂等映射一起提交 |
| save_note | profile、note_id、expected_generation、content → 新 generation | 乐观并发校验；同事务保存 notes/索引/待发状态 |
| purge | profile、note_id、expected_generation → NoteView | 仅单条回收站；持久化请求、永久标记及错误见增量契约 |
| set_deleted | profile、note_id、expected_generation、deleted → 新 generation | 删除和恢复保留正文，过期 UI 不能覆盖更新内容 |
| search_notes | profile、literal_query、limit → 本地结果 | 字面搜索，短词兜底，过滤回收站；0.1.11 起扩展为跨空间搜索，签名见文末 [0.1.11 搜索扩展](#0111-搜索扩展) |
| export_notes | profile、范围 → 导出数据/结果 | 核心生成 ZIP 内容，平台层选择目标位置并原子写入 |
| register / login | 服务器、账号凭据、设备信息 → 账号空间 | 密码只短期使用；账号切换与本机导入分开 |
| import_local_notes | 源空间、目标空间、选择 → 进度与映射 | 目标事务幂等，保留来源，不跨账号复用映射 |
| logout / list_devices / revoke_device | 账号空间、设备 ID → 状态 | 按认证规范执行，不清库 |
| request_sync | profile → 已受理 | 合并触发，网络工作异步，禁止阻塞 UI 线程 |
| flush / shutdown | profile → 已提交或明确失败 | 不要求联网；失败保留内容，不误报保存成功 |
| list_recovery_notes / import_recovery | 恢复空间、目标账号、选择 → 新 ID 映射 | 不重放旧 epoch 操作，来源只读保全 |

NoteView 同时带 generation、正文、删除/冲突元数据、本地保存状态与云状态；UI 不自行推导 dirty 或生成 base_rev/op_id。expected_generation 不匹配返回可识别的本地并发错误，UI 保留缓冲并重新读取，不盲目覆盖。

## 2. 事件与线程

事件至少包含 profile_id、单调递增的本地事件序号和类型：NotesChanged、LocalSaveStateChanged、SyncStateChanged、ConflictCreated、AuthRequired、RecoveryRequired。可合并变更通知；事件只提示重新查询，不能成为唯一持久化数据来源。

事件回调交由平台调度到 UI 线程。订阅必须可取消，空间切换后旧事件不能更新新 UI。Android 前台和 WorkManager 共享同一空间协调器；多进程或双实例访问需数据库锁/单实例策略，不能并行发送同一笔记操作。v1 桌面同一用户只允许一个应用实例，第二次启动激活已有窗口。

核心拥有数据库事务、网络同步和重试；平台层负责安全凭据存取适配、路径选择、生命周期触发、窗口/托盘与 WorkManager。Android 经 UniFFI，Tauri Rust 后端直接链接核心；前端不得接触数据库文件或明文 token。

## 3. 错误与持久性

错误分类复用 [错误码](error-codes.md)。本地 generation 冲突以 LOCAL_GENERATION_CONFLICT 表达，可重读并保留草稿。异步取消不撤回已经提交的本地事务；UI 超时必须查询结果，不能假定保存未发生。

“已保存到本机”只由提交完成事件或返回值产生。无法写入数据库时，UI 持有未提交文本并允许复制/救援导出；core 保存失败不能通过触发云请求绕过。

## 0.1.6 列表与文件入口

`batch_set_deleted` 逐条以原 ID、原 generation 提交普通删除或恢复，返回各项成功的 NoteView 或稳定错误。不重定向冲突副本，不修改冻结 outbox；撤销也是新代次。`import_markdown` 在 core 校验单文件扩展名、UTF-8/BOM、规范化行尾及有界大小，再用 request_id 幂等创建。平台选择器携带原空间和请求令牌；重复回调不重建，空间变化停止。参数见[桥接接口](core-bridge.md)，交互见[0.1.6 规范](client-0.1.6.md)。


## 单篇导出文件名（0.1.10）

`markdown_export_filename(source: String) -> String`为公开UniFFI纯函数，供三端复用首行标题与文件名清理：过滤路径分隔符/控制字符和Windows保留名，stem最多180个UTF-8字节，保证单个`.md`后缀。函数不读写笔记、凭据或文件，不改变正文；平台捕获编辑快照后通过系统保存对话框写入完整UTF-8。取消/写入失败不表示导出完成；新接口不改变协议/schema。

## 0.1.11 搜索扩展

0.1.11 将 `search_notes` 从"单空间字面过滤"扩展为"跨空间本地全文搜索"，行为契约见 [client-0.1.11](client-0.1.11.md)，设计见 [搜索功能详细设计](../docs/search-design.md)。要点（与上表 `search_notes` 行是"扩展"关系，不改变 `list` 的稳定排序+单空间语义）：

- 输入：`query`（字面子串，可为空）、`scope{kind: current|all_local|account, space_ids?, include_deleted}`。
- 输出：`hits[] / truncated`；`SearchHit` 含 `note_id / profile_id / space_kind(local|account) / deleted / title / title_segments / snippet / segments / hit_count / score / updated_at / generation`。`segments`/`title_segments` 是已切分文本 `{text, hit}`，无裸偏移；`snippet` 为围绕首个命中的单片段。
- 只读本地、不返回全文；**全量召回（无 SQL `LIMIT` 预截断）→ 打分 → 排序 → 取 top 50**；`truncated` = 合并排序后命中数 >50 被截断。不做 offset 分页、不触发同步、不持全局 busy。
- 匹配语义延续 v1：字面子串、ASCII 大小写不敏感、其他 Unicode 按原字符、不解释为 FTS/正则/布尔；候选检索用 FTS5 trigram（≥3 字符）与字面 LIKE 兜底（<3 字符），不构造 FTS `MATCH` 语法。v1 全局搜索范围不含恢复空间。
