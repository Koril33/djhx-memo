# Android 0.1.1 界面预览验证

日期：2026-11-08。本轮按 [产品规范第 11 节](../spec/product-v1.md#11-011-界面预览轻量与统一)调整展示和入口，基于原 0.1.0 core；不改变协议、数据库、同步、鉴权或凭据身份，不能据此视作 v1 发布。历史验证仍保留在 [0.1.0 Android 记录](android-validation.md)。

## 范围与实现

- 列表显示 DJHX Memo、简短空间名、搜索、标题与一行摘要/时间，以及右下新建按钮。回收站、整个当前空间/回收站导出和设置移入更多菜单；进入编辑后不显示重复品牌或全局底部操作栏。
- 编辑顶栏提供返回、空间/保存状态、预览/源码和更多。状态展开区分本机保存与云同步，保留最近同步和手动同步；保存失败、鉴权、恢复和冲突继续显示必要提示、重试或救援，不用装饰性正常状态掩盖失败。
- 设置分为显示、空间、设备、数据。登录/注册表单按需打开，保留邀请码、导入事实与危险操作确认；恢复空间行显示批次时间以便区分，当前空间具有选中/单选语义。窄屏或大字号的分组使用可滚动单行标签，避免拆开英文单词。窄屏保存救援按钮改为可换行布局，登录主按钮与次操作纵向排列。
- 使用系统字体与统一纸白/炭灰配色，浅色次级字最终为 `#6B726B`。操作图标由锁定的 Lucide 1.70.0 源生成，24dp 图形、48dp 图标按钮、本地化可访问名称及长按提示；没有引入新运行时依赖。随包保留 Lucide ISC 许可。
- EditText 在源码/预览间保持挂载与同一实例；隐藏时禁用并退出无障碍树，焦点和键盘正确收起。等值正文不重新 `setText`，保留原生撤销；组合输入在进入预览时提交但不丢字。回收站只读，不能通过隐藏编辑器修改。
- 保留中文/英文、系统/浅色/深色和上海/UTC/系统时区偏好，默认服务器保持 `https://memo.djhx.site`，用户已填地址与账号数据不重置。主菜单、设置下拉及确认对话框的独立 Compose 窗口均显式继承当前本地化配置。

## 自动检查

本机使用 JDK 21、Android SDK 36、Build Tools 36.1.0、已生成的 JNI 绑定与两种 ABI 库。设备为 `djhx_dev_api36` / `emulator-5554`，Android 16 / API 36，x86_64、1080×2400、420dpi，KVM 与 SwiftShader。

| 检查 | 实际结果 |
| --- | --- |
| `bash scripts/validate-local.sh android` | 通过：detekt、lintDebug、testDebugUnitTest、assembleDebug、签名 assembleRelease；33 秒 |
| JVM 测试 | 12 项通过：Markdown 4、展示/资源 6、重试策略 2；保留既有用例，新增单行摘要/Unicode 回归 |
| Android Lint | 0 错误、48 警告；依赖更新提示、保留旧资源/数量形式、现有平台与 API 使用提示，未为隐藏警告删除历史资源或修改依赖 |
| `./gradlew connectedDebugAndroidTest` | XML 实际 15 项：14 通过、1 跳过、0 失败，39 秒；既有 14 项全部保留，新增界面回归 1 项 |
| 最终设置语言/主题定向复跑 | 补回英文切换后 `Dark` 保持断言，保留 Appearance/Settings 断言；detekt 与测试 APK 构建通过，该方法设备复跑通过（4.6 秒）；最后完整套件继续包含此补强断言 |
| 演示截图 fixture | 同一界面回归再跑 1 项通过（11.78 秒），仅在隔离 debug 空间保留 4 条演示笔记供截图 |
| 发布与调试 APK | apksigner 验证成功，v2 签名；`zipalign -c -P 16 -v 4` 均成功 |

服务端联调测试因没有注入本轮隔离服务器/单次邀请码而按原有约定跳过；本轮不将它计为通过。Gradle 控制台尾行的 `Finished 16 tests` 含 runner 事件计数，实际用例数采用 XML 的 `tests=15, skipped=1`，不是 16 项通过。

完整设备套件覆盖实际 JNI/SQLite、中文/emoji、搜索与回收站、ZIP、Keystore、创建回包未知后的续写、保存防抖/旋转、组合 span、展示偏好与服务器地址持久化、重定向冲突缓冲和新布局。新增界面回归实际操作更多菜单、状态详情、设置四分组和当前空间选中语义；验证源码/预览往返保持同一 EditText、正文与原生 undo（含组合文字），删除确认后进入回收站只读、恢复，以及导出进入系统 DocumentsUI 后取消。

中途定向测试发现主更多菜单与对话框独立窗口缺少本地化 Context，已在对应 CompositionLocalProvider 中修复；布局后的无障碍节点按行匹配并刷新节点，原语言/主题断言保留。修复后原 4 项定向检查全部通过，再运行完整套件。独立静态审阅见 [界面预览评审](reviews/quiet-ui-review.md)。

## 安装包与真实覆盖升级

新产物独立归档，原 0.1.0 文件和校验条目均保留：

| 文件 | 字节 | SHA256 |
| --- | ---: | --- |
| `dist/android/djhx-memo-0.1.1-android.apk` | 32,904,360 | `6d5e3515b4533b28e053300ba8802cc5f51e0027cbdc439702d3c0a2c907296b` |
| `dist/android/debug/djhx-memo-0.1.1-android-debug.apk` | 38,109,236 | `b45c90dd47525c5ec003eb1fd76f98505c8a3f505b5193169263616cd1c56a32` |

发布 APK 的包名 `app.djhx.memo`、版本 `0.1.1` / code `2`、minSdk 29、targetSdk 36；包含 arm64-v8a 与 x86_64 JNI。发布证书 SHA256 仍为 `ec8a88aa7aad47d51fa07ffddb4a17bb69c4aad71ee3c0dd4b3ad0eb4f5cdcdb`，与原 0.1.0 相同。调试包仍为独立的 `app.djhx.memo.debug` 与 debug 签名。

在模拟器已安装的真实 release 0.1.0 上执行 `adb install -r` 成功，版本 code 从 1 升为 2。`firstInstallTime=2026-11-04 21:32:32`、`dataDir=/data/user/0/app.djhx.memo` 均保持，升级后 Activity 正常启动、进程存在。窄屏分组修正后的最终签名包再次覆盖成功，设备已安装 base.apk 的 SHA256 与归档包逐字节一致。未卸载、清数据或向 release 数据目录注入演示内容；所有 GUI fixture 使用独立 debug 包。此处证明覆盖安装/稳定身份及启动，不宣称逐条审计真实账号内容或完整云同步升级矩阵。

## 截图与人工交互

最终截图在本机忽略目录 `.local/android-ui-preview/final-screenshots/`，均为最终配色与同桌面一致的 4 条独立演示笔记：

- `android-zh-light-list.png`、`android-zh-light-editor.png`、`android-zh-light-settings.png`、`android-zh-light-status.png`。
- `android-zh-dark-editor.png`、`android-en-dark-editor.png`。

人工检查实际弹出 Gboard，`mInputShown=true` / `mIsInputViewShown=true`；正文避让，返回、预览与更多仍可触达，键盘展开时可打开更多菜单。截图为 `android-en-dark-editor-ime.png`、`android-en-dark-ime-menu.png`；此项验证键盘可见性与布局，不替代中文输入法组合输入矩阵。

320dp 宽、130% 字号下原固定分组拆词的问题已用可滚动单行分组修复，实际横滑可进入 Data，记录为 `android-en-dark-320dp-settings.png`、`android-en-dark-320dp-data.png`。登录主/次操作没有横向挤出，点击次操作成功打开注册与邀请码字段，没有提交登录/注册。不过该大字号视口底部的次操作文字仍有部分裁切，未据此宣称全部表单大字号验收通过；此视觉限制保留待后续修整。截图 `android-en-light-320dp-account-actions.png`、`android-en-light-320dp-register.png` 保留实际状态。未注入保存失败状态做窄屏人工检查；救援按钮的换行实现仅有静态复核与既有缓冲测试证据。

结束后 `wm size=1080x2400`、`wm density=420` 均无 override，`font_scale=1.0`、`show_ime_with_hard_keyboard=0` 已恢复原值，记录为 `.local/android-ui-preview/device-settings-restored.txt`。

原始自动截图同时保存在模拟器 MediaStore `Pictures/DJHX-Memo-ui-preview/`，其重复后缀不是不同产物版本。公开交付截图以最终归档清单为准，不含真实账号、密码或私人正文。

## 证据与限制

本机日志：`.local/android-ui-preview-final-check.log`、`.local/android-ui-preview-full-instrumented.log`、`.local/android-ui-preview/full-instrumented-results.xml`、`.local/android-ui-preview-final-assertion-build.log`、`.local/android-ui-preview-final-assertion-instrumented.log`、`.local/android-ui-preview-screenshot-fixture.log`。签名、zipalign、APK 元信息与覆盖安装前后 package 记录在 `.local/android-ui-preview/`；密钥及密码不入日志与仓库。

本轮没有物理 arm64 设备、Android 10/API 29 设备；APK 含对应 ABI 不等同于真机验收。原生输入连接/组合 span 自动测试不替代真实中文输入法完整矩阵；后台省电/厂商系统、全三端同步与连续 7 天试用均未因本轮 UI 预览通过。
