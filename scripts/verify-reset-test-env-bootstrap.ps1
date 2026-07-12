param(
  [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
)

$ErrorActionPreference = "Stop"

function Assert-FileExists {
  param([string]$Path)
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    throw "Expected file to exist: $Path"
  }
}

function Assert-Contains {
  param(
    [string]$Text,
    [string]$Needle,
    [string]$Message
  )
  if (-not $Text.Contains($Needle)) {
    throw $Message
  }
}

$batPath = Join-Path $Root "reset-test-env.bat"
$helperPath = Join-Path $Root "scripts\reset-test-env-bootstrap.ps1"

Assert-FileExists $batPath
Assert-FileExists $helperPath

$batBytes = [System.IO.File]::ReadAllBytes($batPath)
$bareLfCount = 0
for ($index = 0; $index -lt $batBytes.Length; $index += 1) {
  if ($batBytes[$index] -eq 10 -and ($index -eq 0 -or $batBytes[$index - 1] -ne 13)) {
    $bareLfCount += 1
  }
}
if ($bareLfCount -gt 0) {
  throw "bat file must use CRLF line endings; found $bareLfCount bare LF line endings."
}

$bat = Get-Content -LiteralPath $batPath -Raw
Assert-Contains $bat "API_HOST_PORT" "bat must load and default API_HOST_PORT."
Assert-Contains $bat "RESET_ADMIN_PASSWORD" "bat must load and default RESET_ADMIN_PASSWORD."
Assert-Contains $bat "RESET_INSTANCE_1_NAME" "bat must load and default RESET_INSTANCE_1_NAME."
Assert-Contains $bat "RESET_INSTANCE_2_NAME" "bat must load and default RESET_INSTANCE_2_NAME."
Assert-Contains $bat "reset-test-env-bootstrap.ps1" "bat must invoke the PowerShell bootstrap helper."

$tokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($helperPath, [ref]$tokens, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -gt 0) {
  throw "PowerShell helper has parse errors: $(($parseErrors | ForEach-Object { $_.Message }) -join '; ')"
}

$helper = Get-Content -LiteralPath $helperPath -Raw
Assert-Contains $helper "preset_mr5vi8yy_54139e" "helper must seed the fixed model preset id."
Assert-Contains $helper "/api/admin/wechat-plugins/install" "helper must batch install the WeChat plugin."
Assert-Contains $helper "/api/admin/openviking-plugins/install" "helper must batch install the OpenViking plugin."
Assert-Contains $helper "/api/admin/api-channel-plugins/install" "helper must batch install the API Channel plugin."
Assert-Contains $helper "/api/admin/miniapp-bridge-plugins/install" "helper must batch install the Miniapp Bridge plugin."
Assert-Contains $helper "/api/admin/miniapp-bridge-plugins/check" "helper must wait for the Miniapp Bridge plugin installation."
Assert-Contains $helper "Skipping already installed" "helper must skip plugin installation for instances that are already installed."
Assert-Contains $helper "missingInstanceIds" "helper must install plugins only on missing instances."
Assert-Contains $helper "/api/admin/instances/batch/restart-gateway" "helper must batch restart both gateways."

if ($helper -match 'Write-(Host|Output|Information).*(sk-[A-Za-z0-9])') {
  throw "helper must not print API keys."
}

Write-Host "[OK] reset test env bootstrap static verification passed."
