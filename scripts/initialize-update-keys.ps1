# Local Windows key provisioning. Never prints private key material.
[CmdletBinding()]
param([string]$KeyDirectory = (Join-Path $env:USERPROFILE '.djhx-memo/update-signing'))
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$keyRoot = [IO.Path]::GetFullPath($KeyDirectory)
$trustPath = Join-Path $repoRoot 'config/update-trust.json'
$parsedTrust = Get-Content -Raw -LiteralPath $trustPath | ConvertFrom-Json
$trust = @{ feed = $parsedTrust.feed; catalog_keys = @{}; package_keys = @{} }
foreach ($kind in @('catalog_keys', 'package_keys')) {
    foreach ($property in $parsedTrust.$kind.PSObject.Properties) { $trust[$kind][$property.Name] = $property.Value }
}
if ($trust['catalog_keys'].Count -gt 0 -or $trust['package_keys'].Count -gt 0) {
    throw 'Trust roots already configured. Transfer the existing signing keys to another build host; do not regenerate them.'
}
if ($keyRoot.Equals([IO.Path]::GetFullPath($repoRoot), [StringComparison]::OrdinalIgnoreCase) -or $keyRoot.StartsWith([IO.Path]::GetFullPath($repoRoot) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Signing keys must be outside the repository.'
}
if ((Test-Path -LiteralPath $keyRoot) -and (Get-ChildItem -LiteralPath $keyRoot -Force | Select-Object -First 1)) {
    throw 'The key directory must be new or empty.'
}
New-Item -ItemType Directory -Path $keyRoot -Force | Out-Null
$acl = New-Object Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true, $false)
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
Set-Acl -LiteralPath $keyRoot -AclObject $acl
foreach ($kind in @('catalog', 'package')) {
    $plain = Join-Path $keyRoot "$kind.key"
    $protected = "$plain.dpapi"
    if ((Test-Path -LiteralPath $protected) -or (Test-Path -LiteralPath $plain)) {
        throw "Key already exists for $kind; refusing to replace an existing trust root."
    }
    try {
        & node (Join-Path $repoRoot 'desktop/node_modules/@tauri-apps/cli/tauri.js') signer generate --ci --write-keys $plain *> $null
        if ($LASTEXITCODE -ne 0) { throw 'Key generation failed.' }
        $secure = ConvertTo-SecureString (Get-Content -Raw -LiteralPath $plain).Trim() -AsPlainText -Force
        ConvertFrom-SecureString $secure | Set-Content -LiteralPath $protected -Encoding utf8
        $public = (Get-Content -Raw -LiteralPath "$plain.pub").Trim()
        $trust["${kind}_keys"]["${kind}-2026-01"] = $public
    } finally {
        if (Test-Path -LiteralPath $plain) { Remove-Item -LiteralPath $plain }
    }
}
[IO.File]::WriteAllText($trustPath, ($trust | ConvertTo-Json -Depth 5) + [Environment]::NewLine, (New-Object Text.UTF8Encoding($false)))
Write-Output "Created DPAPI-protected keys in $keyRoot. Public keys saved to config/update-trust.json."
