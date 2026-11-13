# Windows / Android 自动更新发布

0.1.5 已接入客户端和本地发行工具。原开发轮未部署目录或发布 Release；2026-11-13 的实际整合、三端包与部署状态以[发布记录](releases/0.1.5-validation.md)为准。产品规则见 [spec](../spec/automatic-updates.md)，实测与限制见 [验证记录](client-updates-0.1.5-validation.md)。Linux 更新安装仍交后续主机实现；当前工具只推广 Windows / Android。

## 1. 用户入口与首次升级

Windows 和 Android 均从“设置 → 软件更新”查看版本、下载、稍后和安装。默认自动检查、仅非计费网络自动下载；Windows 无法确认计费网络时只提醒，手动下载或“允许计费网络”仍可用。Android 退后台取消未完成下载，已完成的私有缓存保留。

Windows 点击“保存并重启更新”后进入 NSIS；Android 先允许此应用安装更新，再点击安装并在系统页确认。取消/失败会恢复使用，安装前先保全编辑和离线操作。未登录也可使用。新版本说明只作纯文本显示。

旧 0.1.3/0.1.4 没有更新器，必须先人工覆盖安装 U1 一次。正式启用前要同时完成：审核后的 U1 安装包、对应公钥、原 APK 签名及可匿名获取的有效目录。把此分支推上去不会自动激活现有安装。

## 2. 公钥与私钥的主机边界

[config/update-trust.json](../config/update-trust.json) 提交的是公开 feed 与两套公钥。目录钥验证版本/目标/撤回等信息，包钥验证 Windows Tauri 资产，APK 继续使用原发布证书；不能用任意新 APK 钥覆盖原安装。

本机首次生成已完成：私钥位于当前 Windows 用户的 `$env:USERPROFILE/.djhx-memo/update-signing/`，分别为 `catalog.key.dpapi`、`package.key.dpapi`。目录 ACL 仅授予生成用户，内容受 Windows DPAPI 保护；公钥另有 `.key.pub` 文件。**这个位置和 DPAPI 用户绑定都是本机配置，不是其他主机的公共前提。** 文件不在仓库内，仓库也忽略 `*.key.dpapi`。

DPAPI 文件只能由原 Windows 用户环境解密，复制该目录不等于有了可移植的备份。维护者需在钥丢失前，把私钥通过可信密码管理器/离线加密介质制作可恢复备份，并验证恢复；不能将私钥、解密口令写入 Git、聊天或命令历史。其他构建机取得同一钥后，使用自己的受控密钥环境，不重新生成信任根。

普通发布使用 [use-update-key.ps1](../scripts/use-update-key.ps1) 在子进程环境加载本机 DPAPI 钥，结束后恢复原环境；该助手不会打印钥。`initialize-update-keys.ps1` 仅用于尚无信任根的新项目启动，已有根会拒绝再次初始化。不要在每台主机上运行初始化来替换公钥；密钥桥接轮换尚未实现，丢失旧钥可能需要用户手动覆盖升级。

非 Windows 发布主机按自己的受控方式提供 `TAURI_SIGNING_PRIVATE_KEY`，必要时另设 `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`；不依赖本机 DPAPI 或代理端口。Android 继续按 [Android README](../android/README.md) 配置原 keystore；缺失时只能生成 unsigned release。

## 3. 先构建并验收资产

在已配置 MSVC/Rust/Node 的 PowerShell 中，从仓库根目录运行；先正常安装锁定依赖，签名步骤不负责补网络依赖。

```powershell
git status --short
npm.cmd --prefix desktop ci
cargo fetch --locked --manifest-path core/Cargo.toml
cargo fetch --locked --manifest-path desktop/src-tauri/Cargo.toml
& .\scripts\use-update-key.ps1 -Kind package -Program npm.cmd -ProgramArguments @(
    '--prefix','desktop','run','tauri','build','--','--bundles','nsis','--','--locked','--offline'
)
```

Tauri 配置启用 `createUpdaterArtifacts: true`，输出 NSIS `.exe` 和 `.exe.sig`。签名必须带版本 trusted comment，`tauri build` 自动生成；手动补签用 `signer sign --app-version <版本>`。Tauri 更新签名不等于 Windows Authenticode，现有代码签名状态仍单独记录。

原生更新器只从已验签的目录构造 `restore_update`，不调用插件的网络 `check`。Tauri builder 仍要求非空 `endpoints`，配置中保留静态目录地址满足初始化校验；JS updater 权限没有开放，不能把这个自定义信封当作普通 Tauri latest.json 直接调用。

Android 先重编 JNI、执行本地检查，再用原 keystore 构建签名 APK。核对包名 `app.djhx.memo`、0.1.5 的 versionCode 6、两种 ABI、实际 minSdk/证书摘要，并用 Android build-tools 的 apksigner 验签。模拟器的 debug/validation APK 不能当作正式 APK 发布。

## 4. 生成签名目录

[prepare-update-catalog.py](../scripts/prepare-update-catalog.py) 从本地 manifest 计算每份资产的字节数/哈希，签署原始 JSON，并调用共享 Rust 验证器核对目录、平台规则、文件哈希和 Windows 包签名。APK 证书/manifest 的原生检查仍由发布机 apksigner 和真实 Android 试验完成。

manifest 示例仅示意 Windows 已就绪，路径按本机修改，`commit` 必须填本次已审核并实际构建的完整 SHA；这些占位内容不能原样发布：

