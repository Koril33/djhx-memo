# sunrise / 0.1.13 活动资源快照

2026-11-21切换玉白母版前，按[品牌固定流程](../../../../docs/brand-change-workflow.md)原样保存63个受管输出、profile、原哈希清单和生成适配器。`files/`与 `manifest-before.json`逐项核哈希后写入，不覆盖更早paper-v1或原logo-pack-v1包。

本目录只保存历史，不作为当前活动图标。新适配器仍能从旧profile在内存中重建原63项且哈希完全一致，新增官网favicon也能随旧图形恢复；验证见[玉白接入记录](../../../../docs/brand-jade-ivory-validation.md)。

正式恢复应使用受管profile和统一生成流程，随后以新的发行版本交付；不能降低版本或覆盖旧Release附件。两份 `*-before.py` 是旧源代码快照，普通生成不调用它们。
