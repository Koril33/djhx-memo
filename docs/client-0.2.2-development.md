# 0.2.2 开发文档

- 日期：2026-11-22；状态：**范围与兼容边界已确认，源码已实现；测试/草稿发行进行中，未公开或部署，实际证据见[本轮记录](releases/0.2.2-validation.md)**。
- 目标行为以 [spec/client-0.2.2.md](../spec/client-0.2.2.md)为准，本文供后续开发、测试、评审和发行使用。
- 基线为已公开 `v0.2.1 / 9e4c84208d003b7615d7e23bfb31d0556aada32a`；设计起点两远程 main 为 `fd0ea4c`，实施起点为 PR #33 合入的 `8e7a154`。客户端 0.2.1、Android code16、core0.1.13、server0.1.4、协议2、local schema4/server schema2。
- 0.2.1 于上海时间 2026-11-22 08:12:58 公开；API 列出三端包、Windows `.sig`、server 归档、SHA256SUMS、catalog-12/current 和部署包共9项。此处只确认公开状态与附件列表，签名和原生测试沿用[实际记录](releases/0.2.1-validation.md)，不推断官网已部署目录12。

## 1 复审结论与范围

四项可在现有架构上实施，没有需要维护者重新选择产品方向的重大矛盾。主要工作量在桌面内容位置映射、可信下载路径选择，以及个人服务器与旧客户端的发行兼容；Linux 保持手动更新显著缩小安装适配范围。

| 工作包 | 已确认内容 | 成本与主要边界 |
|---|---|---|
| W1 桌面 lint | Windows/Linux，默认关闭，离线检查提示，首版无自动修复 | 中等；新增浏览器引擎/展示扩展，保护 IME、输入与保存性能；Android 不做 |
| W2 对照同步滚动 | 默认开启，可关闭，双向按内容同步 | 中等；建立源码与预览块位置映射，处理重绘、换行和惯性滚动 |
| W3 Linux 下载 | 更新页提供最新正式 amd64 DEB 的浏览器入口 | 较小；不启用自动检查、下载、安装、AppImage 或 APT |
| W4 两源发行 | 个人服务器为默认主源，GitHub 为备用；三端设置可切换 | 中高；同包验证、来源偏好、取消状态、官网/服务器/CI及旧版兼容需一并交付 |

需要保持的边界：下载源不是账号同步服务器；选择 GitHub 不切换版本目录服务；Linux 浏览器下载不进入自动安装状态机；lint 与滚动不改正文或 Markdown 语义。既有空白页、两段式 Ctrl+W、搜索范围和 Android 搜索布局完整保留。

## 2 当前入口与影响面

| 入口 | 当前事实 | 实施方向 |
|---|---|---|
| [Editor.svelte](../desktop/src/Editor.svelte) | CodeMirror6；已有 Compartment、组合输入、查找及独立视图生命周期 | 增加 lint 可配置扩展、诊断定位、受控滚动接口；不重建编辑器来开关功能 |
| [App.svelte](../desktop/src/App.svelte)、[markdown.ts](../desktop/src/lib/markdown.ts) | 对照两区独立；Marked 渲染，只有任务勾选的局部行定位；源码模式不渲染预览 | 加块级源码范围和渲染快照，集中滚动协调；复用既有预览，不另造第二套解析器 |
| [preferences.ts](../desktop/src/lib/preferences.ts) | WebView 本设备语言/主题/时区偏好 | 扩展 `markdownLintEnabled=false`、`splitScrollSync=true`，容忍缺失/非法字段 |
| [update_catalog.rs](../core/src/update_catalog.rs) | v1 目录严格字段、GitHub规范路径、固定域名、单任务、签名和防回放 | 保持目录结构，增加固定来源解析及独立来源偏好；保留候选身份和安装前重验 |
| [桌面更新适配](../desktop/src-tauri/src/update.rs)、[Updates.svelte](../desktop/src/Updates.svelte) | Windows 安装；Linux 为 system-managed | 三端来源选择由 core 统一处理；Linux 只增加受限浏览器入口 |
| [AppUpdates.kt](../android/app/src/main/java/app/djhx/memo/AppUpdates.kt)、[UpdateSettings.kt](../android/app/src/main/java/app/djhx/memo/UpdateSettings.kt) | 原生更新状态、下载与安装交接 | 接入来源状态/取消回执；重生成当前 core 的 JNI/UniFFI，保护已有系统安装流程 |
| [官网生成器](../website/scripts/downloads.mjs)、[下载清单生成](../scripts/build-downloads-manifest.py)、[浏览器回归](../scripts/check-website-downloads.py) | 下载直链硬编码到 GitHub，受版本化清单控制 | 单一清单生成主/备用地址；静态 HTML 与 JS 执行后保持一致 |
| [release.yml](../.github/workflows/release.yml)、[catalog.yml](../.github/workflows/catalog.yml)、[部署脚本](../scripts/deploy-website.sh) | CI 构建草稿；公开后目录及部署包；本机上传服务器 | 增加 Linux 别名、正式文件区、同包核验与推广次序；不把服务器写权限交给 CI |

