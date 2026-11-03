# android

Kotlin + Jetpack Compose 客户端，通过 UniFFI 使用 core 的数据库、搜索、回收站、导出和同步逻辑。v1 允许未登录使用，支持邀请注册/登录、Markdown 编辑/预览和前台自动同步；后台通过 WorkManager 尽力补同步。

Android 10+、arm64 真机是默认首版基线，application ID 为 `app.djhx.memo`。系统分享与全局快捷能力暂缓，不属于本轮工程。

当前没有 Gradle 工程、绑定或构建命令。下一轮先验证核心 SQLite 读写、回调和生命周期；产品验收见 [product-v1](../spec/product-v1.md)，平台验证见 [validation](../docs/validation.md)。
