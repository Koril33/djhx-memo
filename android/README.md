# Android 客户端

0.1.2 控件与滚动修正版，基于 0.1.0 core，v1 尚未发布。Kotlin + Jetpack Compose 原生客户端，通过 UniFFI 调用共享 Rust core。最低系统设为 Android 10（API 29），APK 同时包含 arm64-v8a 与 x86_64；实际验证设备见下方记录。发布应用 ID 为 `app.djhx.memo`，调试为 `app.djhx.memo.debug`，两者数据隔离。应用数据与加密凭据在 Android 私有目录；卸载或清除数据前请先导出。

## 构建

先按 [开发环境](../docs/dev-setup.md) 准备 Rust、cargo-ndk、JDK 21、Android SDK Platform 36、Build Tools 36.1.0、Platform Tools 与 NDK 30.0.16248370。当前 Linux 验证使用 Rust 1.99.1、cargo-ndk 4.1.2；项目没有固定 Rust 工具链文件，不能把其他主机的 stable 版本视作同一环境。项目自带 Gradle Wrapper 8.13（附 SHA256）、AGP 8.13.0、Kotlin 2.0.21 和依赖锁文件，无需安装全局 Gradle。本机私有 `env.sh` 只是路径快捷方式，新开发者不需要该文件。网络受限时可使用自己的代理；配置写在用户级 Gradle/Cargo 配置，不提交账号、代理凭据或本机绝对路径。

在仓库根目录执行 Linux/Bash 命令；以下 JDK/SDK 路径应按实际安装位置调整：

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370"
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
./android/scripts/build.sh
```

该入口先编译真实 core，生成 Kotlin 绑定并打包两种 ABI，然后运行 JVM 测试、detekt（包含 Kotlin 格式）、Android Lint 和 debug/release APK 构建。直接运行 Gradle 前也必须先运行 `android/scripts/build-native.sh`，生成文件不入仓库。依赖锁文件与 Wrapper SHA256 随源码提交。`uniffi.toml` 使用 Android cleaner 配置：API 34+ 用系统 cleaner，低版本回退 JNA，不能把生成绑定当作普通 JVM 默认配置。APK assets 随包包含 core 与 Android 运行时依赖的许可证通知；更新依赖后运行 `python3 android/scripts/generate-notices.py` 重新生成 Android 通知。

`build-native.sh` 当前是 Linux 主机入口：先生成供 UniFFI 读取的主机 `.so`，再输出 Android `arm64-v8a` / `x86_64` 的 `libdjhx_memo_core.so`。纯净检出没有 `app/build/generated/uniffi` 和 `app/src/main/jniLibs`，只执行 Gradle 不构成完整构建。绑定和 JNI 必须由同一 core 提交、同一 `uniffi.toml` 生成；不能下载不明预编译库或混用旧绑定。依赖图锁定不等于 APK 字节级可复现，JDK/Rust/NDK、生成物和签名密钥也影响最终文件。本轮 UI 修正复用已验证的 JNI，未重新声称完成全新机器的从零构建。

Windows 推荐在 WSL2/Linux 中按上述完整入口构建，并在该环境内安装 Linux 版 JDK/SDK/NDK，使用 Linux 文件系统中的检出目录；不要把 Windows SDK 的 `.exe` 工具目录当成 Linux SDK。Windows 原生的终端使用 PowerShell 和 `gradlew.bat`，例如在已经准备好同提交生成物的工作树中：

```powershell
# 在仓库根目录；替换为当前机器完整 JDK 和 SDK 路径。
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_NDK_HOME = "$env:ANDROID_HOME\ndk\30.0.16248370"
Push-Location android
try {
    .\gradlew.bat --no-daemon testDebugUnitTest detekt lintDebug assembleDebug assembleRelease
} finally {
    Pop-Location
}
```

该 PowerShell 示例仅是 Android Gradle 阶段，不会生成 JNI。正式 Android app 的 **Windows 原生 UniFFI/JNI 完整构建尚未实测，也没有对应的 PowerShell 构建入口**；当前 Bash 入口硬编码主机 `.so`，不能直接在原生 Windows 上照搬。需要从同提交 Linux 构建复制生成的 Kotlin 目录和两个 Android ABI 库，或全程使用 WSL2/Linux。此前 Windows 原生绑定探针验证不等同于本 app 完整构建通过。

- debug APK：`android/app/build/outputs/apk/debug/app-debug.apk`，使用 Android 调试签名，可对本机测试服务器使用 HTTP。
- release APK：`android/app/build/outputs/apk/release/`，仅支持 HTTPS；签名环境变量缺失时生成 unsigned APK。
- 正式可安装 APK 需要本机持有发布密钥，设置 `DJHX_ANDROID_KEYSTORE`、`DJHX_ANDROID_STORE_PASSWORD`、`DJHX_ANDROID_KEY_ALIAS`（默认 `djhx-memo`）和 `DJHX_ANDROID_KEY_PASSWORD`。不得提交密钥或密码，也不得将它们写进命令日志。持续升级必须使用同一签名密钥。

公开源码不包含发布密钥，开发者可直接构建独立 debug 包，或用自己的密钥签署 release；自行签名的包不能覆盖原发行者签名的已安装 release。`app-release-unsigned.apk` 也不能直接当作已签名安装包。不要为测试删除原应用数据或替换其签名身份。

格式整理使用 `cd android && ./gradlew detekt -Pformat`；随后再运行无 `-Pformat` 的 detekt 确认结果。

## 测试

```bash
cd android
./gradlew testDebugUnitTest detekt lintDebug
./gradlew connectedDebugAndroidTest
```

Windows 的 Gradle 命令相应改为 `.\gradlew.bat`。`connectedDebugAndroidTest` 会管理并卸载测试应用；如需保留现有 debug 数据，先构建 `assembleDebug assembleDebugAndroidTest`，使用 `adb install -r` 安装两个 APK，再运行 `adb shell am instrument -w -r app.djhx.memo.debug.test/androidx.test.runner.AndroidJUnitRunner`。这一路径需检查逐项 instrumentation 状态与汇总，不能只看 adb 进程退出码。release 测试始终覆盖安装，不卸载或清数据。

仪器测试除独立验证桥接 UI 协调的故障注入案例外，实际调用 JNI 和 SQLite，验证中文/emoji、重开持久性、搜索、任务源码勾选、回收站、ZIP、Keystore、持续输入保存、旋转与组合输入 span。服务端联调测试默认跳过；通过环境变量 `DJHX_ANDROID_TEST_SERVER_URL` 和 `DJHX_ANDROID_TEST_INVITE_CODE` 注入隔离测试实例地址及单次邀请码才运行（Gradle 将其传为 `serverUrl` / `inviteCode` instrumentation 参数）。不得将变量内容提交到仓库或打印到日志。邀请码只用于临时 fixture，不使用真实账号。模拟器访问开发机使用 `http://10.0.2.2:<port>`，此功能仅在 debug 构建允许。

