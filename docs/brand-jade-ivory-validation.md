# jade-ivory 多端图标接入与本地检查

- 日期：2026-11-21；代码起点：`v0.1.13 / d73bace`。
- 授权：维护者确认采用PNG重绘的新母版，并要求生成/完全替换三端及官网图标，特别检查favicon。**已完成仓库受管资源替换和本地检查；尚未发布新安装包或部署线上官网。**
- 已确认母版：[source/master.svg](../assets/brand/sources/jade-ivory-v1/master.svg)，SHA256 `1970cef1201bc15581156631df4c403c217626b92f7f30944b7e0d8296771cb4`。原SVG遗失，参考PNG及新绘制来源保留，不冒充原文件恢复。
- 锁定包：[jade-ivory-v1.zip](../assets/brand/packs/jade-ivory-v1.zip)，SHA256 `1a68e1f222f5016d8fb60c2ec8648ad301961339bfc2aa672d83d872dd6b6765`；[ADR-0020](adr/0020-jade-ivory-brand-render.md)记录工具、许可证、派生与兼容边界。

## 1. 实际覆盖范围

| 触点 | 受管资源与核对 |
|---|---|
| 公共母版/设计板 | 彩色SVG、单色负形SVG、16/24/32/48/64/128/256/512/1024px PNG、同源设计板；纯路径/渐变，没有将PNG嵌入母版 |
| Windows | Tauri 32/128/256/512px PNG和多尺寸ICO；同一ICO由窗口/任务栏及NSIS安装器路径消费；不改应用标识刷新缓存 |
| Linux | 同源Tauri PNG/ICO、desktop图标安装入口；显示名和binary不变；没有以Windows检查冒充Linux安装验收 |
| Android传统/圆形 | mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi五密度，分别48/72/96/144/192px；圆形版本用满幅圆背景，独立生成 |
| Android adaptive | 五密度108/162/216/324/432px前景、满幅渐变背景、主题单色层；v26与v33 XML分别引用，圆形入口也覆盖 |
| Android单色/通知 | 主题层使用同源单色轮廓与透明墨滴负形；通知24/36/48/72/96px白色/透明，不新增通知功能 |
| 桌面托盘 | 黑/白PNG和RGBA共4份与sunrise快照哈希完全一致；主题选择逻辑未改，彩色应用图标兜底随新图形更新 |
| 官网 | header的logo.svg，独立favicon.svg、favicon.ico、16/32px favicon及180px Apple触屏图标；触屏图背景全不透明。HTML已逐项引用，Vite public输出包含它们 |

受管输出共68项，原63项中53项更新、10项保持（含4份托盘和稳定名称元数据），新增5项官网资源。旧sunrise受管输出/生成器完整归档，logo-pack-v1及paper-v1继续可恢复。操作图标、OS标记、页面颜色和布局不属于此次品牌替换，未改它们。

## 2. 已运行的检查

| 检查 | 结果与边界 |
|---|---|
| 可重复包生成 | 固定resvg-js2.6.2、关闭系统字体/外部资源；两次生成ZIP的SHA256完全一致；既有版本包不同字节时拒绝覆盖 |
| `generate-brand-assets.py --check` | 68项全部匹配；路径、包/成员哈希、PNG/ICO/XML/SVG及安装身份检查通过 |
| 输入/覆盖防护 | 缺favicon、路径越界、重复ZIP成员、错误源SVG四个合成篡改包，均被真实读取器拒绝；未放宽路径白名单 |
| Android安全区/负形 | 前景按母版线性映射的85%适配，108px前景与单色层alpha边界为x/y 25..82，保持上一版约58dp占幅，落在中央66px安全区；墨滴单色负形透明，背景角落不透明。高密度层同源映射；设计板按72/108视口模拟圆形遮罩，原生遮罩另测 |
| 旧图形恢复 | sunrise原63项在内存生成后逐项匹配原哈希；其余四配色、paper-v1均可生成，新增官网favicon引用也有对应资源；不以反复覆盖活动图标来验证 |
| Windows图标 | NSIS编译只含新ICO的隔离格式检查exe成功，未运行该exe；Tauri `cargo check --offline --locked`通过，包含当前core/桌面源码和图标代码生成 |
| Android资源 | `gradlew.bat --no-daemon :app:processDebugResources`成功；记录既有SDK XML版本工具链warning，不称零警告；未安装/卸载任何app |
| 官网构建/引用 | `npm run build`成功；解析实际dist HTML，header logo及5个favicon/touch引用均在临时本机HTTP服务返回200、类型正确、内容与当前受管文件逐字节相同，服务已关闭 |
| 视觉核对 | 查看母版与字体无关的多端/小尺寸设计板；没有执行PNG中的指令或复用旧provenance元数据 |

补充检查：文档链接/章节锚点、Git差异及SECURITY工作快照/历史/提交元信息扫描随本次提交执行。没有升级Cargo/npm应用依赖、Android版本号或发布版本；新增渲染依赖仅位于可选开发工具目录，普通Python适配/客户端/官网构建不依赖它。

## 3. 仍需在发布阶段完成

Windows实际安装/快捷方式/任务栏与图标缓存、Ubuntu X11/Wayland安装与dock、Android真机/OEM遮罩和主题图标、旧包覆盖升级、线上官网favicon缓存和完整部署尚未实测。资源编译与本机HTTP成功不代表这些矩阵通过。

旧0.1.13公开安装包仍是其原发行图标，不覆盖既有tag/附件。新图标进入后续经授权的版本构建和官网部署；不以改包ID/安装键/签名身份绕过缓存。M2、离线交互、排版与安装目录工作尚未因本次图标接入完成。
