# Android 平台独立评审

- 评审者：主协作者（未编写 Android UI/平台适配）。
- 日期：2026-11-04；范围：CoreGateway、MemoModel、SourceEditor、MainActivity、KeystoreCredentials、MemoApplication、MarkdownSafety。

## 发现与要求

1. 冲突 redirect 在组合输入或未提交缓冲存在时，不得先更新 selected/generation。必须延后消费映射，先按原笔记和原代次提交；失败保留缓冲和原基准并提供救援。否则会把原输入错误提升至目标最新版本，覆盖独立更新。
2. WorkManager 不能对所有 error 状态一律重试，须据 SyncInfo.error.retryable 区分临时失败与永久错误/需要重新登录。
3. CredentialStore 的无 throws 回调不得让平台删除异常穿透 UniFFI；偏好删除和 Keystore key 删除须分别尝试，任一路失败不阻止另一路。核心持久注销标记作为额外保护。
4. create 确认未知后的幂等重试可能返回旧 NoteView，须按返回正文/代次确认，再持续提交续写或清空内容；切换前必须完成这一过程。

以上四项已由实施协作者修复，评审者重新读取关键路径确认：组合期间不消费 redirect、原代次 flush 在锁外执行；Worker 仅对明确 retryable 错误重试；凭据回调拦截平台异常并双路撤销；flush 按实际返回内容持续 drain，创建未知后清空也不跳过原请求确认。后台首次建立核心默认 foreground=false，避免在没有 Activity 时持续持有 SSE。

最终签名 APK 纳入核心 `0549c3e`，Android 最后修正为 `f55effa`。11-04 执行的 11 项仪器测试通过；11-05 引用任务列偏移增量经 6 项 JVM 测试、detekt 与 release GUI 验证，Lint 0 errors/19 warnings。范围包括上述回归、真实 Go 双设备、同版本 Pull 后继续保存、IME 与 Keystore 异常。release 安装、实际编辑/任务、强停重启、SAF ZIP 内容及同签名覆盖安装保留数据已验证；详情见 [Android 验证](../android-validation.md)。这些平台测试由实施协作者运行，本报告不将其描述为评审者亲自操作。

## 已核对

UI 与 WorkManager 共用单例核心；网络和数据库不在主线程运行；缓冲提交有 300ms 防抖/1s 上界与 generation 检查；保存失败保留编辑器并阻止切换。源码编辑器保留原生 IME composing span，预览不使用 WebView、脚本、HTML 或自动外链图片。token 用每空间独立 Keystore AES-GCM 加密，不返回 UI；发布构建禁止明文 HTTP，调试与发布 application ID 分离。

物理 arm64 手机和 7 天日常使用尚未验收，不以模拟器或交叉编译替代。
