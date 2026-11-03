# 数据模型与术语

状态：未发布的 v1 契约；字段表是逻辑约束，实施时通过版本化迁移建立实际 schema。

## 1. 通用类型与限制

- `note_id`、`op_id`、`user_id`、`device_id`、`sync_epoch` 均为 UUID v4，小写规范形式；不从 ID 推导修改顺序。
- `rev`、`seq`、`cursor`、`until` 为非负 64 位整数，HTTP JSON 和查询参数中使用十进制字符串，避免跨语言精度损失；无前导零（零本身除外）。
- `rev=0` 表示客户端尚无服务端版本，服务端实体从 1 开始。每个用户的 `seq` 独立、严格递增；`cursor` 是该用户已经应用的变化位置。
- wire 时间统一为 UTC RFC3339 毫秒字符串，展示时转换本地时区。服务端创建时间为首次接收时间；离线创建的本地时间仅用于同步前展示。时间不参与冲突决策。
- 正文最大 1,048,576 UTF-8 字节，允许空内容；新建纯空白草稿的产品行为见 [product](product-v1.md)。服务端校验正文为合法 UTF-8，不做正文 trim；客户端将 CRLF/CR 转为 LF。
- 设备名称 1～80 个 Unicode 字符；用户名、密码和邀请码约束见 [auth](auth.md)。

术语：note 为笔记；tombstone 为软删除状态；outbox 为持久化待发送操作；shadow 为最后已知权威笔记快照；generation 为本地每次保存递增的代次；epoch 为服务端恢复代次，正常重启保持不变。

## 2. 服务端实体

| 实体 | 关键字段与约束 |
|---|---|
| users | id；username 唯一；password_hash；created_at；禁止通过客户端传 user_id 选择查询范围 |
| invitations | id；code_hash 唯一；expires_at；used_by/used_at；邀请码仅可成功使用一次，事务中消费 |
| devices | id；user_id；name；token_hash；expires_at；last_seen_at；revoked_at；设备归属不可转移 |
| user_sync_state | user_id 主键；head_seq，分配序号与写入变化处于同一事务 |
| notes | 联合主键 (user_id,id)；content；deleted；rev；seq；created_at；updated_at；conflict 元数据 |
| changes | 联合主键 (user_id,seq)；变更后的完整 Note 快照；追加后不可修改，v1 不裁剪 |
| processed_ops | 联合主键 (user_id,op_id)；规范化请求指纹；完整结果；created_at；v1 不过期 |
| schema_migrations | 版本、校验与应用时间，已发布版本不修改 |

所有业务实体的关联、索引及写入均带账号条件。相同 note ID 可以存在于不同账号，跨账号查询只返回不存在。设备名变化不改变历史冲突记录的来源设备 ID。

完整 Note wire 字段：`id, content, deleted, rev, seq, created_at, updated_at, conflict`。普通笔记 `conflict=null`；冲突副本包含 `source_note_id, source_device_id, source_device_name, source_op_id, reason, detected_at`，reason 取 `stale_edit / stale_delete / edit_after_delete`。元数据由服务端生成并在后续编辑中保留，客户端不能注入或修改。

服务端更新 notes、分配序号、追加 changes、保存 processed_ops 结果必须原子完成。冲突只创建副本时，原笔记 rev/seq 不变，副本 rev=1，消耗一个新 seq。删除保留正文，恢复复用原 ID 并增加版本。

服务端和客户端启用 WAL、foreign_keys 和 busy_timeout；可靠性优先，事务使用适当的 FULL 同步持久性配置。具体连接池、锁和超时由技术验证确定，测试必须覆盖写入竞争、磁盘满和事务回滚。WAL 不代替备份。

## 3. 客户端空间与数据库

每个空间独立 SQLite：一个本机空间；每个“规范化服务器 URL + user_id”一个账号空间。不要用用户名作为存储隔离键。URL 去掉末尾斜杠、域名小写，只允许服务器根路径，v1 不支持 API 子路径部署；同一服务器换域名不自动认作同一空间。

| 实体 | 关键字段与约束 |
|---|---|
| notes | id；当前可见 content/deleted；generation；draft_base_rev；本地时间；冲突元数据 |
| note_shadows | id；最后已知的完整权威快照；干净状态下的新编辑以其 rev 为依据 |
| outbox | op_id；note_id；base_rev；content/deleted 不可变快照；frozen_generation；状态；重试信息 |
| sync_state | epoch；cursor；进行中的 until；最近成功时间；需恢复状态 |
| imports | source_profile_id、source_note_id、source_generation 联合唯一；target_note_id；目标账号由数据库本身限定 |
| local_requests | 本地创建请求 ID 唯一；结果 note_id；防止创建成功但 UI 未收到响应时重复建笔记 |
| notes_fts | 可重建搜索索引，与 notes 事务一致；本机空间也提供 |
| schema_migrations | 本地版本与应用时间 |

空间注册表只保存本地路径、服务器、账号 ID 和展示名，不保存密码。设备 token 存系统凭据库；不可用时仅驻留当前进程，重启需重新登录。不得降级到明文配置文件。Android 使用 Keystore 保护凭据，桌面使用系统凭据服务；具体库在实施时以 ADR 记录。

恢复空间使用独立 profile_id 和文件路径，以原账号、epoch 与保全时间关联；同一账号只激活一个正常同步空间，可以保留多个只读恢复空间，不能覆盖原文件来“重新同步”。

首次未同步编辑记录 draft_base_rev，Pull 更新 shadow 时不修改该基准；冻结操作使用此基准。只有本机自己的前序操作得到正式确认，才能将其后续编辑重定基准到该确认版本。每条笔记最多一个已经发送或可能已被服务端接收的冻结操作；后续输入保存在更高 generation 的 notes 中。未发送快照可合并，已经发送的快照不得修改，重试复用原 op_id。一个空间同一时刻只运行一个同步协调器，前台与后台触发应合并。

本机笔记无 shadow、outbox 和云 cursor。离线新建后又删除、且从未冻结发送的笔记只留本地回收站；恢复后再创建云端操作。曾经发送的创建请求即使响应丢失，也必须先确认原操作，再发送删除，不得当作“从未上传”。

## 4. 迁移与恢复边界

迁移前保全数据库，使用版本化脚本；升级测试包括从上一版本迁移、失败回滚以及备份恢复。旧版本应用发现高于自身支持的 schema 时只读提示升级，不自动降级。

备份恢复导致 epoch 改变时，不在旧数据库内清空 notes 或 outbox。先将旧库保全为可导出的恢复空间，再创建新副本全量同步。选中的旧内容以新 ID 导入恢复笔记，避免旧操作覆盖新历史，见 [协议恢复规则](sync-protocol.md)。

changes 的完整快照用于可靠增量同步，并不意味着 v1 提供历史版本 UI。v1 不自动清理 changes、processed_ops 或墓碑；观察存储增长，日后裁剪需另立契约。
