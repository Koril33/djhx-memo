# ADR-0009 离线代码着色

桌面和 Android 使用核心中的同一套语言规则。沿用 pulldown-cmark 识别围栏和源码位置，使用锁定的 [syntect 5.3.0](https://docs.rs/crate/syntect/5.3.0) 解析语法，配合 [two-face 0.5.2+bat-0.26.1](https://docs.rs/crate/two-face/0.5.2%2Bbat-0.26.1) 提供由 bat 项目整理的语法包，覆盖 TypeScript、Kotlin、Dockerfile 等。语法包随 bat 更新。使用随 crate 编译的 Oniguruma，保留包括 PowerShell 在内的完整语法集。syntect 为 MIT 许可，two-face 为 MIT 或 Apache-2.0，Oniguruma 为 BSD-2-Clause。随包分发 [核心通知](../../core/THIRD_PARTY_NOTICES.txt) 和 [语法通知](../../core/licenses/two-face-syntaxes.txt)。升级 two-face 时从对应版本的 `two_face::acknowledgement::listing().for_syntaxes()` 重新提取语法通知。

高亮经平台桥接返回固定类别与 UTF-16 区间，复用两端的主题与现有编辑器。正文与语法在设备上处理。Markdown 预览继续使用各端现有解析器，桌面仍对代码文本进行 HTML 转义。
