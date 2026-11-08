# Android 客户端

0.1.0 自用候选，v1 尚未发布。Kotlin + Jetpack Compose 原生客户端，通过 UniFFI 调用共享 Rust core。最低系统设为 Android 10（API 29），APK 同时包含 arm64-v8a 与 x86_64；实际验证设备见下方记录。发布应用 ID 为 `app.djhx.memo`，调试为 `app.djhx.memo.debug`，两者数据隔离。应用数据与加密凭据在 Android 私有目录；卸载或清除数据前请先导出。

## 构建

先按 [开发环境](../docs/dev-setup.md) 准备 Rust、cargo-ndk、JDK 21、Android SDK 36 与 NDK 30.0.16248370。网络受限时可使用本机代理；代理写在本机 `~/.gradle/gradle.properties`，不提交到仓库。

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370"
./android/scripts/build.sh
```

该入口先编译真实 core，生成 Kotlin 绑定并打包两种 ABI，然后运行 JVM 测试、detekt（包含 Kotlin 格式）、Android Lint 和 debug/release APK 构建。直接运行 Gradle 前也必须先运行 `android/scripts/build-native.sh`，生成文件不入仓库。依赖锁文件与 Wrapper SHA256 随源码提交。`uniffi.toml` 使用 Android cleaner 配置：API 34+ 用系统 cleaner，低版本回退 JNA，不能把生成绑定当作普通 JVM 默认配置。APK assets 随包包含 core 与 Android 运行时依赖的许可证通知；更新依赖后运行 `python3 android/scripts/generate-notices.py` 重新生成 Android 通知。

- debug APK：`android/app/build/outputs/apk/debug/app-debug.apk`，使用 Android 调试签名，可对本机测试服务器使用 HTTP。
- release APK：`android/app/build/outputs/apk/release/`，仅支持 HTTPS；签名环境变量缺失时生成 unsigned APK。
- 正式可安装 APK 需要本机持有发布密钥，设置 `DJHX_ANDROID_KEYSTORE`、`DJHX_ANDROID_STORE_PASSWORD`、`DJHX_ANDROID_KEY_ALIAS`（默认 `djhx-memo`）和 `DJHX_ANDROID_KEY_PASSWORD`。不得提交密钥或密码，也不得将它们写进命令日志。持续升级必须使用同一签名密钥。

格式整理使用 `cd android && ./gradlew detekt -Pformat`；随后再运行无 `-Pformat` 的 detekt 确认结果。

## 测试

```bash
cd android
./gradlew testDebugUnitTest detekt lintDebug
./gradlew connectedDebugAndroidTest
```

仪器测试除独立验证桥接 UI 协调的故障注入案例外，实际调用 JNI 和 SQLite，验证中文/emoji、重开持久性、搜索、任务源码勾选、回收站、ZIP、Keystore、持续输入保存、旋转与组合输入 span。服务端联调测试默认跳过；通过环境变量 `DJHX_ANDROID_TEST_SERVER_URL` 和 `DJHX_ANDROID_TEST_INVITE_CODE` 注入隔离测试实例地址及单次邀请码才运行（Gradle 将其传为 `serverUrl` / `inviteCode` instrumentation 参数）。不得将变量内容提交到仓库或打印到日志。邀请码只用于临时 fixture，不使用真实账号。模拟器访问开发机使用 `http://10.0.2.2:<port>`，此功能仅在 debug 构建允许。

实际结果、设备信息和未验证项见 [Android 验证记录](../docs/android-validation.md)。

## 交互与边界

未登录即可新建笔记；空白草稿不创建数据库记录。正常输入 300ms 防抖、连续输入最多约 1 秒请求提交，失焦、切换及后台立即请求提交。仅成功事务返回后显示已保存；保存失败保留缓冲，可重试、复制或另存新笔记。源码编辑使用 EditText，语法着色不重设文本或移除输入法组合 span。

账号与设置包含邀请注册、登录、已保存空间切换、注销、本机笔记显式复制导入、恢复空间复制与设备吊销。本机内容默认暂不导入。预览只原生渲染 CommonMark AST，HTML 展示为文本，图片不加载，链接仅允许 http/https 并交系统浏览器。ZIP 由 core 生成，通过系统文件选择器创建新文档，失败不显示成功。

应用名与界面品牌统一为 **DJHX Memo**；debug 启动器名称为 **DJHX Memo Debug**。包名、签名、私有数据路径和 Keystore 身份保持不变，可用同一发布签名覆盖升级。图标母版和衍生步骤见[品牌设计](../docs/brand-guidelines.md)，不能单独手改某个 Android 尺寸。

账号与设置顶部提供语言（跟随系统／简体中文／English）、外观（跟随系统／浅色／深色）和显示时区（上海／UTC／跟随系统）。默认语言与外观跟随系统，中文系统使用简体中文，其余语言回退英文；默认时区为 `Asia/Shanghai`。偏好使用应用私有 SharedPreferences，只影响本机展示，重启有效。语言与明暗切换通过 Compose 重组更新现有原生编辑器，保留正文、选区和输入法组合 span；系统配置导致 Activity 重建时，ViewModel 保留编辑缓冲。日期均按独立选择的 locale 与 ZoneId 格式化，协议、存储、排序和导出仍保持原来的 UTC 语义。

首次登录／注册默认服务器为 `https://memo.djhx.site`。用户填写的其他地址作为本设备表单草稿保留，现有账号优先使用其已保存服务器地址。此偏好不保存密码、邀请码或 token。

英文资源位于 `app/src/main/res/values/strings.xml`，简体中文位于 `values-zh-rCN/strings.xml`。界面、可访问名称及错误消息使用资源 ID；错误按 core 稳定错误码映射固定提示，不显示服务端原始消息。新增可见文案须同时补齐两种资源及相同位置参数，并通过 `PresentationTest` 资源一致性检查；正文、账号名和设备名称不翻译。

UI、网络恢复触发及 WorkManager 共享应用单例 core；后台仅尽力执行，不承诺固定时间同步。UI 不实现同步、outbox、冲突裁决或数据迁移。安全凭据使用每个账号独立的 Keystore AES-GCM key，加密失败由 core 保持进程内凭据并提示用户。

独立评审及修复依据见 [Android 评审](../docs/reviews/android-review.md)。创建已提交但回包未知时，平台保留原创建请求内容，获取原 Note 后继续提交全部续写；只有返回正文与可见缓冲一致才显示保存成功。跨空间切换前先完成本地提交并清空旧空间的界面列表。
