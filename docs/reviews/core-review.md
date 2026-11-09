# core 独立交叉评审与服务端联调

- 评审日期：2026-11-03～2026-11-04（Asia/Shanghai）
- 评审者：Go 服务端实现协作者；core 实现者为另一位协作者
- 范围：`core/src/{lib,model,db,sync}.rs`、版本化迁移、公开 MemoApp 桥接、账号/凭据隔离、真实 Go 服务联调
- 分支：`codex/v1-implementation`；服务端基线 `9758664`，设备吊销竞态修复 `ab1886c`
- 本评审没有修改 core 业务源码；回归用例在 [server_integration.rs](../../core/tests/server_integration.rs)

## 发现与修复复核

| 发现 | 具体位置及影响 | 修复和证据 |
|---|---|---|
| P1：丢失冲突响应后覆盖已拉取的新副本 | `db::acknowledge` conflict 分支保留较新 shadow，却无条件用旧发送正文重写 visible target；cursor 已推进时可能永久显示旧内容 | 按目标现有 shadow 与独立 pending 分支合并；无后续本地代次时采用较新权威副本。真实服务代理丢弃冲突响应、其他客户端升级副本后重试，`lost_conflict_acknowledgement_preserves_newer_remote_copy` 通过 |
| P1：无法确认的同一操作重复 10,000 次 | `sync_round` 对缺原笔记 shadow、畸形成功确认等错误继续读取相同 outbox | conflict 确认前先 Pull；非服务端逐项 error 的确认错误立即结束本轮。复核代码，真实冲突与丢失响应场景通过；没有把未注入的所有畸形响应宣称为黑盒已覆盖 |
| P1：迟到旧 401 清除新登录 | `api` / `synchronize` / SSE 无条件清除 profile 凭据；同设备重登后旧请求能注销新会话 | 请求捕获具体 token，仅在当前 token 仍相同时清除；加载、登录、退出、恢复使用 registry 串行；过时请求返回可重试 SESSION_CHANGED。`delayed_old_401_preserves_reauthenticated_session` 用真实 Go 旧 token 401 验证新 token 保留且后续同步成功 |
| P1：异常确认删除可靠待发送记录 | `db::acknowledge` 未核对 note_id，接受 rev=0 或非法 seq | 核对 op_id/note_id、正计数、accepted=base+1、conflict_rev=1 后才确认。复核事务路径；实现方另有直接确认测试，黑盒仅覆盖正常协议和真实重试 |
| P1：恢复早退遗留同步锁 | `synchronize` 的 fallback recover 使用 `?` 早于 running 清理；后续 wait 可能永久等待 | SyncLease 的 Drop 统一移除 running 并通知 Condvar，覆盖所有 Result 早退。复核锁序及 RAII；真实 epoch 恢复通过，未在此集成文件注入 registry 磁盘失败 |
| P1：搜索/列表 SQL 空 ESCAPE | `db::list` Rust 普通字符串把反斜杠转义掉，非空笔记列表失败 | SQL 改 raw string，长查询使用 FTS 候选加字面过滤；共享 11 场景真实 Pull 后列表全部通过 |
| P1：凭据删除失败导致退出后复活 | `token` 可能在重启时重新加载系统残留 token | registry 持久化 logged_out，成功登录才解除。模拟 CredentialStore.remove 静默失败、离线退出再重启，旧空间保持 auth_required，笔记仍可读取 |
| P1：自身相同 rev 的 Pull 使 UI generation 失效 | `apply_page` 对已确认的同一权威版本仍递增 generation，持续输入可能收到虚假的本地代次冲突 | 复核 `0549c3e`：相同 rev 仅规范化时间/来源元数据，正文/deleted 不同则整页回滚；真正新 rev 才推进可见 generation。实现者增加确认→Pull→沿用原 UI generation 续写回归，最终真实联调通过 |
| P2：wait=true 提前返回、同根多个协调器 | `synchronize` running 已存在时直接成功；Engine 未锁数据根 | Condvar 等待前一轮结束；数据根持有 File.try_lock。代码复核及应用重启联调通过；未做大量并发 wait 压力测试 |
| P2：自动重试忽略 Retry-After/永久错误 | 原调度只自行计算退避，编辑触发绕过限制 | 当前按 profile 保存最早重试时刻，并阻止永久错误自动循环；手动同步能重新触发。429/503 均记录 Retry-After，复核实现；退出生命周期边界见下文 |

普通 accepted 确认保留 H>G 的新代次；Pull 只推进 shadow，不抬高本地 pending 的 draft_base_rev。冲突原笔记和副本的本地分支都在同一 SQLite 确认事务处理。所有 HTTP 请求与 token 解析绑定固定 Profile，切换空间不把旧任务改绑到新账号。

恢复先建立新数据库，再原子持久化 registry；旧数据库不清空，改只读 recovery，复制导入产生新 ID，来源代次映射在目标事务内保持幂等。迁移校验旧脚本、高版本先拒绝、升级前备份、WAL/foreign_keys/FULL 配置与业务写入隔离。事务失败与升级测试由实现方的 core 单元测试记录，本报告不重复声称自己执行了未运行的测试。

