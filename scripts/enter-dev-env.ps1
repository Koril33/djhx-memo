# Windows session setup only. Does not install tools or change persistent user settings.
[CmdletBinding()]
param(
    [string]$AndroidSdk,
    [string]$JavaHome,
    [string]$NdkVersion,
    [string]$CMakeVersion,
    [ValidateRange(29, 100)]
    [int]$AndroidApi = 29,
    [switch]$UseProxy,
    [uri]$ProxyUrl
)

$ErrorActionPreference = 'Stop'
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'This helper configures a Windows PowerShell session. Follow docs/dev-setup.md on other hosts.'
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $projectRoot '.local/dev-env.json'
$localConfig = @{}
if (Test-Path -LiteralPath $configPath) {
    $parsedConfig = Get-Content -Raw -Encoding UTF8 -LiteralPath $configPath | ConvertFrom-Json
    foreach ($property in $parsedConfig.PSObject.Properties) {
        $localConfig[$property.Name] = $property.Value
    }
}

if (-not $AndroidSdk) { $AndroidSdk = $localConfig['androidSdk'] }
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_HOME }
if (-not $AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (-not $JavaHome) { $JavaHome = $localConfig['javaHome'] }
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome) { throw 'Supply -JavaHome or JAVA_HOME pointing to a compatible JDK.' }
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) {
    throw 'The selected Java home does not contain javac.exe.'
}
if (-not (Test-Path -LiteralPath (Join-Path $AndroidSdk 'platform-tools/adb.exe'))) {
    throw 'The selected SDK does not contain platform-tools/adb.exe.'
}

if (-not $NdkVersion) { $NdkVersion = $localConfig['ndkVersion'] }
if (-not $NdkVersion) {
    $NdkVersion = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'ndk') -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1 -ExpandProperty Name
}
if (-not $NdkVersion -or $NdkVersion -notmatch '^\d+\.\d+\.\d+$') {
    throw 'Install an NDK, then supply its version with -NdkVersion or .local/dev-env.json.'
}
if (-not $CMakeVersion) { $CMakeVersion = $localConfig['cmakeVersion'] }
if (-not $CMakeVersion) {
    $CMakeVersion = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'cmake') -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1 -ExpandProperty Name
}
if (-not $CMakeVersion -or $CMakeVersion -notmatch '^\d+\.\d+\.\d+$') {
    throw 'Install SDK CMake, then supply -CMakeVersion or .local/dev-env.json.'
}

$ndkRoot = Join-Path $AndroidSdk ('ndk/' + $NdkVersion)
$ndkBin = Join-Path $ndkRoot 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$cmakeBin = Join-Path $AndroidSdk ('cmake/' + $CMakeVersion + '/bin')
$armLinker = Join-Path $ndkBin "aarch64-linux-android$AndroidApi-clang.cmd"
$x64Linker = Join-Path $ndkBin "x86_64-linux-android$AndroidApi-clang.cmd"
$archiver = Join-Path $ndkBin 'llvm-ar.exe'
foreach ($requiredFile in @($armLinker, $x64Linker, $archiver, (Join-Path $cmakeBin 'cmake.exe'))) {
    if (-not (Test-Path -LiteralPath $requiredFile)) { throw "Required tool missing: $requiredFile" }
}

$env:JAVA_HOME = [IO.Path]::GetFullPath($JavaHome)
$env:ANDROID_HOME = [IO.Path]::GetFullPath($AndroidSdk)
# Keep this compatibility variable aligned only if the caller already uses it.
if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME }
$env:ANDROID_NDK_HOME = [IO.Path]::GetFullPath($ndkRoot)
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $armLinker
$env:CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER = $x64Linker
$env:CC_aarch64_linux_android = $armLinker
$env:CC_x86_64_linux_android = $x64Linker
$env:AR_aarch64_linux_android = $archiver
$env:AR_x86_64_linux_android = $archiver
$prefixPaths = @(
    (Join-Path $env:JAVA_HOME 'bin'),
    (Join-Path $env:ANDROID_HOME 'platform-tools'),
    (Join-Path $env:ANDROID_HOME 'cmdline-tools/latest/bin'),
    $cmakeBin
)
$pathEntries = @($prefixPaths + @($env:Path -split ';')) |
    Where-Object { $_ } | Select-Object -Unique
$env:Path = $pathEntries -join ';'

if ($UseProxy -or $ProxyUrl) {
    if (-not $ProxyUrl -and $localConfig['proxyUrl']) { $ProxyUrl = [uri]$localConfig['proxyUrl'] }
    if (-not $ProxyUrl -or $ProxyUrl.Scheme -ne 'http' -or $ProxyUrl.UserInfo) {
        throw 'Supply an HTTP proxy URI without embedded credentials, explicitly or in local config.'
    }
    $env:HTTP_PROXY = $ProxyUrl.AbsoluteUri.TrimEnd('/')
    $env:HTTPS_PROXY = $env:HTTP_PROXY
    $javaProxyOptions = "-Dhttp.proxyHost=$($ProxyUrl.Host) -Dhttp.proxyPort=$($ProxyUrl.Port) -Dhttps.proxyHost=$($ProxyUrl.Host) -Dhttps.proxyPort=$($ProxyUrl.Port)"
    if (-not $env:JAVA_TOOL_OPTIONS -or -not $env:JAVA_TOOL_OPTIONS.Contains($javaProxyOptions)) {
        $env:JAVA_TOOL_OPTIONS = ($env:JAVA_TOOL_OPTIONS + ' ' + $javaProxyOptions).Trim()
    }
    Write-Host 'Proxy enabled for this process and its child tools only.'
}

Write-Host 'Development environment selected for this PowerShell process only.'
Write-Host "JDK: $env:JAVA_HOME"
Write-Host "SDK: $env:ANDROID_HOME"
Write-Host "NDK: $NdkVersion; Android API: $AndroidApi; CMake: $CMakeVersion"
