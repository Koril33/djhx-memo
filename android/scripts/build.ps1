[CmdletBinding()]
param(
    [string]$AndroidSdk,
    [string]$JavaHome,
    [string]$CMakeVersion,
    [switch]$UseProxy,
    [uri]$ProxyUrl,
    [switch]$NativeOnly,
    [string[]]$GradleTasks = @('testDebugUnitTest', 'detekt', 'lintDebug', 'assembleDebug')
)

$ErrorActionPreference = 'Stop'
$androidRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $androidRoot
$coreManifest = Join-Path $repoRoot 'core/Cargo.toml'
$coreTarget = Join-Path $repoRoot 'core/target'
$bindings = Join-Path $androidRoot 'app/build/generated/uniffi'
$jniLibraries = Join-Path $androidRoot 'app/src/main/jniLibs'

$environmentOptions = @{
    NdkVersion = '30.0.16248370'
    AndroidApi = 29
    UseProxy = $UseProxy
}
foreach ($option in @('AndroidSdk', 'JavaHome', 'CMakeVersion', 'ProxyUrl')) {
    if ($PSBoundParameters.ContainsKey($option)) {
        $environmentOptions[$option] = $PSBoundParameters[$option]
    }
}
& (Join-Path $repoRoot 'scripts/enter-dev-env.ps1') @environmentOptions

function Invoke-BuildTool {
    param([string]$Tool, [string[]]$Arguments)
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Tool failed with exit code $LASTEXITCODE." }
}

$cargoOptions = @('--locked', '--release', '--manifest-path', $coreManifest, '--target-dir', $coreTarget)
$androidTargets = [ordered]@{
    'arm64-v8a' = 'aarch64-linux-android'
    'x86_64' = 'x86_64-linux-android'
}

# The Windows host DLL carries the UniFFI metadata. APKs use the separately built Android libraries.
Invoke-BuildTool cargo (@('build', '--lib') + $cargoOptions)
foreach ($target in $androidTargets.Values) {
    Invoke-BuildTool cargo (@('build', '--lib', '--target', $target) + $cargoOptions)
}
# UniFFI runs cargo metadata from the process working directory.
Push-Location (Split-Path -Parent $coreManifest)
try {
    Invoke-BuildTool cargo (@('run', '--features', 'bindgen', '--bin', 'bindgen') + $cargoOptions + @(
        '--', 'generate', '--library', (Join-Path $coreTarget 'release/djhx_memo_core.dll'),
        '--language', 'kotlin', '--no-format', '--config', (Join-Path $androidRoot 'uniffi.toml'),
        '--out-dir', $bindings
    ))
} finally {
    Pop-Location
}

foreach ($abi in $androidTargets.Keys) {
    $destination = Join-Path $jniLibraries $abi
    $library = Join-Path $coreTarget "$($androidTargets[$abi])/release/libdjhx_memo_core.so"
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Copy-Item -LiteralPath $library -Destination $destination -Force
}

if (-not $NativeOnly) {
    Push-Location $androidRoot
    try {
        Invoke-BuildTool '.\gradlew.bat' (@('--no-daemon') + $GradleTasks)
    } finally {
        Pop-Location
    }
}
