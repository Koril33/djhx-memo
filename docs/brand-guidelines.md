# 阳墨随记 / DJHX MEMO 品牌规范

2026-11-21更新：维护者已确认jade-ivory-v1母版，三端/官网受管资源已替换并完成[本地检查](brand-jade-ivory-validation.md)，未发布新安装包或部署线上。当前目标见[0.2.0](../spec/client-0.2.0.md)，0.1.10/sunrise记录保留历史；每次更换执行[固定流程](brand-change-workflow.md)。

## 唯一配置与输入

[profile.json](../assets/brand/profile.json)定义中文“阳墨随记”、英文“DJHX MEMO”及jade-ivory-v1图形。源/包哈希与[已确认母版](../assets/brand/sources/jade-ivory-v1/README.md)对应：墨绿渐变、玉白墨盘、右上缺口及墨滴。每个尺寸直接从矢量渲染，Android各层/遮罩同源适配；不把一张大PNG复制为所有平台尺寸。旧sunrise完整包、63项活动快照和paper-v1保留。

[master.svg](../assets/brand/master.svg)、[单色版](../assets/brand/monochrome.svg)、[设计板](../assets/brand/design-sheet.png)由统一入口适配；设计板上排为标准、圆形、adaptive及单色示意，下排为尺寸阶梯与色板。Logo不含字标，中文/英文仍使用平台字体，不新增字体运行依赖或启动动效。官网logo、SVG/ICO/PNG favicon和Apple触屏图标同时受管，黑白托盘保持原字节。

## 名称与身份

应用内页头、关于/更新页面、窗口/托盘提示、文件对话框与官网按当前语言显示；Android启动器和Linux desktop名称按系统语言，Windows安装入口按安装语言。切换应用语言不改动已有快捷方式。

内部固定值为app.djhx.memo、djhx-memo可执行文件/Linux包名和Windows INSTALLIDENTITY“简记”。原安装目录、卸载键、凭据服务、数据目录、证书和更新信任根保持。禁止全局替换旧名称或通过改包名刷新图标缓存。Windows迁移仅处理确认属于本程序的旧快捷方式；同名其他程序入口保留。

## 平台适配

- Windows使用包内专用七尺寸ICO；Tauri/Linux使用专用32、128、256及512px图标，系统槽位保持原身份。
- Windows托盘读取SystemUsesLightTheme，独立于软件的强制浅深主题，采用包内黑白32px图形。读取失败时用彩色应用图标。
- Linux面板没有统一可靠颜色接口，默认彩色兜底；可选本机环境变量`DJHX_MEMO_TRAY_THEME=dark`或`light`分别选白/黑版。该覆盖属于用户主机面板配置，不是其他开发者必须设置的项目参数；不能由GNOME名称猜测面板颜色。
- Android包含五密度传统/圆形图标、自适应背景/前景及单色资源，v26和v33 XML分开。系统遮罩和主题着色保持系统行为；通知资源使用同源单色图形，不新增通知功能。
- 官网logo和favicon使用同源SVG，文字由品牌定义注入构建和语言切换；更新必须部署完整静态构建。

## 生成、检查和恢复

在仓库根使用Python3.11+：

```sh
python scripts/generate-brand-assets.py
python scripts/generate-brand-assets.py --check
```

入口只用标准库消费已渲染文件，不执行素材包内脚本或安装sharp/Pillow；源包哈希、明确输出白名单、PNG/ICO、Android XML、生成文件及真实平台身份均被检查。所有文本显式UTF-8，不依赖Windows区域设置。归档、源包和profile不属于可删除的活动输出。

旧绿色纸张的35份受管输出、母版、原清单、原生成器和历史规范文本保存在[paper-v1归档](../assets/brand/archive/paper-v1/README.md)。在profile中把图形改为paper-v1，再运行相同入口即可恢复；名称可保持新名或独立恢复。已实际执行paper-v1→sunrise往返检查，原生系统图标缓存与安装效果仍须各平台复核。
