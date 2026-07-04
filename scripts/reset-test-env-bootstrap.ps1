param(
  [int]$ApiHostPort = 8080,
  [int]$WebHostPort = 4300,
  [string]$AdminEmail = "admin@example.com",
  [string]$AdminPassword = "ChangeMe123!",
  [string]$ResetAdminPassword = "cxf123...",
  [string]$Instance1Name = "OpenClaw Test 1",
  [string]$Instance2Name = "OpenClaw Test 2"
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$PresetId = "preset_mr5vi8yy_54139e"
$script:ApiBaseUrl = "http://127.0.0.1:$ApiHostPort"
$script:WebSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$script:PluginSummaries = @()

function Write-Section {
  param([string]$Text)
  Write-Host ""
  Write-Host "== $Text =="
}

function Get-ComposeFileArgs {
  $args = @("-f", (Join-Path $Root "compose.yaml"))
  $localCompose = Join-Path $Root "compose.local.yaml"
  if (Test-Path -LiteralPath $localCompose -PathType Leaf) {
    $args += @("-f", $localCompose)
  }
  return $args
}

function Get-ErrorMessage {
  param([System.Management.Automation.ErrorRecord]$ErrorRecord)
  $message = $ErrorRecord.Exception.Message
  $response = $ErrorRecord.Exception.Response
  if ($null -eq $response) {
    return $message
  }

  try {
    $getResponseStream = $response | Get-Member -Name GetResponseStream -MemberType Method -ErrorAction SilentlyContinue
    if ($null -ne $getResponseStream) {
      $stream = $response.GetResponseStream()
      if ($null -ne $stream) {
        $reader = New-Object System.IO.StreamReader($stream)
        $body = $reader.ReadToEnd()
        if (-not [string]::IsNullOrWhiteSpace($body)) {
          return $body
        }
      }
    }
  } catch {
    return $message
  }

  try {
    if ($null -ne $response.Content) {
      $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
      if (-not [string]::IsNullOrWhiteSpace($body)) {
        return $body
      }
    }
  } catch {
    return $message
  }

  return $message
}

function Invoke-ClawApi {
  param(
    [Parameter(Mandatory = $true)][string]$Method,
    [Parameter(Mandatory = $true)][string]$Path,
    [object]$Body = $null,
    [int]$TimeoutSec = 30
  )

  $uri = if ($Path.StartsWith("http")) { $Path } else { "$script:ApiBaseUrl$Path" }
  $params = @{
    Uri = $uri
    Method = $Method
    WebSession = $script:WebSession
    TimeoutSec = $TimeoutSec
    ErrorAction = "Stop"
  }
  if ($null -ne $Body) {
    $params.ContentType = "application/json; charset=utf-8"
    $params.Body = ($Body | ConvertTo-Json -Depth 20 -Compress)
  }

  try {
    return Invoke-RestMethod @params
  } catch {
    throw "$Method $Path failed: $(Get-ErrorMessage $_)"
  }
}

function Wait-ApiHealth {
  Write-Section "Waiting for API"
  $deadline = (Get-Date).AddMinutes(5)
  while ((Get-Date) -lt $deadline) {
    try {
      $health = Invoke-RestMethod -Uri "$script:ApiBaseUrl/api/health" -Method GET -TimeoutSec 5 -ErrorAction Stop
      if ($health.ok -eq $true) {
        Write-Host "[OK] API is healthy at $script:ApiBaseUrl"
        return
      }
    } catch {
      Start-Sleep -Seconds 3
      continue
    }
    Start-Sleep -Seconds 3
  }
  throw "API did not become healthy within 5 minutes: $script:ApiBaseUrl/api/health"
}

function Invoke-SeedSql {
  Write-Section "Seeding database"
  $sql = @'
UPDATE clawbot.model_presets
SET is_default = 0;

INSERT INTO clawbot.model_presets
  (id, name, is_default, provider_key, provider_id, model_id, api_mode, auth_type, auth_provider_id, auth_method_id, base_url, api_key, provider_config, extra, context_window, max_tokens, created_at)
VALUES
  ('preset_mr5vi8yy_54139e', 'anyclaw-model-1.0', 1, 'custom-provider', 'anyclaw', 'anyclaw-model-1.0', 'openai-completions', 'custom_gateway', 'anyclaw', '', 'https://api.940819.xyz', 'sk-eb2eaa1ded019d472b7a06cf23be1724dd25f14b830b45360aad9b7692524609', NULL, '{}', 200000, 64000, '2026-07-04T04:39:43.594692261Z')
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  is_default = VALUES(is_default),
  provider_key = VALUES(provider_key),
  provider_id = VALUES(provider_id),
  model_id = VALUES(model_id),
  api_mode = VALUES(api_mode),
  auth_type = VALUES(auth_type),
  auth_provider_id = VALUES(auth_provider_id),
  auth_method_id = VALUES(auth_method_id),
  base_url = VALUES(base_url),
  api_key = VALUES(api_key),
  provider_config = VALUES(provider_config),
  extra = VALUES(extra),
  context_window = VALUES(context_window),
  max_tokens = VALUES(max_tokens),
  created_at = VALUES(created_at);

INSERT INTO clawbot.openviking_settings
  (id, base_url, trusted_mode_enabled, account_id, plugin_package, identity_salt, root_api_key, created_at, updated_at)
VALUES
  ('global', 'https://openviking.anyclawer.com', 1, 'claw-manager', 'npm:@claw-manager/openviking-openclaw-plugin@2026.6.37', 'oauLVCAwdwhHFrGAm6txDGcUcULgjM08zF5ZTdwZQKkPgUmVzW', 'sk-XaJnsy6gL42kmeXHNFZDTB4vHaHvBiVNJoJd', '2026-07-04T04:40:04.871124335Z', '2026-07-04T04:40:04.871124335Z')
ON DUPLICATE KEY UPDATE
  base_url = VALUES(base_url),
  trusted_mode_enabled = VALUES(trusted_mode_enabled),
  account_id = VALUES(account_id),
  plugin_package = VALUES(plugin_package),
  identity_salt = VALUES(identity_salt),
  root_api_key = VALUES(root_api_key),
  created_at = VALUES(created_at),
  updated_at = VALUES(updated_at);
'@

  $dockerArgs = @("compose") + (Get-ComposeFileArgs) + @("exec", "-T", "mysql", "mysql", "-uclawbot", "-pclawbot", "clawbot")
  $sql | & docker @dockerArgs
  if ($LASTEXITCODE -ne 0) {
    throw "Database seed failed with docker exit code $LASTEXITCODE."
  }
  Write-Host "[OK] Seeded model preset $PresetId and OpenViking settings."
}

function Try-Login {
  param([string]$Password)
  $script:WebSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
  try {
    Invoke-ClawApi -Method POST -Path "/api/login" -Body @{ email = $AdminEmail; password = $Password } -TimeoutSec 30 | Out-Null
    return $true
  } catch {
    return $false
  }
}

function Set-AdminPassword {
  Write-Section "Configuring admin password"
  if (Try-Login -Password $AdminPassword) {
    try {
      Invoke-ClawApi -Method POST -Path "/api/change-password" -Body @{
        currentPassword = $AdminPassword
        newPassword = $ResetAdminPassword
      } -TimeoutSec 30 | Out-Null
      Write-Host "[OK] Admin password updated."
      return
    } catch {
      if (Try-Login -Password $ResetAdminPassword) {
        Write-Host "[OK] Admin password was already updated."
        return
      }
      throw
    }
  }

  if (Try-Login -Password $ResetAdminPassword) {
    Write-Host "[OK] Admin password was already updated."
    return
  }

  throw "Unable to log in with the initial or reset admin password."
}

function Get-AllInstances {
  $response = Invoke-ClawApi -Method GET -Path "/api/admin/instances" -TimeoutSec 30
  return @($response.instances)
}

function Ensure-Instance {
  param([string]$Name)
  $existing = Get-AllInstances | Where-Object { $_.name -eq $Name } | Select-Object -First 1
  if ($null -ne $existing) {
    Write-Host "[OK] Reusing instance: $($existing.name) ($($existing.id))"
    return $existing
  }

  $response = Invoke-ClawApi -Method POST -Path "/api/admin/instances" -Body @{
    name = $Name
    presetId = $PresetId
  } -TimeoutSec 60
  Write-Host "[OK] Created instance: $($response.instance.name) ($($response.instance.id))"
  return $response.instance
}

function Get-InstancesById {
  param([string[]]$InstanceIds)
  $all = Get-AllInstances
  $items = @()
  foreach ($id in $InstanceIds) {
    $match = $all | Where-Object { $_.id -eq $id } | Select-Object -First 1
    if ($null -eq $match) {
      throw "Instance disappeared while polling: $id"
    }
    $items += $match
  }
  return $items
}

function Format-InstanceStatus {
  param([object]$Instance)
  $provisioning = $Instance.provisioning
  if ($null -eq $provisioning) {
    return "$($Instance.name)=unknown"
  }
  return "$($Instance.name)=$($provisioning.status)/$($provisioning.stage)/$($provisioning.percent)%"
}

function Wait-InstancesReady {
  param(
    [string[]]$InstanceIds,
    [string]$Reason,
    [int]$TimeoutMinutes = 40
  )

  Write-Section "Waiting for instances: $Reason"
  $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
  $nextLogAt = Get-Date
  while ((Get-Date) -lt $deadline) {
    $instances = @(Get-InstancesById -InstanceIds $InstanceIds)
    $errors = @($instances | Where-Object { $_.provisioning -and $_.provisioning.status -eq "error" })
    if ($errors.Count -gt 0) {
      $messages = $errors | ForEach-Object { "$($_.name): $($_.provisioning.message)" }
      throw "Instance provisioning failed: $(($messages) -join '; ')"
    }

    $notReady = @($instances | Where-Object { -not $_.provisioning -or $_.provisioning.status -ne "ready" })
    if ($notReady.Count -eq 0) {
      Write-Host "[OK] Instances are ready."
      return $instances
    }

    $now = Get-Date
    if ($now -ge $nextLogAt) {
      $statusText = ($instances | ForEach-Object { Format-InstanceStatus $_ }) -join ", "
      Write-Host "  Waiting: $statusText"
      $nextLogAt = $now.AddSeconds(30)
    }
    Start-Sleep -Seconds 10
  }

  $latest = @(Get-InstancesById -InstanceIds $InstanceIds)
  $latestText = ($latest | ForEach-Object { Format-InstanceStatus $_ }) -join ", "
  throw "Timed out waiting for instances to become ready after $TimeoutMinutes minutes: $latestText"
}

function Confirm-NoImmediatePluginFailure {
  param(
    [string]$PluginName,
    [object[]]$Items
  )
  $failed = @($Items | Where-Object { $_.plugin -and $_.plugin.status -eq "failed" })
  if ($failed.Count -eq 0) {
    return
  }

  $messages = $failed | ForEach-Object { "$($_.instanceId): $($_.plugin.message)" }
  throw "$PluginName install request failed: $(($messages) -join '; ')"
}

function Invoke-PluginCheck {
  param(
    [hashtable]$Plugin,
    [string[]]$InstanceIds
  )
  $response = Invoke-ClawApi -Method POST -Path $Plugin.Check -Body @{ instanceIds = $InstanceIds } -TimeoutSec 60
  return @($response.plugins)
}

function Wait-PluginInstalled {
  param(
    [hashtable]$Plugin,
    [string[]]$InstanceIds,
    [int]$TimeoutMinutes = 15
  )

  $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
  $nextLogAt = Get-Date
  while ((Get-Date) -lt $deadline) {
    $items = @(Invoke-PluginCheck -Plugin $Plugin -InstanceIds $InstanceIds)
    Confirm-NoImmediatePluginFailure -PluginName $Plugin.Name -Items $items

    $notInstalled = @($items | Where-Object { -not $_.plugin -or $_.plugin.installed -ne $true })
    if ($notInstalled.Count -eq 0) {
      $versions = $items | ForEach-Object {
        $version = $_.plugin.currentVersion
        if ([string]::IsNullOrWhiteSpace($version)) {
          $version = "unknown"
        }
        "$($_.instanceId)@$version"
      }
      $summary = "$($Plugin.Name): $(($versions) -join ', ')"
      $script:PluginSummaries += $summary
      Write-Host "[OK] $summary"
      return $items
    }

    $now = Get-Date
    if ($now -ge $nextLogAt) {
      $statusText = ($items | ForEach-Object {
        if ($_.plugin) {
          "$($_.instanceId)=$($_.plugin.status)"
        } else {
          "$($_.instanceId)=unknown"
        }
      }) -join ", "
      Write-Host "  Waiting for $($Plugin.Name): $statusText"
      $nextLogAt = $now.AddSeconds(30)
    }
    Start-Sleep -Seconds 10
  }

  throw "Timed out waiting for $($Plugin.Name) to install after $TimeoutMinutes minutes."
}

function Install-PluginBatch {
  param(
    [hashtable]$Plugin,
    [string[]]$InstanceIds
  )

  Write-Section "Installing $($Plugin.Name)"
  $response = Invoke-ClawApi -Method POST -Path $Plugin.Install -Body @{
    instanceIds = $InstanceIds
    version = ""
  } -TimeoutSec 60
  $items = @($response.plugins)
  Confirm-NoImmediatePluginFailure -PluginName $Plugin.Name -Items $items
  Wait-PluginInstalled -Plugin $Plugin -InstanceIds $InstanceIds | Out-Null
  Start-Sleep -Seconds 2
}

function Restart-Gateways {
  param([string[]]$InstanceIds)
  Write-Section "Restarting gateways"
  $response = Invoke-ClawApi -Method POST -Path "/api/admin/instances/batch/restart-gateway" -Body @{
    instanceIds = $InstanceIds
  } -TimeoutSec 60
  $failed = @($response.instances | Where-Object { $_.status -eq "failed" })
  if ($failed.Count -gt 0) {
    $messages = $failed | ForEach-Object { "$($_.instanceId): $($_.message)" }
    throw "Gateway restart request failed: $(($messages) -join '; ')"
  }
  Wait-InstancesReady -InstanceIds $InstanceIds -Reason "gateway restart" -TimeoutMinutes 40 | Out-Null
}

function Print-Summary {
  param([object[]]$Instances)
  Write-Section "Bootstrap summary"
  Write-Host "Admin URL:"
  Write-Host "  http://127.0.0.1:$WebHostPort"
  Write-Host "Admin login:"
  Write-Host "  Email:    $AdminEmail"
  Write-Host "  Password: $ResetAdminPassword"
  Write-Host "Model preset:"
  Write-Host "  $PresetId"
  Write-Host "Instances:"
  foreach ($instance in $Instances) {
    Write-Host "  $($instance.name): $($instance.id)"
  }
  Write-Host "Plugins:"
  foreach ($summary in $script:PluginSummaries) {
    Write-Host "  $summary"
  }
}

try {
  Wait-ApiHealth
  Invoke-SeedSql
  Set-AdminPassword

  Write-Section "Creating instances"
  $instance1 = Ensure-Instance -Name $Instance1Name
  $instance2 = Ensure-Instance -Name $Instance2Name
  $instanceIds = @($instance1.id, $instance2.id)
  Wait-InstancesReady -InstanceIds $instanceIds -Reason "initial provisioning" -TimeoutMinutes 40 | Out-Null

  $plugins = @(
    @{ Name = "WeChat"; Install = "/api/admin/wechat-plugins/install"; Check = "/api/admin/wechat-plugins/check" },
    @{ Name = "OpenViking"; Install = "/api/admin/openviking-plugins/install"; Check = "/api/admin/openviking-plugins/check" },
    @{ Name = "API Channel"; Install = "/api/admin/api-channel-plugins/install"; Check = "/api/admin/api-channel-plugins/check" }
  )

  foreach ($plugin in $plugins) {
    Install-PluginBatch -Plugin $plugin -InstanceIds $instanceIds
  }

  Restart-Gateways -InstanceIds $instanceIds
  $finalInstances = @(Get-InstancesById -InstanceIds $instanceIds)
  Print-Summary -Instances $finalInstances
  exit 0
} catch {
  Write-Error "[ERROR] $($_.Exception.Message)"
  exit 1
}
