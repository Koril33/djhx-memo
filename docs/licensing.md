# 许可证与第三方材料

本项目原创代码、文档及原创品牌母版按根目录 [MIT](../LICENSE) 提供，版权归各贡献者所有。选择 MIT 是为了方便学习、修改与复用；它允许第三方商用和再分发，要求保留版权及许可声明，不要求衍生项目开源。维护者本人不计划商用，不等于对使用者增加“仅学习/禁止商用”限制。[OSI MIT 条款](https://opensource.org/license/mit)与[开源定义第6条](https://opensource.org/osd)说明了这一边界。

MIT 只覆盖本项目有权授权的原创部分，不改写第三方许可、商标或他人权利。贡献者应只提交有权贡献的内容；提交贡献表示同意以本项目 MIT 条款提供该贡献，不要求额外版权转让。AI 生成/辅助代码仍须由提交者核验来源、许可和实际行为。

## 随包许可

| 材料 | 管理与分发 |
| --- | --- |
| 本项目原创内容 | 根 LICENSE；桌面 licenses/djhx-memo.txt，Android DJHX_MEMO_LICENSE.txt，服务端包 LICENSE |
| Rust / core / Tauri | [core 清单](../core/THIRD_PARTY_NOTICES.txt)、[桌面 Rust 清单](../desktop/THIRD_PARTY_RUST_NOTICES.txt)，含上游许可原文与来源 |
| 实际前端包 | [npm 清单](../desktop/THIRD_PARTY_NPM_NOTICES.txt)，打包时按实际 bundle 生成，不能只列直接依赖 |
| Android 与 Go | [Android 清单](../android/THIRD_PARTY_NOTICES.txt)、[Go 清单](../server/THIRD_PARTY_NOTICES.txt) |
| 操作图标 | [Lucide 完整 ISC/MIT 通知](../assets/ui-icons/LICENSE.txt)，源哈希及再生成见 [ADR-0011](adr/0011-offline-interface-icons.md) |
| Windows 安装模板 | 保留 Tauri 上游 [MIT 原文](../desktop/src-tauri/windows/LICENSE.tauri-template.txt)及版本来源 |
| Gradle Wrapper / 构建工具 | 保留上游 wrapper 与分发校验信息；JDK、Android SDK/NDK、MSVC、WebView2 等由开发者按各自条款安装，不作为 MIT 授权的原创材料重新许可 |

锁文件中的 `OR` 表示可选择一个许可分支，不能把 `AND` 当成可任选。部分 UniFFI/Mozilla 依赖为 MPL-2.0；本项目没有修改其上游源码，清单提供准确版本和可获取源码的来源。发布时须保留 MPL 源码获取信息；若修改受覆盖文件，另外履行该许可的文件级源码义务。JNA 采用清单列明的 Apache-2.0 许可分支。SQLite 的公有领域声明和其他上游通知均保留。

更新依赖、工具、图标或安装器模板时同时更新 ADR、锁文件与通知；核对最终安装包实际携带的文件。MIT 项目许可不等于“所有依赖都是 MIT”，也不替代对新版本依赖的审查。