```json
{
  "sequence": "1",
  "targets": {
    "windows-x86_64-nsis": {
      "version": "0.1.5",
      "commit": "<40-character-reviewed-source-commit>",
      "notes": "Windows / Android 更新客户端",
      "file": "<absolute-path-to-setup.exe>",
      "url": "https://github.com/Koril33/djhx-memo/releases/download/v0.1.5/djhx-memo-0.1.5-windows-x64-setup.exe",
      "signature_file": "<absolute-path-to-setup.exe.sig>",
      "package_key_id": "package-2026-01"
    }
  },
  "withdrawn": []
}
```

Android 加入目标 `android-universal-apk`：`version/commit/notes/file/url` 相同含义，省略 `signature_file/package_key_id`，增加 `android` 对象，其 `application_id/version_code/min_sdk/certificate_sha256/abis` 来自实际 APK。格式和拒绝规则以 spec U8 为准，不能复用 debug 证书摘要。

```powershell
& .\scripts\use-update-key.ps1 -Kind catalog -Program python -ProgramArguments @(
    'scripts/prepare-update-catalog.py', '.local/update-input.json', '--output', 'dist/updates/stable'
)
```

输出 `catalog-<sequence>.json` 与原子替换的 `current.json`；默认有效期 30 天。同目录序号必须递增，版本化目录不覆盖，错误签名不能推广。CLI/校验失败只输出固定错误，签名私钥不作为命令行参数。目录钥与包钥不要混用。

## 5. 人工发布顺序，无 CI

1. 从审核后的同一提交/tag 构建、签名、验证三端中本次已就绪的资产。推送分支不等于完成评审。
2. 上传并公开 GitHub Release 资产与 `.sig`/校验值，确认最终版本化 URL 能匿名下载。不能先推广尚不可下载的包，也不覆盖旧资产。
3. 使用真实完整 commit、证书摘要和最终 URL 生成目录。只加入已就绪目标；后补平台时保留其他有效条目，增加序号。当前工具拒绝尚未实现的 Linux 目标。
4. 将版本化目录复制到官网静态 `/updates/stable/`，最后原子替换 `current.json`，使用短缓存 TTL。该路径不经过账号 API；签名信封与资产均无需登录。
5. 用隔离 U1 客户端执行 U1→U2 真实下载/更新，核对最新输入、离线 outbox 和所有空间。每个平台独立记录，不以“有附件”或“验证签名”代替安装验收。
6. 定期检查目录有效期；即使没有新版本，也需复核并用更高序号重新签发。过期只暂停更新，不影响本机记笔记。

撤回时以更高序号加入 `<target>@<version>`；已安装版本不远程卸载/降级。修复通常发布更高版本。目录回放保护不能解决根钥泄露，也不能证明一个全新安装拿到的是绝对最新目录；这些边界见设计与 spec。

## 6. 后续主机验证与故障定位

本机只验证 Windows/Android。Linux AppImage、自有 APT/Flatpak、后台持续下载、跨 schema/协议自动升级、根钥桥接均未启用。服务端数据库/协议不因更新器变更迁移。

普通本地检查沿用模块 README。Windows 原生更新界面烟测入口为 `desktop/scripts/update-smoke.mjs`，仅对独立 identifier 的 debug WebView2 使用，按需设置回环 `DJHX_WEBVIEW_TEST_URL`；本轮最终再次启动调试实例被自动批准审查拒绝，见验证记录。Windows 签名互通测试需先生成真实 NSIS，再设置 `DJHX_TEST_INSTALLER` 并显式运行 desktop 的 `signed_nsis` ignored 测试；测试只验资源/包签名，不执行安装器。

Android `UpdatePlatformTest` 的较新包案例需要隔离 APK 和 `updateApk` 参数；系统安装案例额外需要安装来源授权。测试版本覆盖参数只对 `-PisolatedValidation` 生效：`-PupdateTestVersionCode=7`、`-PupdateTestVersionName=0.1.6`，不会改变普通 release/debug 的 0.1.5 基线。在 PowerShell 中将每个 `-P...` 参数整体加引号。

仅收集固定错误码、版本、目标、阶段与哈希；不要记录响应正文、笔记、token 或完整系统安装 Intent。下载失败/签名失败保留正常使用；状态损坏时需核对来源再处理更新状态目录，不能通过 UI 重置序号来绕过防回放，更不能删除笔记数据库。

## 7. 本次 Android 待签名附件例外

维护者于 2026-11-13 明确授权先上传 `djhx-memo-0.1.5-android-debug.apk` 与 `djhx-memo-0.1.5-android-release-unsigned.apk` 作为测试/待签名资料。前者使用独立 `.debug` 应用身份，后者不可直接安装；两者都不覆盖原正式应用，也不进入稳定更新目录或官网正式 Android 下载链接。原正式 Android 下载继续指向已签名的 0.1.4。

持原证书的主机后续从同一 `v0.1.5` 标签重编 JNI/APK，验签、核对身份/versionCode=6 和覆盖升级后，**追加**正式 `djhx-memo-0.1.5-android.apk`，更新 SHA256SUMS、说明与官网 Android 链接，再以更高目录序号推广 Android 条目。保留调试/unsigned 名称可避免把已下载的待签名文件误认成正式包。不要移动标签或覆盖已推广的 Windows 包。

GitHub 普通 Release 可编辑说明并追加附件；不可变 Release 发布后不能增删/替换附件。本次已核仓库 release immutability 未启用，已核对本次公开 Release 为 `immutable=false`，不更改仓库全局发布策略。若后续政策改变，改用新版本追加，不绕过平台限制。
