# 0.1.5 更新器 core 独立交叉评审

日期：2026-11-13；环境：Windows 原生 Rust/MSVC。审查原实现 `9d52aea`，并在整合分支 `codex/0.1.5-release-integration` 的 `31f7874` 之后工作树上复核本轮修复；最终交付提交由发布记录关联。

评审协作者未编写原更新器实现，先只读检查并用独立临时客户端复现问题。整合协作者修改实现和对应失败回归，评审协作者独立复核修复，并获授权新增所有空间 checkpoint 验证用例；没有修改既有断言或生产数据，没有读取发布私钥。本文仅对 core 范围给出结论，不代替平台安装评审。

## 范围与结论

检查 [安装屏障](../../core/src/update_barrier.rs)、[签名目录与下载器](../../core/src/update_catalog.rs)、[目录测试](../../core/src/update_catalog/tests.rs)，以及 `lib.rs` 的桥接入口和 `sync.rs` 的后台同步/SSE 接入；行为依据为 [U3/U4/U8](../../spec/automatic-updates.md)。

发现一项会重置防回放状态的阻断问题，修复及独立复现复核完成后，**本次检查的 core 范围内未发现剩余确定阻断项**。没有据此宣称完整安全审计或三端正式更新链路通过。

## 发现与修复

损坏的 `state.json` 启动后，更新器以 `UPDATE_STORAGE` 暂停更新；但 `installation_failed()` 原先会把它覆盖为 `UPDATE_INSTALL`，无候选的 `prepare_install()` 也可能覆盖错误。随后偏好设置只判断当前错误码，便可将默认 `sequence=0` 写回文件，丢失原防回放屏障。Android 重启恢复失败安装会话时可以进入该路径。

独立临时 Rust 客户端仅创建合成损坏状态并调用公开 API：`new → installation_failed → preferences`。修复前原文件被覆盖，探针退出码 2；修复后文件逐字保持、`UPDATE_STORAGE` 保持，退出码 0。没有网络、签名私钥或真实笔记参与复现。

整合修复使 `fail()` 保留已有 `UPDATE_STORAGE`，并在 `prepare_install()` 入口准确返回该错误。新增 `install_failures_cannot_clear_corrupt_replay_state` 检查安装失败、偏好、准备安装、稍后调用序列；独立复核确认 `run/fetch_catalog/preferences/defer` 的现有保护与修复共同阻止状态被重置。

## 实际验证

- 修复前实际运行更新相关 10 项测试，通过；上述独立公开 API 探针另外揭示了原测试未覆盖的问题。
- 新增 `update_checkpoint_preserves_all_spaces_and_frozen_outbox_and_recovers_from_busy_reader`：登记当前 local、非当前 account 和只读 recovery 三个空间，保持数据库连接打开并禁用自动 checkpoint。账号/恢复空间先冻结 outbox，再继续输入到更高代次。读事务固定旧 WAL 快照时，安装准备失败且自动解锁；释放读事务后，全部空间 WAL 截断为零，新的创建被 `UPDATE_BUSY` 拒绝。恢复并重开后，正文、代次、确认位置和冻结操作的 ID/原始快照保持，失败的创建没有生成笔记。该项独立实际通过。
- 修复后公开 API 探针通过；`cargo fmt --check`、`cargo clippy --offline --locked --all-targets -- -D warnings` 通过。
- 修复与新增验证后，`cargo test --offline --locked --manifest-path core/Cargo.toml` 的 **39 项单元测试通过**。常规命令中的 **9 项真实 HTTP 联调保持 ignored**，本评审没有将它们算作通过。

所有 Cargo 命令使用 `core/Cargo.toml`。测试数据仅位于本机新建的唯一临时目录；外部探针是一次性评审工具，不是产品依赖或调试后门。

## 已复核边界与未覆盖项

普通桥接调用、同步应用和 SSE 回包数据库/凭据处理持有活动 lease；安装准备阻止新入口并等待已有活动结束。checkpoint 遍历已登记空间，不迁移休眠库、不删除正文或 outbox；失败恢复正常调用，安装不以远端上传成功为前提。

目录使用固定公钥验证原始字节后解析，拒绝未知/重复结构字段，绑定有效期、序号/摘要、版本、平台、协议/schema、版本化 HTTPS URL；缓存按大小/哈希和 Windows 包签名重验，安装前重新检查目录并比较候选身份。下载客户端没有复用账号凭据，错误为固定代码。

本评审未执行真实官网目录部署/完整网络下载、生产 APK 签名连续性、Windows NSIS 覆盖安装、Linux 桌面安装、掉电文件系统恢复、完整设备矩阵或连续 7 天试用；发布协作者须在各自记录中单列实际结果。未实现的 Linux 自动安装、根钥桥接和跨 schema 升级仍按现有规范暂缓。
