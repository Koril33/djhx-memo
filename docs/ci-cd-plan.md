# GitHub Actions CI/CD 可行性评估与建设计划

- 日期：2026-11-19
- 状态：评审完成；工作流分阶段实施。决策见 [ADR-0017](adr/0017-github-actions-ci.md)。
- 依据：仓库现有构建/测试入口、锁文件与工具链版本；`docs/client-release.md`、`docs/update-publishing.md`、各模块 README。

## 1. 结论

**可行，而且适合本项目。** 公开仓库使用 GitHub 托管 runner（Linux/Windows 免费）构建服务端包、Linux DEB、Windows NSIS 安装包与 Android APK，并运行可自动化的测试，把三端打包从“AI agent 在虚拟机逐台编译”变为“推 tag 即构建”。真正的难点是签名密钥的存放，本轮采用**路线 B（CI 只构建，签名留在本机）**规避。

自建 Gitea 无法提供稳定的供编译/打包的虚拟机（Gitea Actions 需自备 runner，三端无稳定自托管构建环境），因此 CI/CD 基础设施采用 **GitHub**。

## 2. 背景与决策理由

- 现状：Gitea 保存开发分支与评审；审核后的 `main` 与发布 tag 推到 [GitHub 仓库](https://github.com/Koril33/djhx-memo)。
- 痛点：三端打包目前由 AI agent 在各虚拟机手工编译，速度慢、token 消耗大、不可重复。
- Gitea 限制：Gitea Actions 依赖自托管 runner；没有稳定的 Windows/Linux/Android 编译虚拟机，Windows runner 自备尤其困难。
- GitHub 优势：公开仓库标准 runner 免费；`main`+tag 已推送 GitHub，触发方式与现有发布流程一致，不改变开发习惯。

## 3. 现有基础设施盘点

### 3.1 本地检查入口

`bash scripts/validate-local.sh <模块>`，每次选一个模块：

| 模块 | 执行内容 |
|---|---|
| core | `cargo fmt --check`、`clippy --all-targets -D warnings`、`cargo test --locked` |
| server | `gofmt -l`、`go vet`、`go test -race`、`golangci-lint run` |
| integration | 编译 server + `core::server_integration`（`--ignored`）+ `smoke.py` |
| desktop | `npm run check`、`npm test`、`npm run build`、`cargo fmt --check`、`cargo check --locked` |
| android | `./gradlew --no-daemon detekt lintDebug testDebugUnitTest assembleDebug assembleRelease` |

文档/契约检查：`python3 scripts/check-docs.py`、`git diff --check`；密钥审计：`python3 scripts/check-secrets.py`。

### 3.2 工具链版本矩阵（CI 必须与之对齐）

| 工具 | 版本 | 出处 |
|---|---|---|
| Rust | `rust-version` 1.99，实测 1.99.1 | `core/Cargo.toml`、`desktop/src-tauri/Cargo.toml` |
| Node / npm | Node 22.12+，实测 22.23.2 / 10.9.8 | `desktop/`、`docs/dev-setup.md` |
| Go | `go.mod` 声明 1.27.0，实测 1.27.3 | `server/go.mod` |
| JDK | 实测 JDK 21；产物目标 17 | `android/`、`docs/dev-setup.md` |
| Gradle / AGP / Kotlin | Wrapper 8.13 / AGP 8.13.0 / Kotlin 2.0.21 | `android/` |
| Android SDK | compile/target API 36，min 29，NDK 30.0.16248370（r30） | `android/`、`docs/dev-setup-linux.md` |
| Tauri | 2.12.5 | `desktop/src-tauri/Cargo.toml` |

CI 中必须在 workflow 内**显式安装并固定**上述版本，不依赖 runner 预装默认值；依赖用 `--locked`、`npm ci`、Gradle Wrapper 锁定。

### 3.3 产物构建入口（沿用现有，不新造）

| 产物 | 构建入口 | 附件名 |
|---|---|---|
| 服务端 Linux amd64 | `bash scripts/package-server.sh` | `djhx-memo-server-<版本>-linux-amd64.tar.gz` |
| Linux DEB | `desktop/`：`npm run tauri build -- --config src-tauri/tauri.linux.conf.json --bundles deb -- --locked` | `djhx-memo-<版本>-linux-amd64.deb` |
| Windows NSIS | `desktop/`：`npm run tauri build -- --bundles nsis -- --locked` | `djhx-memo-<版本>-windows-x64-setup.exe` |
| Android APK | 按 `android/README.md` 重建双 ABI JNI/UniFFI 绑定后 `assembleRelease` | `djhx-memo-<版本>-android.apk`（CI 产出 unsigned 前缀变体） |

## 4. 可行性分析（按产物）

| 产物 | Runner | 可行性 | 要点与风险 |
|---|---|---|---|
| 服务端 tar.gz | ubuntu-22.04 | 直接可做 | 静态 Linux amd64 二进制，无系统依赖难点 |
| Linux DEB | ubuntu-22.04 | 可做 | 需先 apt 安装 Tauri 前置依赖（webkit2gtk-4.1 等）；用较旧镜像提升旧发行版兼容性 |
| Windows NSIS | windows-2022 | 可做 | 原生 MSVC + WebView2；无私钥时需确认 `createUpdaterArtifacts` 不会导致构建失败 |
| Android unsigned APK | ubuntu-22.04 | 可做 | Rust core + UniFFI + JNI + Gradle；需显式固定 JDK/NDK 版本并安装 Rust 目标 |

## 5. 测试自动化评估

**能进 CI（托管 runner 可运行）：**

- core：格式、clippy、单元测试。
- server：gofmt、vet、`go test -race`、lint。
- desktop：`npm check/test/build`、`cargo check`（Linux 侧需 GTK/WebKit 依赖）。
- Android：`testDebugUnitTest`、`lintDebug`、`detekt`、`assembleRelease`（编译期校验）。
- integration（core+server 联调）：ubuntu runner 即可，不依赖 Android/Windows。

**CI 替代不了（仍本地人工，属于 v1 验收门槛）：**

- 真机/模拟器安装与升级验收、托盘/中文输入法/系统组合、三端设备矩阵、连续 7 天试用。
- Android 仪器测试（需模拟器/真机）、Windows 原生安装器实际运行。
- 官网签名目录部署与 Release 公开。

## 6. 签名与密钥策略（路线 B）

- CI 只构建**未签名**产物：Windows setup.exe（当前本就不做 Authenticode）、Android release-**unsigned** APK。
- Tauri 更新签名（`.exe.sig`）与 Android 原证书 keystore 私钥**不进入 GitHub**；维护者在本机用现有工具对 **CI 产出的同一文件**补签。
- CI 用自带 `GITHUB_TOKEN` 生成 `SHA256SUMS.ci`（传输校验）；最终 `SHA256SUMS` 由维护者签名完成后本地重生成并回读核对。
- 后续可选路线 A（secrets + Environment + Required reviewers + tag ruleset）需维护者明确授权后再做。

## 7. 分阶段实施计划

| 阶段 | 内容 | 触发 | 验收标准 |
|---|---|---|---|
| 0 文档对齐 | 修改 CI 相关文档表述，新增本计划与 ADR-0017 | 无 | 文档无“CI 暂缓”残留矛盾 |
| 1 服务端 + Linux DEB | `release.yml` 实现 `server`、`linux-deb` job | `workflow_dispatch` | 产物与本机一致；`dpkg-deb -I` 架构/版本正确 |
| 2 Windows 安装包 | 增加 `windows-exe` job，产出未签名 setup.exe | `workflow_dispatch` | 本机可安装启动 |
| 3 Android unsigned APK | 增加 `android` job | `workflow_dispatch` | 包名/versionCode/双 ABI 正确，处于未签名状态 |
| 4 汇总与校验 | `release` job 统一重命名 + 生成校验文件，tag 触发时创建 draft Release | `workflow_dispatch` | 附件按约定命名 |
| 5 tag 演练 | 加入 `push` tag `v*` 触发，用测试 tag 全流程演练 | 测试 tag | 得到完整 draft Release；演练后清理 |
| 6（可选）签名自动化 | 切换路线 A | 另行授权 | 仅维护者明确同意并完成密钥防护后 |

## 8. 触发与安全边界

- 触发：`push` tag 匹配 `v*` + `workflow_dispatch`。
- 顶层 `permissions: contents: read`，仅 `release` job 授 `contents: write`。
- 第三方 action 固定到完整 commit SHA；runner 固定镜像版本，不用 `latest`。
- 不覆盖、不删除、不移动已有 tag/Release 附件；Release 只创建为 draft。
- 不使用 `pull_request_target`；不在 `run:` 中直接插入不可信输入；不自动公开 Release。
- Gitea Actions 也会识别 `.github/workflows/`（当前无 runner 不触发）；若日后接入，需加 `if: github.server_url == 'https://github.com'` 防误触发。

## 9. 风险与待验证点

| 事项 | 处理要求 |
|---|---|
| Tauri 更新产物 + 无私钥构建 | 用 `--config` 覆盖关闭更新产物并确认 exe 本体不受影响；不伪造私钥 |
| Android 工具链版本随镜像漂移 | workflow 内显式安装并固定 JDK/NDK |
| CI 产物与本机哈希不同 | 以 CI 产物为准，不要求逐字节一致，发布记录中说明 |
| 首次编译时间长 | Rust/Node/Gradle 缓存 + 记录有/无缓存耗时 |
| Linux 镜像太新导致 DEB 依赖不满足 | 用较旧受支持镜像并在目标系统验证安装 |
| 公开仓库日志可见 | 日志不含内部地址、路径、凭据 |
| 计费政策变化 | 以 GitHub 官方文档为准；每个 job 设超时 |

## 10. 验收清单

- [ ] 文档对齐完成，无“CI 暂缓”残留
- [ ] `workflow_dispatch` 下四类产物均构建成功
- [ ] 版本检查在 tag 与仓库版本号不一致时失败
- [ ] 产物命名与 `docs/client-release.md` 一致
- [ ] Android unsigned APK 包名/versionCode/ABI 核验通过
- [ ] tag 演练得到完整 draft Release，无自动公开动作
- [ ] workflow 无自定义 secrets、无覆盖上传
- [ ] 演练产生的测试 tag 与 draft Release 已清理

## 11. 回滚

CI 只新增 workflow 与文档，不改变既有发布流程。出现问题时可删除对应 draft Release 与测试 tag，必要时撤回 `.github/workflows/` 提交，仍按原本机构建流程发版。
