# core

Rust 本地优先核心，负责 SQLite 存储与迁移、持久 outbox、客户端同步状态机、搜索、回收站、导出和空间隔离。Android 经 UniFFI 调用，Tauri Rust 后端直接链接。

已实现可构建的 `djhx-memo-core` 0.1.0；依赖锁定于 Cargo.lock，SQLite 随库构建。平台入口为 `MemoApp`，JSON 调用见 [桥接契约](../spec/core-bridge.md)，不向 UI 返回 token。

```sh
cargo fmt --check
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked
```

真实 Go 联调单独执行，不能用被忽略的测试计数宣称联调通过：

```sh
cd ../server
go build -trimpath -o ../.local/server/memo-server ./cmd/memo-server
cd ../core
DJHX_SERVER_BIN="$PWD/../.local/server/memo-server" cargo test --locked --test server_integration -- --ignored --nocapture
```

数据根目录只允许一个实例使用；每个空间使用独立 SQLite 数据库。恢复空间保留原文件并只读。平台需将缓冲提交与窗口/生命周期操作串行，网络失败不会使本地保存成功状态失效。发布构建仅允许 HTTPS；调试环境的 HTTP 需要构造时显式允许。

[核心验证记录](../docs/core-validation.md)、[独立评审](../docs/reviews/core-review.md)、[依赖决策](../docs/adr/0005-core-and-build-dependencies.md) 与 [第三方许可证](THIRD_PARTY_NOTICES.txt) 随源码提交。许可证清单可在已有 Cargo 缓存时执行 `python3 ../scripts/cargo-notices.py Cargo.toml THIRD_PARTY_NOTICES.txt` 重建。

先阅读 [产品规范](../spec/product-v1.md)、[同步协议](../spec/sync-protocol.md)、[模型](../spec/data-model.md)、[UI 接口](../spec/core-api.md) 和 [一致性场景](../spec/conformance/scenarios.md)。服务端负责权威版本与冲突裁决，core 负责保存本地修改并落实客户端流程。
