# ADR-0005：核心实现依赖与可重复构建

- 状态：本轮实施采用，交付前独立评审
- 日期：2026-11-03

## 决策与维护来源

核心采用 rusqlite 0.41.1（MIT，rusqlite 项目），关闭默认 WASM 后端，显式选择原生 libsqlite3-sys、bundled、backup；SQLite 为公有领域。沿用原生探针已选 UniFFI 0.32.2（MPL-2.0，Mozilla）生成 Kotlin 绑定。

HTTP 使用 reqwest 0.13.5（MIT/Apache-2.0，reqwest 项目）；TLS 使用 rustls 0.23 系列与 webpki-roots 官方根证书集合，显式验证证书与主机名，不采用忽略证书错误或 Android 私有 JNI 初始化。协议处理使用 serde 1.0.229 / serde_json 1.0.154（MIT/Apache-2.0）；UUID 使用 uuid 1.29.0 的密码学随机 v4（MIT/Apache-2.0）；时间使用 chrono 0.4.45（MIT/Apache-2.0），仅用于展示和重试，不用于版本裁决。

导出使用 zip 9.3.0（MIT，zip-rs）且关闭无关压缩/加密特性；base64 0.22.1（MIT/Apache-2.0）仅作平台桥接 ZIP 传输。迁移校验使用 sha2 0.10.9（MIT/Apache-2.0，RustCrypto）。任务标记定位使用 pulldown-cmark 0.13.6（MIT，pulldown-cmark 项目），共享 CommonMark 解析语义，避免 Android/桌面各自修改代码块内容。

所有传递版本以提交的 Cargo.lock 为准；平台项目分别提交 npm 锁文件与 Gradle Wrapper/依赖约束。各平台额外依赖另记 ADR。

2026-11-04 以 `cargo metadata --locked --offline` 核对 225 个包的 SPDX 声明与本机 crate 许可证文件，完整文本收集于 [core/THIRD_PARTY_NOTICES.txt](../../core/THIRD_PARTY_NOTICES.txt)。清单包含可选跨平台依赖；OR 许可选择 MIT/Apache 等可兼容分支，Mozilla UniFFI 保留 MPL-2.0 原文与上游源码获取地址，未修改上游源码。生成入口为 [cargo-notices.py](../../scripts/cargo-notices.py)。核心与平台安装包分别携带通知。

## 构建环境

宿主 Debian 13 已安装的 systemd 运行库版本与当前软件源开发包不匹配。为避免降级或替换主机系统组件，Linux 桌面构建使用本机 Docker 的 Ubuntu 24.04 临时构建环境，再在真实 Linux 桌面验证安装包。容器仅用于本地构建，不建立 CI/Runner；用户已提供的 Debian/Ubuntu/Windows 虚拟机用于实际部署与安装验收。

凭据由各平台系统服务实现，核心仅通过回调访问；凭据服务不可用时 token 仅驻留内存。发布客户端只允许 HTTPS，本地 HTTP 由调试构建显式开启。
