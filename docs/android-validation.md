# Android 0.1.0 自用候选验证

- 验证日期：2026-11-04～2026-11-05（Asia/Shanghai）。这是候选实现记录，v1 尚未发布。
- 独立评审：[Android 平台评审](reviews/android-review.md)。发现的保存基准、后台重试、凭据异常及创建回包未知问题已修复，并各有专用回归。
- Android 实现提交：`cc79e5a`，最终源码修正：`f55effa`；核心冻结提交：`0549c3e`。
- 构建依赖：[ADR-0008](adr/0008-android-dependencies.md)，源码与构建入口：[Android README](../android/README.md)。

## 设备与工具

本机 Linux x86_64、JDK 21.0.12.1、Gradle Wrapper 8.13、AGP 8.13.0、Kotlin 2.0.21、Rust 1.99.1、UniFFI 0.32.2、NDK 30.0.16248370。compile/target SDK 36，最低 API 29；Rust Android 编译平台 29。

实际运行设备为专用 AVD `djhx_dev_api36`，Google APIs/Play Android 16（API 36.1 镜像，系统报告 SDK 36）、x86_64、1080×2400、1536MiB、2 核、KVM + SwiftShader。未操作用户其他 AVD。arm64-v8a 完成编译与打包，没有物理 arm64 手机，不能视为真机验收。

## 自动验证

先运行 `android/scripts/build-native.sh`，再以本机私有环境变量提供发布签名及隔离 Go 测试服务参数：

```bash
cd android
./gradlew --no-daemon testDebugUnitTest detekt lintDebug assembleRelease connectedDebugAndroidTest
```

服务端使用独立数据库与 epoch，以 `--dev-http` 监听开发机 18082；模拟器连接 `http://10.0.2.2:18082`。注册邀请码在运行时生成并通过环境变量注入，不使用真实账号，不提交或打印凭据。测试服务器、日志、密钥和测试数据仅在仓库忽略的 `.local/` 下。

| 验证 | 实际结果 |
|---|---|
| Kotlin 编译、格式与 detekt | 通过；源码检查不包含生成绑定 |
| JVM 单元测试 | 2026-11-05 共 6 项通过：危险链接、HTML/图片安全、任务源码行号与代码排除、引用内任务的列偏移、后台临时/永久错误策略 |
| Android 仪器测试 | 2026-11-04 共 11 项通过、0 跳过，详见下表；11-05 的引用任务增量另经 JVM 与 release GUI 验证，未重复全部仪器测试 |
| Android Lint | 0 errors，19 条提示；主要为固定依赖版本/target SDK 更新建议及 KTX 风格建议；未屏蔽 NewApi |
| release/debug APK | 两种 ABI 均编入，debug 与 release application ID、签名及私有数据隔离 |
| APK 签名与对齐 | apksigner v2 签名验证通过；zipalign 16KiB 检查通过 |
| 原生 ELF 对齐 | 2026-11-05 主协作者独立解析最终 APK 的全部 6 个 `.so`：arm64-v8a/x86_64 的 core、JNA、AndroidX graphics 各 PT_LOAD 对齐均至少 16384 字节 |
| 许可通知 | APK assets 中 core/Android 通知分别与仓库源文件逐字节一致 |

| 仪器测试 | 真实验证内容 |
|---|---|
| CoreBindingTest（3 项） | 真实 JNI/SQLite 创建、中文/emoji、任务勾选、重开、短词搜索、软删除恢复及 ZIP；Keystore 密文跨适配器读取/删除；偏好平台异常不穿过回调且 Keystore 撤销仍执行 |
| EditorLifecycleTest（2 项） | 连续输入期间已提交，停止后保存完成；旋转保留编辑状态；中文组合输入的 span 经着色与自动保存后保留，独立更新事件不替换组合缓冲，结束组合后更新 |
| ServerIntegrationTest（1 项） | 真实 Go 注册、本机内容默认不导入、显式幂等复制、双设备同步、自己的 ack/pull 不抬 generation 且可按原代次续写、吊销后需重新登录但本地内容保留、注销回本机空间 |
| RedirectBufferTest（2 项） | 严格假桥仅验证 UI 并发协调：目标已有独立更新时，未提交/组合缓冲不会被提升为目标最新 generation；保存失败保留原 id、代次和正文 |
| CreateReplayTest（2 项） | 严格假桥模拟创建已提交但回包未知：原 request_id 重试先确认原 Note，再保存全部续写或清空；切换前完成 drain，不将旧内容误标为已保存 |
| CorePerformanceTest（1 项） | 真实 JNI/SQLite，1,000 条笔记、每条约 2KiB、打开和两字中文搜索各 30 次 |