0.1.2 的滚动复现、设备检查与安装包见 [客户端修正验证](../docs/client-fixes-0.1.2-validation.md)；0.1.1 的实际结果见 [Android 界面预览验证](../docs/android-ui-preview-validation.md)；0.1.0 的既有检查保留在 [Android 验证记录](../docs/android-validation.md)。

## 交互与边界

未登录即可新建笔记；空白草稿不创建数据库记录。正常输入 300ms 防抖、连续输入最多约 1 秒请求提交，失焦、切换及后台立即请求提交。仅成功事务返回后显示已保存；保存失败保留缓冲，可重试、复制或另存新笔记。源码编辑使用平台 ScrollView 承载同一 EditText，拖动、惯性和边界由系统处理，保留点击定位与长按选区；语法着色不重设文本或移除输入法组合 span。

列表提供品牌与当前空间、搜索、一行摘要和底部新建按钮；回收站、导出和设置由更多菜单进入。编辑页仅保留返回、空间与保存状态、预览和更多操作；点击状态可分别查看本机保存、云同步、最近同步时间及手动同步。重要错误与保存救援继续直接显示。源码与预览切换保留同一个 EditText、正文和原生撤销；预览及只读空间中的原生编辑器隐藏、禁用并退出无障碍树。

账号与设置包含邀请注册、登录、已保存空间切换、注销、本机笔记显式复制导入、恢复空间复制与设备吊销。本机内容默认暂不导入。预览只原生渲染 CommonMark AST，HTML 展示为文本，图片不加载，链接仅允许 http/https 并交系统浏览器。ZIP 由 core 生成，通过系统文件选择器创建新文档，失败不显示成功。

应用名与界面品牌统一为 **DJHX Memo**；debug 启动器名称为 **DJHX Memo Debug**。包名、签名、私有数据路径和 Keystore 身份保持不变，可用同一发布签名覆盖升级。图标母版和衍生步骤见[品牌设计](../docs/brand-guidelines.md)，不能单独手改某个 Android 尺寸。

设置按显示、空间、设备、数据分组；显示页提供语言（跟随系统／简体中文／English）、外观（跟随系统／浅色／深色）和显示时区（上海／UTC／跟随系统）。默认语言与外观跟随系统，中文系统使用简体中文，其余语言回退英文；默认时区为 `Asia/Shanghai`。偏好使用应用私有 SharedPreferences，只影响本机展示，重启有效。语言与明暗切换通过 Compose 重组更新现有原生编辑器，保留正文、选区和输入法组合 span；系统配置导致 Activity 重建时，ViewModel 保留编辑缓冲。日期均按独立选择的 locale 与 ZoneId 格式化，协议、存储、排序和导出仍保持原来的 UTC 语义。

操作图标由 [统一图标源](../assets/ui-icons/UPSTREAM.json) 生成，按 24dp 图形、48dp 触控区域显示，提供本地化可访问名称和长按提示。不要手改 `ic_ui_*.xml`；许可证随包位于 `assets/licenses/lucide.txt`。应用 Logo 与操作图标分别维护，配色与布局约定见 [界面设计](../docs/ui-design.md)。

首次登录／注册默认服务器为 `https://memo.djhx.site`。用户填写的其他地址作为本设备表单草稿保留，现有账号优先使用其已保存服务器地址。此偏好不保存密码、邀请码或 token。

英文资源位于 `app/src/main/res/values/strings.xml`，简体中文位于 `values-zh-rCN/strings.xml`。界面、可访问名称及错误消息使用资源 ID；错误按 core 稳定错误码映射固定提示，不显示服务端原始消息。新增可见文案须同时补齐两种资源及相同位置参数，并通过 `PresentationTest` 资源一致性检查；正文、账号名和设备名称不翻译。

UI、网络恢复触发及 WorkManager 共享应用单例 core；后台仅尽力执行，不承诺固定时间同步。UI 不实现同步、outbox、冲突裁决或数据迁移。安全凭据使用每个账号独立的 Keystore AES-GCM key，加密失败由 core 保持进程内凭据并提示用户。

独立评审及修复依据见 [Android 评审](../docs/reviews/android-review.md)。创建已提交但回包未知时，平台保留原创建请求内容，获取原 Note 后继续提交全部续写；只有返回正文与可见缓冲一致才显示保存成功。跨空间切换前先完成本地提交并清空旧空间的界面列表。
