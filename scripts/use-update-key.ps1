# Loads a local DPAPI key for a child process. No key bytes are printed or persisted.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('catalog', 'package')][string]$Kind,
    [string]$KeyDirectory = (Join-Path $env:USERPROFILE '.djhx-memo/update-signing'),
    [Parameter(Mandatory)][string]$Program,
    [string[]]$ProgramArguments = @()
)
$ErrorActionPreference = 'Stop'
$secure = (Get-Content -Raw -LiteralPath (Join-Path $KeyDirectory "$Kind.key.dpapi")).Trim() | ConvertTo-SecureString
$credential = New-Object System.Management.Automation.PSCredential('update-signing', $secure)
$previous = $env:TAURI_SIGNING_PRIVATE_KEY
try {
    $env:TAURI_SIGNING_PRIVATE_KEY = $credential.GetNetworkCredential().Password
    & $Program @ProgramArguments
    if ($LASTEXITCODE -ne 0) { throw "Signing command failed with exit code $LASTEXITCODE." }
} finally {
    $env:TAURI_SIGNING_PRIVATE_KEY = $previous
}
