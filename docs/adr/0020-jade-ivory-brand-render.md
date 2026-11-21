# ADR-0020：jade-ivory母版与可重复渲染资源包

- 日期：2026-11-21；状态：维护者已确认新母版并授权多端替换，资源接入与[本地检查](../brand-jade-ivory-validation.md)完成；原生安装与线上部署待验收。
- 延续 [ADR-0010](0010-brand-assets.md)与[品牌固定流程](../brand-change-workflow.md)。旧sunrise包、paper-v1、应用身份、黑白托盘保持可恢复。

原玉白SVG遗失后，已根据维护者的PNG预览重绘纯矢量母版，SHA256为 `1970cef1201bc15581156631df4c403c217626b92f7f30944b7e0d8296771cb4`；维护者确认采用。原样归档该来源，生产SVG/PNG/ICO/Android层由其派生，不冒充遗失文件或沿用旧provenance声明。

既有Python适配器只消费已渲染包，不能处理任意SVG；当前Windows没有旧ADR锁定的ImageMagick环境。采用独立、可选的 [tools/brand-render](../../tools/brand-render/package.json)，固定 `@resvg/resvg-js@2.6.2` 与npm锁文件，从已锁哈希的自有路径/渐变母版生成资源。该工具由[resvg-js上游](https://github.com/yisibl/resvg-js)维护，固定[2.6.2源码](https://github.com/yisibl/resvg-js/tree/v2.6.2)，不跟随浮动版本；其[package声明](https://raw.githubusercontent.com/yisibl/resvg-js/v2.6.2/package.json)许可证为MPL-2.0，第三方工具保留自身许可，不按仓库MIT重新授权。

工具仅用于开发时生成受管资源包，不进入客户端、服务端或官网运行依赖。普通构建仍通过Python标准库读取已提交、哈希固定的ZIP；不要求CI/贡献者安装渲染工具。维护者需要重建图形包时才执行独立npm ci，默认不执行依赖安装脚本，不读取系统字体或外部图像。升级工具/原母版/几何映射需更新锁定值、全部派生物与清单并复核。

Android传统、圆形、自适应彩色和主题单色均同源；adaptive背景满幅，前景轻微缩小使轮廓位于66dp安全区，主题单色保留负形墨滴。通知图标维持单色，不新增通知功能。桌面黑/白托盘PNG/RGBA按旧包原字节保留，彩色兜底随应用图标更新。

官网同时管理header logo、SVG favicon、ICO与16/32px favicon、Apple触屏图标；HTML实际引用、Vite构建结果和资源可达性均须核对。新增受管路径逐项登记，不放宽任意路径/ZIP写入权限。

先归档sunrise当前受管输出、profile、哈希清单与适配器，再启用新profile。验证新旧profile的内存生成/恢复、全部输出check、Android图层/透明安全区、ICO尺寸及官网构建。原生桌面/启动器缓存、实际安装和线上官网部署仍单独验收；本次不升级版本或发布。
