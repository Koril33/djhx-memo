# HTTP 与 SSE 接口说明

机器可读定义见 [openapi.yaml](openapi.yaml)，使用 OpenAPI 3.1；该文件采用 JSON 语法（兼容 YAML 1.2），可用标准 JSON 工具检查。

## 1. 通用约定

生产服务器根 URL 可配置，路径统一 `/v1`；默认拒绝跨域。JSON 请求/响应均为 UTF-8，正文文本行尾 LF。seq/rev 等 64 位计数均为十进制字符串。字段范围见 [模型](data-model.md)，错误见 [错误码](error-codes.md)。

除注册、登录、healthz 外均需 Authorization: Bearer token。响应使用 Cache-Control: no-store；请求 ID 置于 X-Request-ID，JSON 错误另带 request_id。HTTP 明文调试、TLS、限流和账号隔离见 [认证规范](auth.md)。

## 2. 接口清单

| 方法与路径 | 成功结果 | 说明 |
|---|---|---|
| GET /healthz | 200，status=ok | 无敏感数据；无法正常使用数据库时 503 |
| POST /v1/auth/register | 201，Session | 邀请码注册并登录首设备 |
| POST /v1/auth/login | 200，Session | 签发/替换本设备 token |
| POST /v1/auth/logout | 204 | 吊销当前 token |
| GET /v1/devices | 200，devices 数组 | 仅本账号，包含吊销/过期信息 |
| DELETE /v1/devices/{id} | 204 | 吊销本账号设备 |
| GET /v1/sync/status | 200，epoch/head_seq/protocol_version | 核对恢复代次和当前同步位置 |
| POST /v1/sync/push | 200，逐项 results | 外层校验失败不执行；其余允许逐项成功 |
| GET /v1/sync/pull | 200，changes/next_cursor/until/has_more | 受固定 until 限制的不可变变化日志分页 |
| GET /v1/events | 200，text/event-stream | 更新信号，不能代替 Pull |
| GET /v1/export | 200，application/zip | 服务端当前快照，不包含客户端尚未上传内容 |

客户端常用导出来自本地 core，离线可用；云端导出只是额外救援接口。云导出默认不含墓碑，include_deleted=true 时包含，使用 ID 文件名、manifest 和一致读取事务。导出过大或失败不得把不完整 ZIP 当成功文件；客户端先写临时文件，验证成功后改名。

## 3. 请求限制与结果

Push 最多 50 项、JSON 编码后 ≤8MiB，每条正文 ≤1MiB UTF-8 字节；单项大小验证不能仅依赖 JSON Schema 的 maxLength。外层未知字段拒绝，解析每项的未知字段/不合法字段返回 INVALID_REQUEST 结果，不执行该项。不能识别 op_id 的错误项返回 op_id=null，其他字段合法时应保留其 ID。

Pull 的 epoch 和 cursor 必填；until 首次可省略；limit 为 1～100，默认 50。一次响应编码后 ≤8MiB，返回较少项时仍正确设置 has_more。结果包含完整 Note 快照，deleted=true 仍保留正文和元数据。空变化页、初始 cursor=0、并发分页语义见 [协议](sync-protocol.md)。

JSON 响应可以新增可选字段，客户端忽略未知响应字段；服务端仍严格校验请求。JSON 解析和 UTF-8 字节校验在写入前完成。

正文必须已统一 LF；服务端收到含 CR 的正文返回逐项 INVALID_REQUEST，不静默改写后参与幂等指纹计算。

## 4. SSE

原生客户端通过可携带 Authorization 头的流式 HTTP 连接，Accept: text/event-stream。建立连接后立即发送 `event: sync`，data 为 `{"epoch":"UUID","head_seq":"42"}`；每次账号数据事务提交后发送同类信号。信号不含正文，不使用该 seq 推进本地 cursor。

每 15 秒发送注释心跳。断线后重连采用协议退避，并先/同时触发 Pull；Last-Event-ID 不承担补偿语义，无需保存。多次通知可合并。设备吊销后关闭连接；新建连接遇到 401 停止重连直至登录。每个设备最多一个有效 SSE 连接。

反向代理禁用 SSE 缓冲，空闲超时大于心跳周期；前台 30 秒拉取兜底仍然保留。Android 后台不维持常驻长连接，回前台重连并补拉。
