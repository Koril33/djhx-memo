# Android安装兼容修复验证

日期：2026-11-14。行为见[规范](../spec/android-install-compatibility.md)，独立只读评审见[评审记录](reviews/android-install-compatibility-review.md)。Android候选0.1.9/code10，尚未发布；Windows/Linux继续0.1.8，core0.1.7/server0.1.4/协议/schema不变。不移动v0.1.8，不替换已发布附件或签名目录。

## 真实问题与已恢复状态

Xiaomi Civi、Android13、HyperOS1.0.2.0.TKVCNXM，0.1.7下载0.1.8且允许来源授权后，未出现系统安装确认页。维护者用手机浏览器下载同一官方APK并覆盖安装成功，原笔记正常。官方0.1.7/code8与0.1.8/code9均为app.djhx.memo、相同原证书；不能把问题归因于两份官方包签名不连续。

源码确认存在失败分类丢失和确认Intent URI往返的缺口，但尚无该机系统失败码或ADB证据，不能断言唯一根因。0.1.8 Release说明已追加该机限制与手动覆盖办法；该恢复结果不能当作新兼容入口已验收。

## 环境与隔离

本机Windows、Android Studio JBR、SDK/NDK30.0.16248370、Gradle Wrapper8.13；实际设备为API35 x86_64模拟器。使用`.validation014`隔离身份、合成中文/emoji笔记，直接`adb install -r`及instrumentation，不运行会卸载应用的connected任务，不读取日常笔记。测试APK fixture只进入androidTest，不进入应用资产。

主检查入口为Android目录中的PowerShell命令：

```powershell
.\gradlew.bat --no-daemon detekt testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest -PisolatedValidation
```

真实安装页测试额外通过`-PupdateFixtureApk=<本机较高版本隔离APK的绝对路径>`准备同调试证书code11 fixture，并以`updateApk=asset:update-candidate.apk`传入。它验证平台预检、FileProvider、Activity Result与系统确认/取消边界，不伪装成已从生产签名目录完成正式升级。

## 检查结果

| 检查 | 实际结果 |
|---|---|
| `detekt testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` | 无格式自动修正参数的最终检查通过；13项JVM单测，0失败 |
| `android/scripts/build.ps1 -NativeOnly` | Windows宿主元数据、arm64/x86_64 JNI及UniFFI绑定按锁文件重新生成；core仍0.1.7 |
| API35隔离完整instrumentation | 56项，52通过、4条件跳过、0失败；逐项解析状态，未把进度通知计为测试通过 |
| `assembleRelease lintRelease` | 原发布证书签名构建与Release Lint通过 |
| 文档与公开边界 | 文档链接/OpenAPI引用、Git差异空白检查通过；Gitleaks公开工作区、可达历史、提交元信息均0发现，不代表绝对无漏洞 |

新增14项仪器回归覆盖：完整确认Intent与重建、固定错误分类持久性、单文件只读URI、复制损坏拒绝、合法路径别名、provider失败清理、旧尝试回调隔离、Registry同步回放、撤销/记录失败恢复、原生失败记账异常、core恢复失败重试、后台Activity拒绝交接，以及真实系统确认页的打开/取消。原会话路径、同版本/错证书拒绝也通过；取消后原ID中文/emoji正文仍在，未知结果不误报安装成功。

4项跳过为账号永久删除HTTP联调、邀请登录/双设备同步联调、生产目录正式升级驱动、安装后新进程正文检查：本次未提供对应隔离服务器或正式升级参数，且只打开/取消系统页，没有实际替换到code11。不能把这些跳过计入通过。安装来源appops恢复为执行前的`allow`，未改生产包或设备全局代理。

最初新增10项回归通过后，追加了3项故障/生命周期回归。完整套件发现JUnit方法返回类型与fixture任务对Lint的依赖声明问题，保留原有业务断言修正后，上述56项最终重跑通过。SDK元数据和既有弃用API警告仍存在，本次未屏蔽告警或弱化检查。

## 本机签名候选

生成文件`djhx-memo-0.1.9-android-candidate.apk`，仅保留于本机忽略目录，不上传stable或替换0.1.8附件：37,186,942字节，SHA256 `b9ee374a1cd10bab3436d1ef7f8d672d07d4b2707e61d48016a63d4761494b44`。

Build Tools36.1.0的apksigner验证通过：app.djhx.memo、versionName0.1.9、code10、min29、target36，双ABI；原证书SHA256 `ec8a88aa7aad47d51fa07ffddb4a17bb69c4aad71ee3c0dd4b3ad0eb4f5cdcdb`。16KiB zipalign通过，APK内无嵌套fixture APK。被测试debug APK与签名候选两种ABI的core `.so`分别逐字哈希相同；这不代替签名release的真机完整安装验收。

密钥沿用仓库外原发布JKS，只在本次Gradle进程环境注入，使用`--no-daemon`并在结束后清除变量。未生成新Android证书或更新信任根。

## 未验证范围

- Civi新兼容入口尚未实测；用户目前已恢复到正式0.1.8。新源码不能直接修补已安装旧进程。
- 其他厂商、Android13实机、arm64真机、安装后杀进程及完整设备/输入法矩阵未通过本记录声明验收。
- Windows/Linux与core/server实现未改，本次不重复三端发行或宣称其完整回归。
- 本修复候选尚未推广到官网stable，未发行新版本；公开0.1.8内容保持。
