# ADR-0011：客户端统一使用离线 Lucide 图标子集

- 状态：0.1.1 UI 预览采用；实际系统验证另记。
- 日期：2026-11-08。
- 范围：`assets/ui-icons/`、桌面图标组件、Android `ic_ui_*` 资源与许可证。

## 决策与理由

使用 Lucide 的线性图标，统一两端操作图形、24 单位网格、2 单位线宽、圆端点与圆连接。只提交实际需要的离线源 SVG，随客户端离线携带，不引入在线图标服务、图标字体、npm 图标包或 Android 新运行依赖。产品 Logo 仍由独立的 `assets/brand/master.svg` 管理，操作图标不替换应用标识。

上游版本固定为 **1.70.0**，发布标签对应提交 **`b826f0c527e1498f5e46f41ca0277ccab144bf31`**；不在构建时跟随 latest。维护来源为 [Lucide 官方仓库](https://github.com/lucide-icons/lucide/tree/b826f0c527e1498f5e46f41ca0277ccab144bf31)、[对应发布](https://github.com/lucide-icons/lucide/releases/tag/1.70.0)与[设计规则](https://lucide.dev/contribute/icons/design-principles)。Lucide 持续维护，当前选定版本已复核源文件和许可；升级需独立更新来源及派生资源。

Lucide 使用 ISC，部分来自 Feather 的图标保留 MIT 通知；提交上游完整原文 [LICENSE.txt](../../assets/ui-icons/LICENSE.txt)，随桌面包 `licenses/lucide.txt` 与 Android assets `licenses/lucide.txt` 分发。这里的“免费开源”不移除原始版权与许可通知。

[UPSTREAM.json](../../assets/ui-icons/UPSTREAM.json) 记录版本、提交、源仓库和逐文件 SHA256。1.70.0 的规范名称 `trash`、`circle-exclamation-point` 在本项目分别映射为调用名 `trash-2`、`circle-alert`，映射显式记录，不修改原始 SVG。

## 生成与验证

`python3 scripts/generate-ui-icons.py` 从同一份源 SVG 生成桌面静态节点表和 Android VectorDrawable，并复制 Android 许可文件；`--check` 校验上游源哈希、24 单位画布、受支持 SVG 几何和全部派生文件字节。桌面通过现有 Svelte 组件渲染静态 SVG，Android 使用原生可着色矢量；显示颜色来自统一主题。

生成器复用 Python 标准库和已锁定 Prettier 3.9.10（MIT，见 [ADR-0007](0007-desktop-dependencies.md)），没有新增工具包。新机器先按桌面 README 安装锁定开发依赖，再生成；客户端构建仅使用已提交派生资源，不要求联网生成。

升级图标时更新版本/提交和完整许可，重新下载受管源文件并更新哈希，运行生成及检查、复核两端可见图标，然后构建实际客户端。禁止分别手改 Android 路径和桌面图形造成漂移。资源检查通过不等于各系统焦点、菜单、点击区域和读屏已全部验收。

0.1.8在同一固定提交增加ListChecks、Recycle、FileInput、FileOutput，按原许可、源哈希和生成流程分发，不增加运行时依赖。