## 实际执行的黑盒验证

本机 Go 1.27.3、Rust 1.99.1，服务二进制重建到持久的 `.local/server/memo-server`。没有新增测试依赖；真实 Go 子进程、独立临时数据库和内存 CredentialStore，故障场景关闭前台自动协调后显式同步；另有隐藏窗口手动排队和真实前台自动 SSE 性能测试。临时 HTTP 代理仅用于确定性地丢弃或延迟响应，不伪造业务成功结果，不打印 token/邀请码/密码。

```sh
# 仓库根目录
(cd server && go build -trimpath -o ../.local/server/memo-server ./cmd/memo-server)
DJHX_SERVER_BIN="$PWD/.local/server/memo-server" cargo test --manifest-path core/Cargo.toml --test server_integration -- --ignored --nocapture
```

2026-11-04 使用包含 `ab1886c` 吊销修复的服务端再次运行，在 `0549c3e` core 上实际结果：7 passed、0 failed；独占集成 target 的 `cargo clippy --test server_integration -- -D warnings` 通过。这些测试默认 ignored，因为需要显式提供真实服务端二进制；普通 cargo test 的 ignored 不代表联调通过。

1. `shared_fixtures_over_real_http`：消费同一 [fixtures.json](../../spec/conformance/fixtures.json) 的 11 例，逐项真实 HTTP 验证裁决并通过 core Pull/本地列表/NoteView 断言权威内容和墓碑状态，校对账号 head_seq。
2. `offline_conflicts_response_loss_and_epoch_recovery`：两个独立 core 离线编辑冲突、删除后离线编辑、服务已提交但创建响应丢失、重启复用原 op_id、恢复快照并轮换 epoch、旧空间只读保全、选择性新 ID 导入与重复导入幂等。
3. `lost_conflict_acknowledgement_preserves_newer_remote_copy`：已提交冲突响应丢失，另一客户端编辑副本，再重启重试；不回滚已拉取的较新副本。
4. `account_switch_revocation_and_failed_credential_removal`：账号隔离、切换后旧 profile 缓冲被拒、本机来源保留、另一设备吊销、离线编辑与重登继续同步、凭据删除静默失败后的离线退出/重启。
5. `delayed_old_401_preserves_reauthenticated_session`：阻塞旧 token status 请求，同设备重新登录后才转发旧请求；服务器真实返回 401，客户端保留新凭据并继续同步。

6. `hidden_manual_sync_uploads_queued_content`：foreground=false 时通过公开桥接排队一次同步，观察真实 Go 权威 head/正文提交，不调用等待式同步来代替后台协调器。
7. `foreground_automatic_sse_converges_within_latency_target`：两套独立 MemoApp 启用真实前台协调和 SSE，连续 30 次不超过 64KiB 的本地提交，B 端完全依赖自动协调可见，不手动触发 B 同步；P95 断言 ≤5 秒。只输出测量数值，不打印正文。

## 自动 SSE 链路测量

环境：本机 Intel Core Ultra 5 125H（18 逻辑 CPU）、Linux `6.12.113+deb13-amd64`、Rust 1.99.1、Go 1.27.3；调试 HTTP 回环、单账号两设备、一条笔记连续 30 次约 64KiB 更新。起点为 A 的本地事务成功返回，终点为 B 公开桥接可读到对应内容。没有在采样循环调用任一端手动 sync；按 nearest-rank 取第 29/30 个排序样本作为 P95。

该测量仅证明本机 core+Go 的自动协调/SSE链路，不包含 Tauri/Compose 渲染、物理设备、云网络、TLS开销或1000条笔记规模；不能用它代替三端UI性能验收。最终完整样本记录（毫秒，升序）：

```text
272.403, 288.039, 290.797, 292.905, 293.302, 293.695, 294.605, 294.745,
294.953, 295.520, 295.558, 295.663, 295.775, 296.040, 296.214, 296.330,
296.390, 296.685, 297.043, 297.044, 297.143, 297.896, 298.090, 310.253,
312.123, 588.420, 590.896, 606.647, 610.147, 1127.941
```

30 次样本：**P95 610.147ms，最大 1127.941ms**，通过 ≤5000ms 断言。完整 7 项联调也已在最终 core 修复后通过；为保留全部 30 个采样数值，测试增加数值输出后单独再执行此性能项，得到上述记录。

## 尚未覆盖的边界

- SSE 线程在读取期间持有强 Arc<Engine>，丢弃最后 MemoApp 后根目录锁可能延迟到流超时才释放。当前根锁阻止两个协调器并行，但没有显式 shutdown 或高频销毁/重建压力验证；桌面/Android 生命周期应实测。
- Retry-After 的数字与日期解析、429/503 deadline 处理已复核；未在此集成文件等待真实长窗口或进行大量限流调度压力验证。
- 云端 HTTPS、自签/公网证书、平台安全凭据库、三端后台与 UI 行为、三端 UI 的性能 P95、7 天自用均不由这些测试证明。Debian 13 VM 的独立服务部署演练见 [服务验证](../server-validation.md)。