本轮源码版本为三端客户端/core `0.2.2`，Android code17（上个公开值16）；core 更新因共享更新器/UniFFI变化，不意味着笔记迁移。server0.1.4、协议2、local schema4/server schema2、包名、证书、keyring、安装兼容身份和公钥保持。已同步实际版本文件，发布包身份仍以产物核验为准。

## 3 桌面 Markdown 格式检查设计

### 3.1 界面与设置

在现有设置中增加“编辑器”分组，承载两个编辑器开关，不与更新网络设置混放。lint 关闭时不加载引擎；开启后当前正文才进入检查。编辑页提供轻量问题计数入口，用户主动打开问题列表，可查看规则编号、中文/英文说明和1起始行列。范围标记使用可辨识的提示样式，不把风格建议渲染成保存失败。

问题列表不自动抢焦点。点击问题先校验文档版本，再定位；预览模式需要定位时切到源码模式，键盘关闭列表后能回编辑器。只读正文允许检查/定位。开关切换通过 Compartment 重配置，保留撤销栈、选区、焦点和组合输入。

### 3.2 引擎和规则

已锁定 `markdownlint 0.42.2`＋`@codemirror/lint 6.9.8`，理由、许可、精确版本与兼容探针见 [ADR 0021](adr/0021-desktop-markdownlint.md)。运行包必须离线可用，不在用户机器启动 Node、联网检查链接或加载远程规则。

