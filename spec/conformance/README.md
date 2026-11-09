# 一致性与可靠性场景

[scenarios.md](scenarios.md) 是 v1 验收场景目录；逐项覆盖以模块验证记录为准，不能认定整份目录已经通过。已建立 [fixtures.json](fixtures.json)（schema_version=1）的 11 项共享声明式同步裁决数据，由 Go `TestSharedConformance` 与 Rust `shared_fixtures_over_real_http` 分别执行，并保留真正多库/故障联调。

数据文件使用 case_id、初始实体、设备操作及最终断言。符号 A/B/N/O 在适配器中映射为合法 UUID；故障通过事务注入或受控 HTTP 代理的请求屏障确定，禁止靠真实 sleep 猜测执行顺序。性能测量中的等待只用于观测真实异步完成，并设置期限。

执行命令：`bash scripts/validate-local.sh server`、`bash scripts/validate-local.sh integration`。后者构建真实 Go 二进制并显式运行默认 ignored 的集成项。实际证据见 [核心](../../docs/core-validation.md)、[服务端](../../docs/server-validation.md) 与 [交叉评审](../../docs/reviews/core-review.md)。

server 适配器断言权威实体、变化日志、账号隔离和幂等；core 适配器断言本地持久性、基准版本、待发操作与恢复；集成适配器通过真实 HTTP 和多个独立本地数据库验证最终结果。UI/平台场景另用对应测试或人工验收，不能要求服务端 runner 验证托盘或输入法。

每个用例独立数据库，记录实际失败位置；重复执行、重启后执行均应可复现。增加或修改同步行为时，先更新相关场景，再修改实现。
