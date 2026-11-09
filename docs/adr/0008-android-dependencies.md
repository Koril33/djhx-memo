# ADR-0008：Android v1 原生客户端依赖

- 状态：实施采用，实际设备验收见 [Android 验证](../android-validation.md)
- 日期：2026-11-03
- 依据：[ADR-0001](0001-v1-baseline.md)、[桥接契约](../../spec/core-bridge.md)

## 决策

采用已在本机验证的稳定工具链，固定版本而非动态追踪最新版本。直接依赖与传递依赖由 Gradle lockfile 锁定；Rust 库遵循 core 的 Cargo.lock。

| 依赖/工具 | 精确版本 | 许可证、官方维护来源 | 用途 |
|---|---|---|---|
| Gradle Wrapper | 8.13 | Apache-2.0，[Gradle](https://github.com/gradle/gradle) | 可重复构建，校验发行包 SHA256 |
| Android Gradle Plugin | 8.13.0 | Apache-2.0，[Google](https://developer.android.com/build/releases/past-releases/agp-8-13-0-release-notes) | API 36 构建及 APK 打包 |
| Kotlin Android / Compose compiler plugin | 2.0.21 | Apache-2.0，[JetBrains](https://github.com/JetBrains/kotlin/releases/tag/v2.0.21) | 原生 Kotlin / Compose 编译 |
| androidx.activity:activity-compose | 1.9.3 | Apache-2.0，[AndroidX](https://developer.android.com/jetpack/androidx/releases/activity) | Activity、SAF、Compose 容器 |
| androidx.compose.ui:ui / foundation / ui-tooling-preview | 1.7.5 | Apache-2.0，[AndroidX](https://developer.android.com/jetpack/androidx/releases/compose-ui) | 原生界面与布局 |
| androidx.compose.material3:material3 | 1.3.0 | Apache-2.0，[AndroidX](https://developer.android.com/jetpack/androidx/releases/compose-material3) | Material 控件 |
| androidx.lifecycle:lifecycle-viewmodel-compose / lifecycle-runtime-ktx | 2.8.7 | Apache-2.0，[AndroidX](https://developer.android.com/jetpack/androidx/releases/lifecycle) | 保留编辑缓冲与生命周期 |
| androidx.work:work-runtime-ktx | 2.10.1 | Apache-2.0，[AndroidX](https://developer.android.com/jetpack/androidx/releases/work#2.10.1) | 单进程 WorkManager 尽力后台同步 |
| org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.9.0 | Apache-2.0，[JetBrains](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.9.0) | IO 调度、保存防抖与互斥 |
| net.java.dev.jna:jna (aar) | 5.17.0 | Apache-2.0 / LGPL-2.1-or-later 双许可，[JNA](https://github.com/java-native-access/jna) | UniFFI 官方 Kotlin JVM/Android FFI 运行时，沿用 ADR-0002 |
| org.commonmark:commonmark | 0.24.0 | BSD-2-Clause，[CommonMark](https://github.com/commonmark/commonmark-java/releases/tag/commonmark-parent-0.24.0) | 解析 Markdown AST，原生渲染而非 WebView |
| detekt plugin / detekt-formatting | 1.23.8 | Apache-2.0，[detekt](https://detekt.dev/docs/1.23.8/gettingstarted/gradle/) | 静态检查与 Kotlin 格式检查 |
| junit:junit | 4.13.2 | EPL-1.0，[JUnit](https://github.com/junit-team/junit4) | Markdown 与保存调度单测 |
| androidx.test:runner / core | 1.6.2 / 1.6.1 | Apache-2.0，[AndroidX Test](https://github.com/android/android-test) | 真实 JNI / SQLite / Keystore 仪器测试 |
| androidx.test.ext:junit | 1.2.1 | Apache-2.0，[AndroidX Test](https://developer.android.com/jetpack/androidx/releases/test) | Android JUnit 集成 |

依赖通知以锁定的 releaseRuntimeClasspath 为准生成并随 APK assets 分发，附原制品许可证及版权文本；core 的通知与 Android 通知分别保留。UniFFI 使用官方 Android cleaner 配置，API 34+ 使用系统 cleaner、API 29～33 回退 JNA，不抑制 NewApi 检查。

Android SDK compile/target 36，最低 API 29（Android 10），JDK 21 编译为 Java 17 字节码。NDK 30.0.16248370，Rust Android 平台 29；打包 arm64-v8a 和 x86_64。发布构建不接受明文 HTTP；debug 构建可供本机联调。

## 凭据、编辑与预览

不引入第三方凭据框架。每个 profile 使用独立的 Android Keystore AES-256/GCM key 保护 token，随机 IV 与密文仅存应用私有 SharedPreferences，关闭系统备份；注销同时移除密文与该 profile 的 key，使旧密文不可解密。Keystore 失败由 core 提示凭据仅驻留进程。UI 不接触 token。

源码编辑用 Android EditText，语法着色只添加本应用的 spans，不重设输入文本，不移除 IME composing spans。CommonMark 只作为解析器；HTML 以文本展示，图片仅展示替代文本，不加载远端资源；链接仅允许 http/https 并交系统浏览器。

保存与后台工作均调用同一进程单例 MemoApp。UI 只管理未提交缓冲与提交调度，不实现同步、冲突、账号数据库与 outbox 逻辑。ZIP 由 core 生成，SAF 创建新文档并以完整写入成功为导出成功条件。