首版按[规范白名单](../spec/client-0.2.2.md#23-首版规则白名单)生成固定配置 `default: false`，只启用MD009、MD018/019、MD023、MD037/038、MD039、MD042；MD009保留合法双空格硬换行。不要调用上游完整默认集，也不强迫随记符合仓库README的排版习惯。嵌套引用/代码块按解析上下文识别；若发现与本软件Markdown语义冲突，须同步修订规范和回归用例。关闭规则不改变渲染器的危险HTML、图片和链接安全限制。

### 3.3 后台调度与预算

采用独立 module Worker，延迟加载和单任务合并；停输750ms后检查。每个Editor实例绑定空间/文档会话，规则随包固定；控制器以单调serial绑定正文快照，销毁实例终止Worker，只接收当前快照回执；正文更新时立即撤销旧标记或显示待检查，不能把过期问题应用到新文本。中文组合输入结束后重新计时。

大于128KiB UTF-8时停止实时检查，提示“大笔记已暂停实时格式检查”及“检查一次”；不降低1MiB正文上限。最多展示200条诊断，超出显示“200+”和截断说明，不把截断数当完整总数。手动检查也由 Worker 执行，编辑、切笔记、关闭开关、离开编辑会话均可取消；超时显示可重试状态。

初始超时预算2秒，仅作为待测参数；测量0/小文/128KiB边界/1MiB与大量问题样本后冻结。关闭任务应终止 Worker 或可靠中断，不以 Promise 仍在主线程运行冒充取消。结果和错误只存内存，日志不含正文。检查失败不阻止自动保存或安全关闭。

### 3.4 必须处理的语义差异

markdownlint 的解析规则与当前 Marked 预览可能存在差别，因此只作风格建议。中英混排、CRLF、emoji/代理对、Tab、代码围栏、任务列表与双空格硬换行必须验证行列/UTF-16位置换算；不能用字节偏移直接装饰 CodeMirror。首版不增加 `.markdownlint*` 配置读取、任意用户JS、自动修复或批量扫描全部笔记。

## 4 对照同步滚动设计

### 4.1 映射和协调

为当前渲染快照输出顶层块索引：源码UTF-16范围及对应预览元素的data-source-from/to数字属性。Marked只lexer一次，按每个顶层token.raw精确推进游标；嵌套列表/引用整体作为一块，在块内近似插值，不承诺嵌套项逐像素对齐。任务勾选的字符串查找不参与滚动映射；重复段落仍具有不同源码范围。

预览只增加程序生成的固定属性/数字ID，不把正文作为未转义属性或HTML写入DOM；原始HTML转义、图片阻止、受限链接及任务按钮快照校验继续执行。

Editor 向协调器暴露当前可视内容锚点和受控滚动方法，使用 CodeMirror 的行块/测量API，覆盖软换行与非等高行。预览用当前块元素几何位置建立索引，以可视区顶部落入的块及块内比例计算目标位置；一块对应多行时做局部插值，允许与渲染折行存在小幅差异。

协调器通过 `requestAnimationFrame` 合并滚动；最近一次真实用户滚动成为控制方，程序滚动带来源/代次防环。必须覆盖滚轮、触控板、拖动滚动条、触摸与键盘滚动；另一侧出现真实用户输入时立即接管。边界归零、到达底部及高度不足一屏时单独处理，避免除零与来回弹跳。

### 4.2 重绘与性能

首次进入对照或重新开启同步时，以当前聚焦侧为准，无明确焦点时源码侧为准，只对齐另一侧。关闭后两侧独立，保持原阅读位置；再次开启按上述规则对齐，不记正文修改。

编辑触发的新预览在 DOM 完成后再测量；映射携带 documentKey/revision，旧映射立即失效。窗口/分栏宽度、字体及问题面板引起可视区变化后重新测量，保留控制侧锚点。切笔记、切空间、空白页、源码/预览单栏或卸载时拆除监听和在途 rAF。

滚动热路径不重新解析 Markdown、不遍历全文元素、不触发同步/保存，也不自动 `focus()` 或重置选区。块索引与预览共享一次解析，不再为滚动或 lint 重复主线程全文渲染。大笔记保持M2约束；128KiB/1MiB 对照模式需单独测量，不能以源码模式通过代替对照通过。

当前源码只在源码模式跳过预览解析，大笔记阈值主要跳过代码着色；不要把 [note-size.ts](../desktop/src/lib/note-size.ts) 的宽泛注释当成“所有大笔记预览已停用”的事实。对照仍显示预览，新增映射不能靠清空它获得流畅结果。

## 5 两个下载源设计

### 5.1 保持签名目录兼容

当前 `Release` 与目录拒绝未知字段；资产URL还要求本仓库 GitHub 路径。直接把 `url` 改为个人域名，或向旧payload追加 `mirrors`，会使旧客户端拒绝更新。本版采用 [ADR 0022](adr/0022-primary-download-source.md) 的固定镜像传输规则，不引入新签名格式：

1. 目录继续在现有feed分发，先按原信任根、序号/摘要、有效期和目标验签校验。
2. `Release.url` 保留已签名的规范 GitHub 地址；额外严格核对tag、版本与目标的标准文件名。
3. 选择 `github` 时使用该URL；选择 `memo` 时，从校验后的tag与文件名构造固定个人源URL。只作本地解析，不为获得URL向GitHub请求。
4. 两源均以该 `Release` 的相同size/hash、Windows更新签名或APK身份完成验证，仍用其身份执行安装前目录重验。不能以来源切换替换候选版本。

例如Windows 0.2.2规范地址为 `https://github.com/Koril33/djhx-memo/releases/download/v0.2.2/djhx-memo-0.2.2-windows-x64-setup.exe`，主源为 `https://memo.djhx.site/downloads/releases/v0.2.2/djhx-memo-0.2.2-windows-x64-setup.exe`。示例属于目标路径，不表示已上线。

个人源限定固定主机、443、无userinfo/query/fragment、精确路径及文件名；拒绝重定向，不接受百分号编码绕过、点段或前缀碰撞。GitHub仍允许其必需的受信CDN重定向，保留次数上限；不要把“个人源”悄悄302回GitHub。不使用第三方代理、来源提供的任意下载链接或同步账号HTTP客户端。

### 5.2 来源偏好与并发

共享core保存 `memo|github`，与原防回放 `state.json` 分开（建议更新私有根目录下版本化 `transport-preferences.json`）；不改旧state结构。缺失默认memo；来源文件损坏回退memo并在页内提示，原安全状态损坏仍保持 `UPDATE_STORAGE`。不能用切源重新初始化或迁移笔记库。

建议保留原 `preferences(auto_check, download_policy)` 接口，新增独立 `set_download_source(source)`，snapshot增加来源、来源写入错误及实际已下载来源；JNI/桌面桥接只传固定枚举。错误文案中英对应，非法枚举返回固定错误，不输出文件路径或输入原文。完成接口后更新core API/bridge文档及实际生成绑定。

来源修改必须纳入原生更新单任务门。下载中选择新源时先发取消、等待真实传输回执、删除半包，再持久化新选择；不能因设置页卸载而失去锁。UI就地显示“正在停止下载”，只约束更新操作，可返回、切页、继续编辑。若用户离页/Android进程被杀，重开显示实际持久化选择，不自动从半包拼接恢复。

选择完成后由用户重新点击下载，沿用大小与计费网络策略。完整已验缓存与来源无关，可保留并重验；界面区分“已选来源”与“本包下载来源”，避免把切源后复用的文件说成重新下载。安装准备、保存屏障或已提交系统会话期间禁用来源切换。

### 5.3 故障和过渡

两源选择首版不自动回退，网络失败给重试和切换按钮，不弹阻塞错误。超时、404、限流和TLS失败按固定分类提示；错误正文不入UI/日志。尺寸/哈希/签名错误停止，用户明确重试另一源时仍从头完整验证，不能把验证失败自动降级为浏览器安装。

旧0.2.1仍使用GitHub安装0.2.2；需以隔离旧更新器实测。旧版GitHub不可达时在官网手动下载并覆盖一次，新版后续才默认memo。目录仍在memo：全站或目录故障时，选择GitHub并不能保证检查/安装前重验成功；保留浏览器进入GitHub Release的人工恢复入口，不声称已提供完整灾备。

现有目录schema_min/schema_max固定3，而实际local schema4，已在[0.1.13记录](releases/0.1.13-validation.md#11-更新目录-schema_minschema_max-与本地-schema-4核实结论2026-11-21)核实为兼容标记遗留。该问题不阻断当前同schema更新，本版保持冻结字段；不能顺手写4或改成“目录格式3”。真正接入数据库范围门禁属于另一个旧更新器兼容设计。

## 6 Linux 最新 DEB 入口

Linux 保持 system-managed，无自动检查/下载/安装调度，也不将DEB塞入Windows/Android的安装targets。更新页显示当前应用版本、下载源选择及“下载最新DEB（在浏览器中打开）”；打开期间不用全局busy，也不暂停本地编辑。当前仅发行amd64，其他架构给下载页与支持说明。

主源使用 `https://memo.djhx.site/downloads/latest/djhx-memo-linux-amd64.deb`，服务器临时跳转到已验证的版本化文件；latest入口短缓存或no-cache，文件长期缓存且immutable。备用源使用GitHub的 `releases/latest/download/djhx-memo-linux-amd64.deb`，从0.2.2起在每个适用正式Release额外添加该固定名别名，并在SHA256SUMS中列出其与版本化DEB相同的hash。别名不改DEB内部Package/Version/Architecture。

发布必须检查GitHub latest确实指向包含该别名、适用Linux的正式Release；不依赖tag字符串或文件上传时间推断。将来单端发布不具备Linux资产时，不可把该Release推广为破坏此入口的latest。latest和官网始终按平台维护，不把不存在的平台新版本写成已发布。

撤回Linux发行物时同时修正个人latest与GitHub latest，指向仍适用的安全发行物；没有安全目标时明确暂停下载。不得改写旧版本DEB；自动更新目录的撤回仍使用更高sequence，不能把浏览器latest的可变指针当作自动降级规则。

应用不从上述跳转得到可自动安装句柄，也不声称浏览器下载经过客户端验签。构建/发布侧验证DEB元数据、哈希及别名；用户自行使用系统工具覆盖安装。保留版本说明与历史下载入口，启动浏览器失败时页内提示重试。

## 7 个人服务器与发布设计

### 7.1 静态文件区

个人服务器直接保存CI原始安装包，不实时回源GitHub。独立持久文件区映射到 `/downloads/releases/`，保存版本化EXE/APK/DEB、`.sig`与SHA256SUMS；与轮换的官网目录、Go数据目录分开，关闭目录列表和执行权限，不放源码、数据库或签名私钥。默认不自动删除旧包。

配置正确Content-Type/Content-Disposition、Content-Length及HEAD/Range响应；虽然服务器支持Range，首版客户端切源和中断后仍从头下载，不扩展断点续传。反盗链不能要求Referer/Cookie，否则会阻断更新器。自动下载主源必须200直返文件，Linux latest是仅供浏览器的例外跳转。未知文件返回404，不能退回首页HTML。

下载location单独设定并发、连接/速率及磁盘预算；具体值由服务器带宽、并发测量决定。不能全站限流使 `/v1`、SSE、`/healthz` 变慢；上线前测试并行下载期间同步延迟与本地编辑。当前未读取或修改生产服务器配置，磁盘路径/容量/带宽作为部署参数核实，不构成产品决策缺口。

### 7.2 官网清单

扩展单一下载清单，由每个平台的版本、标准文件名推导memo主链与GitHub备用链，不允许翻译脚本重写地址。每个下载磁贴主操作指向memo，邻近提供GitHub备用。静态HTML、无JS、真实JS执行后的两组地址、四语及主题均回归。仓库中旧静态清单版本不代表线上发行状态，继续由CI在正式构建时按已验证资产生成。

### 7.3 正式发布次序

1. 实施后先运行模块与隔离设备检查，审核workflow改动，再打tag；沿用CI创建draft、签署原证书APK和Windows更新包。release job不等于test job，分别核对结果。
2. draft增加Linux固定名别名；下载整套附件核对同包大小/hash、Windows签名、APK原证书/ABI/versionCode和DEB元数据。草稿只能放私有staging，不能提前提供正式公共URL。
3. 维护者决定公开Release后，再由现有catalog流程签署兼容v1目录、构建官网和部署包；bundle必须包含本轮要发布的三端文件/签名/校验清单。目录签名职责与服务器SSH权限保持ADR0019边界。
4. 本机部署工具先校验清单与路径，传到私有staging，再核hash；将不可变版本文件原子放入正式文件区。若同路径已有不同内容立即拒绝，禁止覆盖旧tag资产。匿名完整下载核验，不能只检查HEAD。
5. 验证个人源与GitHub、Linux别名后，再推广官网/latest/签名current。切换并非跨文件事务，窗口内必须保证所有新引用的资产已经可用；并发部署加锁，目录sequence严格递增。
6. 复核页面真实JS、下载、目录签名/有效期/序号、旧版→0.2.2覆盖安装及两源新版更新。明确记录平台、账号空间/离线outbox、输入与数据保留结果。

当前 [deploy-remote.sh](../scripts/deploy-remote.sh) 只做首页健康检查，且失败可回切旧站点/目录。扩展后不得在新current已被客户端读取后回退到较低sequence：文件健康检查尽量在推广current前完成；推广后的撤回/修复发更高序号，并保留所需资产。仅站点外观失败可回切页面，但必须独立保留最新签名目录和正式文件区。

本版需实际改造部署包、脚本及检查器，不能只修改README。发布前维护者按仓库规则审阅workflow；后续维护者已授权实现、测试与Release draft；根AGENTS要求workflow经维护者审阅后才打tag。本轮不部署服务器或公开Release。部署细节以[0.2.2运行手册](download-mirror-deployment.md)为准。

## 8 验收矩阵

以下是验收要求，实际已测/未测以[0.2.2验证记录](releases/0.2.2-validation.md)逐项区分。使用合成笔记、独立应用/数据与更新目录；每项记录平台、步骤、实际/预期、证据及未执行原因。

| ID | 场景 | 必须满足 |
|---|---|---|
| L01 | 新装/旧偏好、开关、重启 | 默认关闭；状态持久；Android无lint入口；关闭不加载引擎 |
| L02 | 白名单、合法硬换行/无标题/长行 | 仅启用既定规则；合法语义不被重写；中英规则说明可读 |
| L03 | CRLF、emoji、Tab、嵌套/围栏 | 诊断行列/范围正确；点选定位不串文档 |
| L04 | 中文组合、连续输入、撤销/选区 | 无丢字/中断；列表不抢焦点；正文保存内容不变 |
| L05 | 切空间/笔记、只读、关闭、Worker失败 | 拒绝旧回执；错误与0问题区分；输入/保存可用 |
| L06 | 128KiB边界/1MiB/超过200问题/超时 | 暂停实时、手动可取消、截断明确；不降低笔记上限 |
| S01 | 默认、双开关、重启、进入/退出对照 | 默认开；两个控件一致；单栏暂停；开关不写正文 |
| S02 | 双向滚轮/触控板/拖条/键盘 | 内容锚点跟随、用户接管、防反馈环，无持续抖动 |
| S03 | 重复段落、嵌套列表/引用、表格/长代码 | 映射正确，块内合理近似，横向独立 |
| S04 | 软换行、顶部/底部、短文、缩放/改宽 | 无除零、跳错块或边界弹跳；光标/选区/焦点保持 |
| S05 | 连续编辑/勾选任务/重绘/切笔记 | 同快照映射；旧rAF/监听不回写；内容和撤销正常 |
| S06 | Windows/Linux原生长文滚动 | 记录普通/128KiB/1MiB表现，滚动热路径不全文测量/解析 |
| D01 | 三端新装/旧装、来源持久化/损坏 | 两选项、默认memo；来源回退不清防回放或笔记数据 |
| D02 | memo/GitHub分别真实下载 | 字节相同；大小/hash、Windows签名/APK原证书均核验 |
| D03 | 非本仓路径、错tag/文件名、编码、query/重定向 | 拒绝非法个人映射；GitHub合法CDN可用，不扩大任意host |
| D04 | 下载中切源/返回/重进/杀进程/取消 | 单任务门保持；旧下载停妥；无半包拼接；编辑不阻塞 |
| D05 | 完整缓存后切源/安装前目录变化 | 缓存重验；撤回/候选变化不安装；来源标记准确 |
| D06 | 保存/IME失败、安装屏障、系统取消 | 输入/outbox保全，失败可恢复，安装中不能切源 |
| D07 | 超时、404、限流、TLS、哈希/签名错误 | 页内反馈；无隐式切源、无验签降级、日志脱敏 |
| D08 | 切源后旧目录/同序号变内容/状态损坏 | 拒绝回放/篡改；不通过改偏好恢复更新权限 |
| D09 | 0.2.1→0.2.2隔离真实覆盖升级 | 原v1目录与GitHub路径可用；旧数据、原身份/签名保留 |
| D10 | GitHub不可达旧版/官网目录不可达新版 | 人工过渡说明真实；不假报最新版或承诺完整灾备 |
| B01 | Linux两个latest入口/跨多次发布 | 分别得到最新适用DEB，非客户端内置旧版本；别名同hash |
| B02 | Linux离线/浏览器失败/非amd64 | 无自动任务/安装/提权；错误就地提示，不下载错架构 |
| P01 | 官网三端磁贴、无JS/有JS、四语/主题 | memo主链与GitHub备用一致，版本按平台，缺文件404 |
| P02 | draft/staging/同路径覆盖/站点轮换 | 草稿不公开；拒绝改写资产；旧文件不随页面清理消失 |
| P03 | Nginx下载类型/长度/Range/HEAD/并发 | 匿名可取、同包；不要求Cookie/Referer；同步/SSE健康 |
| P04 | 文件失败、目录已推广后故障、并发部署 | 先文件后引用；无低序号回滚；高序号撤回/恢复可复现 |

常规入口：`bash scripts/validate-local.sh desktop|core|android` 按模块分别执行（不是把竖线原样传参）；无Bash时按模块README运行原生命令。新增依赖后运行npm格式/lint/check/test/build及许可生成；core跑fmt/clippy/单元与更新器安全场景；Android重建JNI并跑JVM/lint/detekt/原生来源和系统安装回归；官网跑构建和真实浏览器链接回归。Windows、Linux、Android各自的原生结果不能互相替代。

0.2.1严格detekt有17项与0.2.0相同的遗留问题，见[原记录](releases/0.2.1-validation.md)；实施后重新比较，新增问题须修复，不通过宽泛baseline或删测试宣称绿色。未运行真机/系统/安装矩阵明确保留。

## 9 实施顺序和交付

1. 先完成依赖浏览器Worker与源码位置映射探针，形成实际性能数据；审核两个ADR和固定URL解析边界。
2. W1/W2在桌面独立实现并验证；W4先完成core来源偏好、路径校验、取消状态和测试，再接三端更新页。W3只接浏览器入口，不复用自动安装任务。
3. 同批更新官网生成器、Linux别名、CI部署包与服务器部署脚本；在隔离本地静态服务验证完整发行顺序、错误和撤回，再进行维护者授权的目标系统验收。
4. 行为实现时更新core API/bridge、自动更新规范、模块README/许可与验证记录；规范与代码同批提交。涉及保存/鉴权/迁移职责变化时按协作规范补独立交叉评审，不能把本文自查当作已交叉评审。
5. 模块检查完成后提交独立分支/PR；维护者审阅workflow后按授权创建0.2.2 Release draft。公开、服务器首次配置、双源实际下载及覆盖升级由后续推广任务验收，不把草稿当正式完成。

当前无产品方向待确认项。750ms/2秒/200条等为可测量初值；服务器文件区路径、磁盘/带宽预算和部署环境是实施参数。若实测迫使降低正文上限、取消双向同步、改变信任根/签名格式或扩大到Linux自动安装，必须回到维护者确认，不能作为微调自行改变范围。

## 10 参考资料与本轮检查

- [markdownlint 作者说明](https://github.com/DavidAnson/markdownlint)及[规则定义](https://github.com/DavidAnson/markdownlint/blob/main/doc/Rules.md)：浏览器支持、规则语义与MIT；本版浏览器集成尚待探针。
- [CodeMirror lint 官方实现](https://github.com/codemirror/lint)、[视图API](https://codemirror.net/docs/ref/#view.EditorView)：诊断、视图测量和滚动接口；不复制其他产品实现。
- [GitHub 固定名最新附件链接](https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases)：Linux备用latest地址依赖每个适用Release确实具有固定名别名。
- [Nginx静态HTTP模块](https://nginx.org/en/docs/http/ngx_http_core_module.html)、[下载请求限流](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html)：具体资源预算需在目标服务器测量。

开发前阶段只读检查结论保留历史含义；本轮已实施代码并运行模块及隔离原生探针，完整结果和未验收项见[0.2.2记录](releases/0.2.2-validation.md)。不将Windows探针、Android模拟器、CI构建或附件校验等同Linux GUI、真机/系统安装或生产部署验收。
