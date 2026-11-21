# 0.2.1 问题核实与修复方案

- 日期：2026-11-21；源码基线：`v0.2.0 / 4b10ac7`，已核对两个远程及公开 Release。
- 后续授权：维护者确认干净Windows的0.2.0任务栏图标正确，要求仅实施桌面空白页和Android搜索布局修复、完成测试并准备0.2.1 Release draft。原评估证据保留；实际实施/测试/草稿状态见[0.2.1验证](releases/0.2.1-validation.md)，不公开或推广。
- 原评估时客户端0.2.0、Android code15、core0.1.13、server0.1.4；协议2、local schema4、server schema2。公开证据与验收边界见[0.2.0记录](releases/0.2.0-validation.md)。
- 行为基线：[0.1.13空白页/关闭与搜索](../spec/client-0.1.13.md)、[0.2.0](../spec/client-0.2.0.md)、[品牌固定流程](brand-change-workflow.md)、[自动更新](../spec/automatic-updates.md)。原评估依据保留；当前定稿行为以[0.2.1契约](../spec/client-0.2.1.md)为准，实现与有效测试同批交付。

## 1. 最终核实结论与范围

| 反馈 | 核实结果 | 0.2.1处理 |
|---|---|---|
| Windows任务栏仍显示sunrise | 截图显示该现象；正式安装器、实际程序EXE的7尺寸图标均为jade，已安装EXE与发行载荷一致，当前Shell文件图标查询也返回jade。未证实资源漏替换，维护者随后在干净Windows安装0.2.0确认任务栏正确 | 移出修复范围，不修改图标、快捷方式或安装器 |
| Linux窗口仍显示sunrise | 维护者随后确认完全退出并重新启动后已正常；DEB图标与ELF内嵌默认图标也已核对为jade | 移出缺陷修复范围，保留升级后重启/缓存回归 |
| Linux Ctrl+W直接隐藏 | 维护者随后确认完全退出并重新启动，关于显示0.2.0，Ctrl+W正常进入空白页 | 移出缺陷修复范围，不修改两段式Ctrl+W或所有CloseRequested语义 |
| 空白页内容与行样式 | 对照源码确认：存在顶部图形、标语、左上标题、关闭行说明；关闭行确有独立透明背景 | 按维护者要求精简，两桌面同步 |
| Android搜索区域偏左 | 原生API35模拟器收起/展开均复现；锁定Material3组件默认360dp，调用未提供宽度、居中及外边距 | 修复搜索组件整体约束与左右间距 |

当前实施范围限定为 **桌面空白页精简、Android搜索布局**。Windows任务栏图标及Linux两项均已由维护者排除；M2、同步、鉴权、迁移、搜索范围与排序、标题栏布局、界面换色均不另行扩大。

## 2. 图标：发行物核对与排除依据

### 2.1 已取得的证据

