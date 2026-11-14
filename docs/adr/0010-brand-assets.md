# ADR-0010：统一品牌母版与开发期图标生成工具

0.1.10补充（2026-11-14）：维护者选择logo-pack-v1/sunrise与阳墨随记 / DJHX MEMO。原纸张母版、已渲染输出及原生成器完整归档，旧决策保留历史。活动入口改为版本化profile加Python标准库的确定性资源适配，直接消费包中专用小尺寸/ICO/Android层；不执行其依赖未锁定的sharp/Pillow脚本，不引入客户端或开发期新依赖。名称和图形独立选择；全部触点由同一配置生成。固定流程见[品牌更新流程](../brand-change-workflow.md)，后续原生实际效果仍须单独验收。

- 状态：已接受实现；平台安装效果按实际验证另记。
- 日期：2026-11-07。
- 范围：`assets/brand/`、`scripts/generate-brand-assets.py`、桌面和 Android 的图标资源。

## 决策与理由

按项目负责人选择保留原桌面图标，将其几何重建为一份可编辑 SVG。标准库脚本读取背景与纸张图层，生成桌面 PNG / 多尺寸 ICO，以及 Android 传统、自适应和单色图标。所有派生文件提交仓库，客户端构建和运行不要求安装生成工具；后续改图统一由母版重新生成，防止多端分别手绘造成漂移。

本机已有下列开发工具，复用它们，不新增 Python 包、npm 包或客户端运行依赖：

| 工具 | 锁定/实际版本 | 许可证与维护来源 | 用途 |
| --- | --- | --- | --- |
| Python | 脚本最低 3.11；实际 3.13.5，Debian 包 `3.13.5-1` | PSF License；[Python Software Foundation](https://www.python.org/downloads/release/python-3135/)、[许可证](https://docs.python.org/3/license.html) | 标准库 XML/JSON、确定性文件清单、ICO 封装、尺寸与安全区校验 |
| ImageMagick | **7.1.1-43**；Debian 包 `8:7.1.1.43+dfsg1-1+deb13u12` | ImageMagick License；[ImageMagick Studio LLC / 官方仓库](https://github.com/ImageMagick/ImageMagick/tree/7.1.1-43)、[许可证](https://imagemagick.org/license/) | MSVG 矢量栅格化、Lanczos 缩小、RGBA8 PNG 编码 |
| Liberation Sans 字体 | 2.1.5；Debian 包 `1:2.1.5-3` | SIL Open Font License 1.1；[Liberation Fonts 官方仓库](https://github.com/liberationfonts/liberation-fonts/tree/2.1.5) | 设计板中的英文辅助文字；不进入产品图标或客户端资源 |
| Noto Sans CJK SC 字体 | Debian 包 `fonts-noto-cjk` `1:20240730+repack1-1` | SIL Open Font License 1.1；[Noto CJK 官方仓库](https://github.com/notofonts/noto-cjk) | 设计板中文标题及双语说明；不进入产品图标或客户端资源 |

ImageMagick 仍由上游及 Debian 维护；使用当前机器已安装的明确版本，不跟随未锁定的最新版本。脚本检查渲染器版本，拒绝静默使用不同版本覆盖资源；工具升级应在独立变更中更新此 ADR、版本常量、全部资源及清单，复核视觉差异。不同平台的字体或渲染库可能影响设计板 PNG，建议用同版本开发环境运行；可编辑设计板 SVG 是跨环境查看入口。

输入为仓库自有的简单 SVG 矩形和路径。生成器只接受显式色值和已支持的几何，不下载外部资源、不处理用户上传内容、不读取应用笔记与凭据。使用 ImageMagick 的内置 MSVG 渲染器，不依赖浏览器、CairoSVG、Pillow 或在线图标服务。图标采用四倍采样后缩小以改善原始低分辨率 PNG 的边缘；不追求与原 PNG 逐像素相同。

Android 自适应层分离依据 [Android 官方规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)：完整纯色背景、108 dp 前景、中心安全区，以及 Android 13+ 的单色层。Android API 26 与 API 33 的 XML 分别存放，保留旧系统兼容。

## 验证与交付边界

`python3 scripts/generate-brand-assets.py --check` 重建所有输出并比对文件，验证 PNG / ICO 尺寸、XML 与 Android 前景的中央 66 dp 安全区。设计板包含尺寸阶梯、色板、构图、留白、浅深背景和三种遮罩示意，人工检查后再构建客户端。

实际 Windows/Linux/Android 包的图标显示与缓存行为属于平台验收，不能仅凭资源脚本通过宣称全部系统已验收。具体替换步骤见 [品牌规范](../brand-guidelines.md)。