2026-11-04 最后一项性能实测：打开 P95 **2.365169 ms**；两字中文查询返回 1,000 条 P95 **87.520691 ms**。它是模拟器核心调用的测量，包含 JSON/JNI 成本；不等于真实 arm64 手机的应用冷启动与编辑器打开 P95，也不替代三端前台同步五秒目标的完整验收。

## release APK 实操

在上述 AVD 安装真正的签名 release APK，实际操作：新建 → 原生源码输入 → 自动保存 → Markdown 预览勾选任务 → 源码确认只改变任务标记 → Home 后 force-stop → 重启 → 已提交正文与任务状态保留。随后使用系统 SAF 的 Downloads 新建 ZIP，拉取导出文件核验：一个 UTF-8/LF Markdown 文件及 manifest，正文和已勾选任务与界面一致。截图与导出样本保存在 `.local/`，不含真实笔记。

2026-11-05 对最终增量 APK 执行同签名覆盖安装，原笔记仍可见；新增引用中的任务经预览勾选后确认只改变对应标记，引用前缀与源码行保持不变。

上述实操以 release 构建执行；HTTP 的真实服务端联调仅在独立 debug 构建执行。release 由核心拒绝非 HTTPS，Manifest 同时禁用明文流量。Android 12+ 显式排除云备份/设备转移，较低版本的 Manifest 同时设 allowBackup=false / fullBackupContent=false；导出由用户主动选择。

## 安装包与密钥

- 发布候选：`dist/android/djhx-memo-0.1.0-android.apk`，application ID `app.djhx.memo`，versionCode 1。
- 调试：`dist/android/debug/djhx-memo-0.1.0-android-debug.apk`，application ID `app.djhx.memo.debug`。
- 两者旁均有 `SHA256SUMS`；release SHA-256：`429325866c0ed24941d339bcc702d6214d8ca4a2cca245489fdb4cd809ebd0bd`。
- APK 同时包含 arm64-v8a / x86_64 的 core、JNA、AndroidX graphics 原生库。
- 本次为项目生成独立发布密钥：`.local/android-release.jks`，口令文件 `.local/android-signing-password`，均不入仓库。证书 SHA-256：`ec8a88aa7aad47d51fa07ffddb4a17bb69c4aad71ee3c0dd4b3ad0eb4f5cdcdb`。后续升级必须保留并备份这两个本机文件，不能改用 debug 签名覆盖真实数据安装。

2026-11-05 已另作仓库外的本机长期副本：`~/.local/state/djhx-memo/signing/`，目录权限 0700、两文件权限 0600，并逐字节核对一致。原构建路径保留；此本机副本不等于异地保管，后续由项目负责人自行离线备份。

## 未验收项

物理 arm64 手机、Android 10/API 29 实际运行、不同手机输入法/电池策略、后台 15 分钟周期与 Doze/休眠/断网恢复的长期行为、三端同账号 GUI 完整互通及前台端到端性能、真实磁盘满/迁移失败救援、1,000 笔记应用冷启动 30 次统计、连续 7 天日常使用均未完成。后台仅尽力执行，不承诺固定时刻或五秒同步。

测试通过不能替代这些发布条件；本版本作为 0.1.0 自用候选交付。正式发布前继续按 [全局验收](validation.md) 补齐。
