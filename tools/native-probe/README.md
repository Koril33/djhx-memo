# M0 原生绑定验证

此目录包含可重复执行的技术探针。使用临时数据库验证 Rust 写入提交、重新打开数据库、SQLite FTS5 中文三字查询及提交回调。探针只使用固定测试正文。请为每次运行使用独立的测试数据库。

## Windows 主机测试

从仓库根目录运行：

```powershell
cargo fmt --manifest-path tools/native-probe/Cargo.toml --check
cargo clippy --locked --manifest-path tools/native-probe/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path tools/native-probe/Cargo.toml
```

## Android 原生库与 Kotlin 绑定

安装 NDK r29，并通过 rustup 安装 `aarch64-linux-android` 和 `x86_64-linux-android` 目标。在设置了 `ANDROID_HOME` 的 PowerShell 中运行：

```powershell
$manifest = 'tools/native-probe/Cargo.toml'
$ndkBin = Join-Path $env:ANDROID_HOME 'ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin'
$env:AR_aarch64_linux_android = Join-Path $ndkBin 'llvm-ar.exe'
$env:AR_x86_64_linux_android = $env:AR_aarch64_linux_android
$env:CC_aarch64_linux_android = Join-Path $ndkBin 'aarch64-linux-android29-clang.cmd'
$env:CC_x86_64_linux_android = Join-Path $ndkBin 'x86_64-linux-android29-clang.cmd'
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $env:CC_aarch64_linux_android
$env:CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER = $env:CC_x86_64_linux_android
# GNU 宿主通过 w64devkit 链接构建脚本时，补上 Rust 自带的 libgcc_eh。
# MSVC 宿主跳过这一行。
$env:LIBRARY_PATH = Join-Path (rustc --print sysroot) 'lib/rustlib/x86_64-pc-windows-gnu/lib/self-contained'
cargo build --locked --manifest-path $manifest --target aarch64-linux-android
cargo build --locked --manifest-path $manifest --target x86_64-linux-android
Push-Location tools/native-probe
try {
    cargo run --locked --features bindgen --bin bindgen -- generate target/aarch64-linux-android/debug/libdjhx_native_probe.so --metadata-no-deps --no-format --language kotlin --out-dir target/kotlin
} finally {
    Pop-Location
}
```

设备验收须在 Android 进程中加载对应 ABI 的共享库，通过生成的 Kotlin 接口调用 `probe`，断言返回 `中文持久化探针` 和一次提交回调，再用不可写路径检查错误和回调次数。首次成功后杀掉进程，重启并读取探针数据库，确认持久化。跨编译及生成绑定只是设备验收的前置步骤。

## Kotlin 主机联调

完成上一步生成绑定后，使用 Android Studio 自带的 Kotlin 编译器和 JBR。在 Maven Central 下载 `net.java.dev.jna:jna:5.17.0` 的 JAR，校验仓库发布的摘要，再在仓库根目录运行以下命令。按实际安装位置设置 `$studio` 和 `$jna`。

```powershell
cargo build --locked --manifest-path tools/native-probe/Cargo.toml
$probe = (Resolve-Path tools/native-probe).Path
$target = Join-Path $probe 'target'
$studio = 'C:/Program Files/Android/Android Studio'
$jna = 'C:/path/to/jna-5.17.0.jar'
$kotlin = Join-Path $studio 'plugins/Kotlin/kotlinc/lib'
$java = Join-Path $studio 'jbr/bin/java.exe'
$generated = Join-Path $target 'kotlin/uniffi/djhx_native_probe/djhx_native_probe.kt'
$dependencies = "$jna;$kotlin/kotlin-stdlib.jar;$kotlin/kotlinx-coroutines-core-jvm.jar"
$compilerArgs = @(
    '-cp', "$kotlin/*", 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-classpath', $dependencies,
    '-d', "$target/probe-host.jar", $generated, "$probe/kotlin/HostProbe.kt"
)
& $java @compilerArgs
if ($LASTEXITCODE -ne 0) { throw 'Kotlin compilation failed' }
& $java '--enable-native-access=ALL-UNNAMED' "-Djna.library.path=$target/debug" '-cp' "$target/probe-host.jar;$dependencies" 'HostProbeKt'
```
