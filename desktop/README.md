# desktop

Windows / Linux Tauri 2 客户端。Rust 后端直接链接 core，本地前端在本目录内采用 Svelte + TypeScript + Vite，编辑器使用 CodeMirror 6；不依赖尚未开发的独立 Web 工程。

当前 0.1.1 界面预览保留 0.1.1 界面预览的功能，包含托盘、未登录笔记、账号、Markdown、搜索、回收站、导出与同步。全局快捷键、开机自启、自动升级暂缓。Windows 首先验收负责人当前的 Windows 10 22H2 x86_64，Windows 11 兼容性另行记录；Linux 默认 Ubuntu 24.04 x86_64，验证 Wayland/X11、输入法及 AppIndicator 环境。

## 开发与构建

要求 Node 22.12+、Rust 1.99+；Linux 需要 GTK3、WebKitGTK 4.1、AppIndicator 开发包，Windows 需要 MSVC C++ 工具链与 WebView2。依赖版本及维护来源见 [ADR-0007](../docs/adr/0007-desktop-dependencies.md)。

```sh
cd desktop
npm ci
npm run format:check
npm run lint
npm run check
npm test
npm run build
npm run tauri dev
```

原生检查在 `desktop/src-tauri/` 执行 `cargo fmt --check`、`cargo check --locked`、`cargo clippy --locked -- -D warnings` 和 `cargo test --locked --release --bin djhx-memo`。Linux 打包：`npm run tauri build -- --config src-tauri/tauri.linux.conf.json --bundles deb`；Windows 原生环境打包：`npm run tauri build -- --bundles nsis`。生成物在 Cargo target 的 `release/bundle/deb/` 或 `release/bundle/nsis/`。Windows 安装器嵌入 WebView2 引导程序，缺失运行时时需要网络下载微软运行库。

Linux 包名为 `djhx-memo`，桌面显示“DJHX Memo”；GNOME 托盘需启用 AppIndicator 扩展。托盘宿主无法确认时，窗口明确提示“关闭后退出”，不会隐藏到无法恢复的位置。

前端仅经 `core_invoke` 调用 [JSON 桥接](../spec/core-bridge.md)，不持有 token、数据库路径或同步状态机。系统 keyring 不可用时，账号凭据仅驻留进程并有提示。编辑缓冲在提交成功前保留，保存失败可复制或另行导出 Markdown 救援。

见 [产品规范](../spec/product-v1.md)、[UI 接口](../spec/core-api.md)、[桌面验证记录](../docs/desktop-validation.md) 与 [三端验收](../docs/validation.md)。

安装包归档在仓库根目录 `dist/linux/` 和 `dist/windows/`，各有 `SHA256SUMS`。包内 `licenses/` 携带 core、桌面 Rust 与实际打入前端的 npm 依赖许可证原文；生成方法见 ADR-0007。当前验证环境和剩余验收项以桌面验证记录为准，候选包不代表 v1 发布验收已完成。

## 语言、外观与时间

设置 → 偏好设置提供“跟随系统 / 简体中文 / English”、外观“跟随系统 / 浅色 / 深色”与显示时区“上海 / UTC / 跟随系统”。默认语言和外观跟随系统，中文系统使用简体中文，其余语言回退英文；显示时区独立默认为 `Asia/Shanghai`。跟随系统动态响应语言和明暗变化。Linux GTK3/WebKit 对 GNOME 新外观设置的媒体查询可能不一致，因此原生层优先读取桌面 portal 的 `org.freedesktop.appearance/color-scheme`，无明确偏好时回退 GNOME `gsettings`；仅在跟随系统模式每 2 秒异步检查，与应用显式主题设置分离。其他平台采用系统 WebView 媒体查询。选择仅保存在此设备的 WebView 本地存储 `djhx-memo.presentation.v1`，重启有效，不加入账号同步或修改操作系统设置。

注册/登录表单首次默认地址为 `https://memo.djhx.site`，可输入其他 HTTPS 地址；切换外观或语言不会覆盖正在填写的地址或已保存账号的归属。应用名和窗口标题统一为 **DJHX Memo**，数据目录、包标识、二进制名和 keyring 身份继续沿用原值；图标衍生资源及更换流程见[品牌设计说明](../docs/brand-guidelines.md)。

双语文案集中在 `src/lib/i18n.ts`，偏好解析与日期格式集中在 `src/lib/preferences.ts`；新增可见文案应补齐两个语言和相同插值参数。错误按 core 错误码及已知安全的外壳消息映射，不展示任意原始异常。账号与设备自定义名称、笔记内容和导出 UTC 时间保持原样。CodeMirror 通过 compartment 更新占位符和可访问名称，主题通过成对颜色变量更新，切换偏好不重建编辑器或清空撤销与组合输入。设置关闭使用固定 36×36 点击区域内的 20×20 SVG，避免不同平台字体基线和默认按钮内边距裁切图标。

Windows 安装器使用 `src-tauri/windows/installer.nsi`，来自已锁定 `@tauri-apps/cli` 2.12.4 的[同版本官方模板](https://github.com/tauri-apps/tauri/blob/tauri-cli-v2.12.4/crates/tauri-bundler/src/bundle/windows/nsis/installer.nsi)（上游双许可，此处按 MIT 分发，原文随包附带）。仅为本次品牌改名固定旧候选版的安装注册表身份与默认目录“简记”，显示名、窗口名及新快捷方式仍为 DJHX Memo；旧快捷方式仅在目标确认为本安装目录的二进制时改名迁移，目标已存在或改名失败时保留旧入口；`/UPDATE` 和 `/NS` 下也不会先删掉所有入口。已有候选版可覆盖安装，避免因 Tauri 默认将卸载键绑定产品名而形成第二套安装。升级 CLI 时须同步上游模板并保留该差异，重新验证旧版升级、重装和卸载；模板内部旧名称不应当作用户可见品牌进行批量替换。

## 0.1.1 界面预览

界面采用共享的纸白/炭灰与品牌绿语义色、系统字体及 4/8 间距。60px 导航保留新建、笔记、回收站和设置；列表顶部显示 DJHX Memo，列表更多菜单提供当前空间导出和账号入口。编辑器更多菜单提供删除/恢复、复制与当前文字导出。底部紧凑状态可展开查看本地保存、云同步与最近同步时间，并保留手动同步入口；保存失败救援、凭据失效、冲突及恢复提示仍直接显示。

通用操作使用离线随包的 Lucide 线性图标，来源和许可见[共同界面设计说明](../docs/ui-design.md)；Markdown 任务标记也使用同一静态 `check` 图标，用户 Markdown 不能注入 SVG。普通图标目标至少 36px，具备双语可访问名称与悬停提示。源码、预览和对照切换只隐藏相应面板，保留 CodeMirror 实例及撤销/组合输入状态；切换笔记仍先保存。本轮仅修改展示，不改 core、同步、鉴权或数据目录。实际构建记录见[桌面界面预览验证](../docs/desktop-ui-preview-validation.md)。

0.1.1 的紧凑导航、图标和菜单入口见[界面设计](../docs/ui-design.md)，本轮实际检查见[预览验证](../docs/desktop-ui-preview-validation.md)。
