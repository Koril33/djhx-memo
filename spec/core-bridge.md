# v1 平台桥接接口

本文件细化 [core-api](core-api.md) 的语言包装，不改变同步协议。Rust 导出 UniFFI 对象 `MemoApp`，桌面 Rust 后端直接使用同一对象。所有调用在平台的后台线程执行。

## 入口与凭据

构造：`MemoApp(root: String, credentials: CredentialStore, allow_insecure_http: bool)`。`root` 由平台选择，不能来自笔记正文或前端任意路径。发布构建传入 false；测试/调试构建可以显式传入 true。

`CredentialStore` 回调提供 `load(profile_id) -> String?`、`save(profile_id, token) -> bool`、`remove(profile_id)`；save=false 表示只驻留进程并向用户提示。token 不返回 UI。桌面使用系统 keyring，Android 使用 Keystore 加密后持久化。

`invoke(request_json: String) -> String` 接收 JSON 对象，以 `action` 区分操作。返回 `{"ok":true,"data":...}` 或 `{"ok":false,"error":{"code":"...","message":"...","retryable":false}}`。错误不含正文、密码或 token。

## 请求与结果

请求参数均与 action 同级；省略字段采用表中默认值。所有 generation/rev/seq 为十进制字符串。

| action | 参数 | data |
|---|---|---|
| state | 无 | `{profile,profiles,event_seq,sync,credential_persistent,local_import_count}` |
| list | query="", deleted=false | NoteView 数组 |
| get | id | NoteView |
| create | content, request_id（UUID） | NoteView；重试同 request_id 返回同笔记 |
| save | id, expected_generation, content | NoteView |
| set_deleted | id, expected_generation, deleted | NoteView |
| toggle_task | id, expected_generation, line（从 0 开始的源码行） | NoteView；核心只修改 CommonMark 任务标记，排除代码块 |
| export | include_deleted=false, deleted_only=false | `{filename,bytes_base64}`，ZIP 由核心生成；deleted_only=true 仅导出回收站，优先于 include_deleted |
| login | server_url, username, password, device_name | `{profile,import_count,credential_persistent}` |
| register | 同 login，加 invite_code | 同 login |
| switch | profile_id | ProfileInfo；本机 id 为 `local` |
| import | source_profile_id="local", ids（可省略，表示全部 active） | `{imported}`；当前目标必须为账号空间 |
| logout | 无 | `{remote_revoked}`；即使离线也清除凭据、回本机空间 |
| devices | 无 | 设备数组，字段同 OpenAPI Device |
| revoke_device | id | `{revoked:true}` |
| sync | wait=false | SyncInfo；默认只排队，wait=true 供后台任务和测试等待本轮完成 |
| foreground | active=true | `{active}`；前后台与 WorkManager 共用实例 |

ProfileInfo：`id,kind,label,server_url,user_id,username,device_id,read_only`，非适用字段为 null。kind 为 local/account/recovery。

NoteView：`id,content,title,generation,deleted,created_at,updated_at,conflict,save_state,sync_state`。conflict 结构同 OpenAPI；save_state 返回 saved 只表示事务已提交。sync_state 为 local/pending/synced/blocked。另可返回 `redirected_from` 供冲突后焦点迁移。

SyncInfo：`state,pending,last_success,error`。state 为 local/pending/syncing/synced/offline/auth_required/recovery_required/error；error 为可空的稳定错误对象。时间为 RFC3339 字符串或 null。

sync 的 wait=true 仍返回成功桥接结果与 SyncInfo；认证、网络和协议失败记录于 SyncInfo.error，本地存储不可读等无法构造状态的错误才返回桥接错误。后台任务据此区分需要重试和需要重新登录。

## UI 责任

UI 保留未提交编辑缓冲，300ms 防抖且最长 1 秒提交；切换、失焦、后台与关闭前等待保存。保存返回新 generation 后才能显示已保存。刷新列表、事件或同步状态不能覆盖未提交缓冲；LOCAL_GENERATION_CONFLICT 时保留缓冲并提供重试/复制救援。

create 的同 request_id 重试返回该笔记当前 NoteView，可能与本次发送缓冲不同（例如首次已提交但 UI 未收到确认，期间用户继续输入）。UI 只能按返回的 content/generation 确认本机基线；缓冲仍有差异时继续 save，不能将本次请求正文直接标为已保存。关闭/退出确认期间应先完成输入法组合并建立输入屏障，提交最终缓冲后再确认生命周期操作。

笔记读写、列表、导入和导出请求同时携带 UI 已读取的 `profile_id`。当前空间已变化时返回 PROFILE_CHANGED，不把旧空间缓冲写入新账号。state 可返回 `redirects` 对象（原笔记 ID → 冲突副本 ID）；UI 在 event_seq 更新时消费一次映射并迁移焦点，不能因此覆盖未提交缓冲。

state 的 event_seq 变化提示重新读取；前台可定期调用 state。不得在 UI 生成 base_rev/op_id、裁决冲突或从显示时间推导同步状态。导出 ZIP 的目标选择和原子落盘由平台完成。