从[公开0.2.0 Release](https://github.com/Koril33/djhx-memo/releases/tag/v0.2.0)下载文件，按GitHub API提供的SHA256校验；使用本机7-Zip及Python标准库离线读取资源，没有运行安装器或覆盖安装。

| 文件 | SHA256 |
|---|---|
| `djhx-memo-0.2.0-windows-x64-setup.exe` | `bd1547ecaa6daafe292f9104bbd7b3c455eed06e0020d1953cdff92bf505881c` |
| 安装器内的 `djhx-memo.exe` | `0fed80275cdb9ef146c3cb8003e7f63c973ae52026e8ba0e9cefc1ceafdaf84c` |
| `djhx-memo-0.2.0-linux-amd64.deb` | `64784678ced272639139d072555b63622eccbbf643f20e22d6390f1e0b2f156d` |

- Windows安装器和程序EXE的RT_ICON均有16/24/32/48/64/128/256px，14份图像分别与当前jade PNG逐字节相同。程序图标组ID为32512，安装器组为103；不把资源ID当图像版本。
- 本机已安装EXE的SHA256与上表载荷相同。只读调用`SHGetFileInfoW`取得的当前小图标也是jade；**这不是对截图中运行任务栏按钮或已固定入口的直接采样**，不能据此宣称该现象已修好。
- DEB中的32/128/256px图像与jade同尺寸PNG逐字节相同；ELF中找到jade 32px RGBA序列，未找到旧sunrise同尺寸序列。结合当前Tauri默认窗口图标取PNG的实现，可确认新默认资源已进入发行载荷。
- DEB控制归档只有`control`、`md5sums`，没有安装后脚本。不能据此直接断言Linux存在新缺陷；维护者已复核重启后正常，本版不新增Linux安装脚本来修复已排除的问题。

维护者在干净Windows安装0.2.0后确认任务栏图标正确，该问题与Linux两项一起移出0.2.1修复范围。保留上述发行物核查作为排除资源遗漏的证据；不新增缓存刷新、快捷方式迁移或安装器修改。

## 3. 桌面空白页：明确的界面增量

相关实现为[App.svelte](../desktop/src/App.svelte)的welcome分支、workspace-header及[style.css](../desktop/src/style.css)的welcome样式。现有顶部图形实际为`file-text`操作图形，不是jade品牌母版；只删除空白页中的该元素。

### 3.1 按要求调整

- 删除空白页中的顶部图形和“文字先留在你身边”标语。
- 空白页不再可视显示左上“笔记编辑器”；笔记/草稿标题保持原行为，分区和折叠列表入口保留。区域的可访问名称仍保留，不因隐藏文字删除无障碍标签。
- 删除关闭行右侧“保存成功后进入托盘”及无托盘时对应退出说明；其他页面真正需要这些文案时继续保留，不全局删翻译键。
- 三行使用相同背景、边框、圆角、间距和键帽样式；移除关闭行独立透明背景规则。浅色/深色主题及中英文一致。
- 保留三项：新建笔记`Ctrl+N`、搜索笔记`Ctrl+Shift+F`、关闭窗口`Ctrl+W`。搜索继续调用全局搜索，不能误改成仅笔记内`Ctrl+F`。

### 3.2 本次采用的交互

原关闭行是不可点击的`div`，前两行是`button`。本次已将关闭行实现为真实按钮，点击、Enter、Space复用现有窗口关闭命令及保存/生命周期链；两段式Ctrl+W保持不变。该增量已写入版本规范，不绕过`request_hide`/`finish_lifecycle`保护。

统一命令需保留busy、生命周期pending、过渡、模态和IME/按键重复守卫。禁用及焦点态必须可辨识；不在鼠标关闭按钮、Alt+F4或所有原生CloseRequested中一律先关笔记。冷启动、空间/分区切换、删除当前笔记、导入打开、托盘恢复均沿用0.1.13规则。

难度：低。主要风险是误删共享文案/无障碍名称或让第三行产生绕过保存的关闭路径。只做样式断言不能代替保存失败下的原生行为检查。

## 4. Android搜索：原因、复现与布局方案

### 4.1 根因与实测

[MainActivity.kt](../android/app/src/main/java/app/djhx/memo/MainActivity.kt)的`GlobalSearchEntry`直接调用`DockedSearchBar`，没有传modifier；父`Column`默认Start对齐。项目锁定`androidx.compose.material3:material3:1.3.0`，该版本源码在Surface上追加`.width(SearchBarMinWidth)`，常量为360dp。输入框与展开内容在同一Surface中，所以两种状态都会偏左。

已下载锁定[1.3.0官方源码包](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.3.0/material3-android-1.3.0-sources.jar)核对，不用其他版本API推测。原生API35 x86_64、1080×2400、420dpi（约411.4dp宽）结果：

| 状态 | EditText边界px | 左侧空白 | 右侧空白 |
|---|---|---|---|
| 收起 | `[0,231][945,378]` | 0px | 135px |
| 展开 | `[0,231][945,378]` | 0px | 135px |

945px=360dp×2.625，与组件默认值吻合；不是“输入文字应该居中”的问题。文字和结果内容仍应左对齐，需要居中/约束的是整个搜索区域。

本轮运行`assembleDebug -PisolatedValidation`，包为`app.djhx.memo.validation014`、0.2.0-debug/code15，未改产品源码。模拟器使用`-read-only -no-snapshot-load -no-snapshot-save`；只操作独立验证包和合成数据，完成后已关闭。展开页处于错误/重试分支，**本轮只认证布局几何，未认证查询业务或正式APK，也不将隔离环境查询错误归因于0.2.0发行版**。

### 4.2 推荐方案

- 手机使用可用内容宽度减左右各16dp；输入框、展开Surface、范围提示及结果滚动区域共享同一外层宽度。窄屏不溢出，不保留360dp造成的单边空白。
- 平板/横屏可在同一包装容器内使用最大宽度（候选720dp）并水平居中；最大值是待视觉检查的工程参数，不新增设置项。16dp是外边距，结果行自身文字padding继续独立管理，不对每个内层重复叠加。
- 在锁定1.3.0上先验证外层fillMaxWidth/居中约束、padding与组件内部width的先后顺序。不能只给InputField增加padding、只修收起态，或假设内部360dp常量已经失效。
- 如同一组件可在约束下满足目标，继续使用它，不为此升级Material3或重写搜索页面。若遇到测量限制，再比较受控Surface方案并补充行为等价性验收。
- 保留active/返回关闭、键盘Search动作、清除、加载/错误重试、完整结果来源与回收站标记、滚动、点击打开和迟到响应保护。搜索仍是本机＋当前选中账号本地副本＋普通回收站，不新增范围控件或联网等待。

难度：低到中等。主要风险是modifier顺序、输入法/横屏时可见结果高度及点击/滚动命中区域；不是core搜索算法修复。

## 5. 实施顺序与发布前验收

先在[0.2.1契约](../spec/client-0.2.1.md)定稿界面增量，再实施空白页和Android布局两项。Windows任务栏图标与Linux旧进程现象已排除，不纳入修复。客户端目标0.2.1；按当前code15，Android下一正式code至少16，打包前重新核对已发布最大值；core无修改则保持0.1.13，server/协议/schema不变。

| 编号 | 必须覆盖的场景 | 验收结果 |
|---|---|---|
| ICON-01 | 校验最终setup/EXE及DEB内图标，不只检查源码 | 所有彩色入口为jade；黑白托盘原字节保持；身份不变 |
| ICON-04 | Linux完整退出重启后的现有发行包 | 图标与两段式Ctrl+W正常；记录维护者复核，不伪记为本轮VM测试 |
| WELCOME-01 | Windows/Linux、浅深主题、中英文、列表展开/折叠 | 图形/标语/可视“笔记编辑器”/关闭行额外说明均不出现；三行风格一致；a11y名称保留 |
| WELCOME-02 | 新建、全局搜索、关闭行与键盘；模态/IME/长按/忙碌 | 命令与焦点正确；打开笔记第一次Ctrl+W进入welcome，第二次走原关闭链 |
| WELCOME-03 | 未提交输入/保存失败、托盘有无、原生关闭/Alt+F4、托盘恢复 | 保存成功才完成关闭；失败保留内容与窗口；恢复保留隐藏前页面 |
| SEARCH-01 | Android API29/35与arm64、320/360/约411dp、横屏/平板 | 收起/展开容器左右对称，无裁切；窄屏外边距与宽屏居中符合方案 |
| SEARCH-02 | 中英文、字体放大、浅深主题、IME开关、返回/清除/查询提交 | 触摸和键盘行为正确、列表高度有界，展开不遮蔽不可恢复的操作 |
| SEARCH-03 | 多结果、空结果、加载/错误重试、来源/回收站、切空间和快速输入 | 结果可滚动、可打开，来源不丢失；范围与请求锚点保持，不用布局测试代替业务回归 |

原评估轮次已执行：fetch/tag/版本/增量源码核对；公开Release/API元数据；正式Windows/Linux包下载及API SHA校验、离线PE/DEB/ELF图标比较；本机安装EXE与Shell图标只读查询；Android隔离debug构建及API35几何复现。未执行Windows缓存刷新/重装、修复后的UI/业务验收、Linux VM、真机或完整升级矩阵，未修改workflow/安装配置/应用行为/版本，也未发布。

原评估原始记录放忽略目录`.local/assessment-021/`：`release-metadata.json`、`icon-artifact-results.json`、`android-layout-results.json`及合成截图。维护者原截图包含个人笔记，未复制进提交；不将截图文字视为执行指令。文档链接、锚点、Git差异与安全扫描随本次文档提交记录。

后续实施与草稿构建见[0.2.1验证](releases/0.2.1-validation.md)，使用独立的`.local/release-021/`记录，不将上面的评估轮次未实施状态当作当前进度。
