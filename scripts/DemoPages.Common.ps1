$demoProjectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$demoRuntimePath=Join-Path $demoProjectRoot '.runtime'
$demoPagesStatePath=Join-Path $demoRuntimePath 'demo-pages.json'

function Get-DemoOwnedProcess([object]$Identity,[string[]]$Arguments=@()) {
  if (-not $Identity -or -not $Identity.processId -or -not $Identity.startedTicks -or -not $Identity.executable) { return $null }
  $running=Get-Process -Id ([int]$Identity.processId) -ErrorAction SilentlyContinue
  if (-not $running) { return $null }
  $actual=Get-CimInstance Win32_Process -Filter "ProcessId=$($Identity.processId)"
  if (-not $actual -or -not $actual.ExecutablePath -or [IO.Path]::GetFullPath($actual.ExecutablePath) -ne [IO.Path]::GetFullPath($Identity.executable) -or $running.StartTime.ToUniversalTime().Ticks.ToString() -ne [string]$Identity.startedTicks) {
    throw 'El proceso registrado no coincide con el iniciado por esta demo. Se conserva para revisar.'
  }
  foreach ($argument in $Arguments) {
    if (-not $actual.CommandLine -or $actual.CommandLine.IndexOf($argument,[StringComparison]::OrdinalIgnoreCase) -lt 0) { throw 'Los argumentos del proceso no corresponden a esta demo. Se conserva para revisar.' }
  }
  return $running
}

function Get-DemoProcessIdentity([Diagnostics.Process]$Process) {
  $actual=Get-CimInstance Win32_Process -Filter "ProcessId=$($Process.Id)"
  if (-not $actual -or -not $actual.ExecutablePath) { throw 'No se pudo registrar el proceso propio.' }
  return @{processId=$Process.Id;startedTicks=$Process.StartTime.ToUniversalTime().Ticks.ToString();executable=$actual.ExecutablePath}
}

function Write-DemoPagesState([object]$State) {
  New-Item -ItemType Directory -Path $demoRuntimePath -Force | Out-Null
  $temporaryPath=Join-Path $demoRuntimePath ('demo-pages-'+[Guid]::NewGuid().ToString('N')+'.tmp')
  try {
    $State | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $temporaryPath -Encoding UTF8
    Move-Item -LiteralPath $temporaryPath -Destination $demoPagesStatePath -Force
  } finally { if (Test-Path -LiteralPath $temporaryPath) { Remove-Item -LiteralPath $temporaryPath } }
}
