# 核心库本地验证

日期：2026-11-04；Debian 13 x86_64，Rust/Cargo 1.99.1，Go 1.27.3。属于 0.1.0 自用候选实现，正式发布仍须三端验收与连续 7 天试用。

## 本轮实际结果

- `cargo fmt --check`、`cargo clippy --locked --all-targets -- -D warnings` 通过。
- `cargo test --locked --lib`：19 项通过。覆盖请求幂等、LF 与乐观代次、短中文/字面量搜索、墓碑恢复、真实任务标记、事务失败回滚、冻结 outbox、旧确认与新输入、两条独立待发分支、账号隔离导入、ZIP 和仅回收站导出、迁移校验/备份/高版本拒绝、根目录实例锁、凭据条件撤销、Retry-After、恢复持久化失败后可再次同步、窗口隐藏后显式同步唤醒，以及已确认版本的 Pull 回放不使 UI 续写代次失效。
- 真实 Go 进程联调由另一协作者运行并记录于 [核心交叉评审](reviews/core-review.md)，使用 [同一组声明式场景](../spec/conformance/fixtures.json)。普通 `cargo test` 会忽略这些依赖外部二进制的测试，须显式执行下面命令。

```sh
mkdir -p .local/server
(cd server && go build -trimpath -o ../.local/server/memo-server ./cmd/memo-server)
DJHX_SERVER_BIN="$PWD/.local/server/memo-server" \
  cargo test --locked --manifest-path core/Cargo.toml --test server_integration -- --ignored --nocapture
```

## 已修复的交叉评审问题

迟到的旧 token 401 仅能移除相同会话的凭据；登录、加载凭据、注销和恢复串行，平台删除失败时也保留持久注销标记。同步等待始终释放运行标记，恢复数据库写入失败不改变登记中的原空间。推送确认先校验操作、笔记和版本，异常响应保留原冻结操作。冲突未知结果恢复后不能覆盖较新的远端副本或另一条本地待发分支。

429/503 遵守 Retry-After，其他临时失败指数退避；永久外层协议错误停止自动循环，用户可手动重试。单笔记永久错误保留 outbox 与正文、阻止该笔记重发，同时允许其他笔记同步。`sync(wait=true)` 把可报告的网络/鉴权结果放入 SyncInfo，供前台与后台共用。显式排队的同步可在窗口隐藏时执行一次，SSE/周期检查仍受前台状态控制。

## 边界

未把单元测试等同于三端真实使用。Android arm64 编译不等于物理设备验收；SSE 阻塞连接关闭与跨平台强制断电仍需压力验证。根目录锁在活跃后台请求完全结束前保持，进程退出由操作系统释放；应用应复用单例而非在一个进程内重复创建该目录的实例。
