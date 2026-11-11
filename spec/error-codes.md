# 错误码与恢复行为

HTTP 错误结构为 `{"error":{"code":"...","message":"...","retryable":false},"request_id":"..."}`。message 供展示，可本地化，不参与程序分支。错误不得含正文或凭据。Push 内部错误使用同样的 code/retryable，但位于对应结果项，HTTP 仍可为 200。

| code | HTTP（外层） | 自动重试 | 客户端行为 |
|---|---|---|---|
| INVALID_REQUEST | 400 | 否 | 保留本地内容，修正字段；单项字段错误可在 Push 结果中返回 |
| NOTE_TOO_LARGE | 422 | 否 | 保留内容，提示正文超过 1MiB，不截断；Push 为单项 error |
| PAYLOAD_TOO_LARGE | 413 | 否 | 拆分批次；外层超过 8MiB 不执行 |
| AUTH_INVALID_CREDENTIALS | 401 | 否 | 注册/登录页提示凭据错误 |
| AUTH_REQUIRED | 401 | 否 | 暂停同步，保留本地，重新登录 |
| DEVICE_ID_UNAVAILABLE | 409 | 否 | 重新生成设备 ID，不泄露其归属 |
| INVITE_INVALID | 403 | 否 | 邀请码无效、过期或已用，统一提示 |
| USERNAME_TAKEN | 409 | 否 | 提示更换用户名 |
| NOT_FOUND | 404 | 否 | 设备等资源不存在，跨账号同样返回此码 |
| NOTE_NOT_FOUND | 422 | 否 | 保留操作和正文，不自动作为新建 |
| INVALID_BASE_REV | 422 | 否 | 基准超前，停止该笔记推送并报告 |
| OP_ID_REUSED | 409 | 否 | 操作标识与内容不符，保全诊断信息并阻止重试 |
| CURSOR_INVALID | 409 | 否 | 保全旧库并进入重新同步恢复流程 |
| SYNC_EPOCH_MISMATCH | 409 | 否 | 按协议保全旧库，禁止原操作自动重放 |
| PURGE_CONFLICT | 409 | 否 | 彻底删除未执行，刷新已恢复/变化的笔记并重新确认 |
| NOTE_PURGED | 409 | 否 | 原笔记不可恢复，不生成冲突副本；未提交输入可手动另存新 ID |
| PROTOCOL_UNSUPPORTED | 426 | 否 | 提示升级，本地继续可用 |
| RATE_LIMITED | 429 | 是 | 等待 Retry-After；登录不自动反复提交密码 |
| SERVER_BUSY | 503 | 是 | 退避重试，操作 ID 不变 |
| INTERNAL_ERROR | 500 | 是 | 保留操作，退避重试，以 request_id 排查 |

所有未说明的 5xx 视为临时错误，网络超时视为结果未知，原 op_id 重试。冲突是成功保存副本的业务结果 `status=conflict`，不作为 HTTP 409。already_deleted 是成功确认。

## 本地错误

| code | 含义 | 行为 |
|---|---|---|
| PURGE_PENDING | 正在等待彻底删除 | 禁止编辑和恢复，联网后确认 |
| LOCAL_STORAGE_FAILED | 数据库、磁盘或权限故障 | 不显示已保存；保留缓冲内容，重试或救援导出 |
| LOCAL_SCHEMA_TOO_NEW | 数据库版本高于应用 | 不写入、不降级，提示升级 |
| CREDENTIAL_STORE_UNAVAILABLE | 系统凭据服务不可用 | 本次 token 仅存进程内，说明重启需登录 |
| RECOVERY_REQUIRED | epoch/cursor 异常 | 保全旧空间、提供导出与恢复 |
| PROFILE_CHANGED | 操作关联的空间已切换 | 禁止把原任务应用到当前空间 |
| LOCAL_GENERATION_CONFLICT | UI 使用的本地代次已过期 | 保留缓冲并重读，禁止旧内容覆盖新代次 |
| EXPORT_FAILED | 导出未全部完成 | 不显示全部成功，允许重新导出 |
