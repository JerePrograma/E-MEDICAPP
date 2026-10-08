$publicProjectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$publicRuntimePath=Join-Path $publicProjectRoot '.runtime'
$publicConfigPath=Join-Path $publicRuntimePath 'public-config.json'
$publicStatePath=Join-Path $publicRuntimePath 'public-session.json'
$publicAgentConfigPath=Join-Path $publicRuntimePath 'ngrok-public.yml'
$publicAgentPath=Join-Path $publicProjectRoot '.tools\ngrok\ngrok.exe'
$publicJarPath=Join-Path $publicProjectRoot 'backend\target\emedicapp-0.1.0-SNAPSHOT.jar'
$publicManualStatePath=Join-Path $publicRuntimePath 'app-Manual.json'

function Resolve-PublicUrl([string]$Value) {
  $uri=$null
  if ([string]::IsNullOrWhiteSpace($Value) -or -not [Uri]::TryCreate($Value.Trim(),[UriKind]::Absolute,[ref]$uri) -or $uri.Scheme -ne 'https' -or $uri.Port -ne 443 -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/') {
    throw 'Indicá únicamente la URL HTTPS estable de tu cuenta de ngrok, sin usuario, ruta ni parámetros.'
  }
  if ($uri.DnsSafeHost -notmatch '^[a-z0-9-]+\.ngrok(-free)?\.(app|dev|io)$') {
    throw 'La dirección debe ser el dominio asignado por ngrok a tu cuenta.'
  }
  return $uri.GetLeftPart([UriPartial]::Authority)
}

function Get-OwnPublicProcess([object]$Identity,[string]$ExpectedFile,[string[]]$ExpectedArguments) {
  if (-not $Identity -or -not $Identity.processId -or -not $Identity.startedTicks) { return $null }
  $running=Get-Process -Id ([int]$Identity.processId) -ErrorAction SilentlyContinue
  if (-not $running) { return $null }
  $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($Identity.processId)"
  if (-not $process -or -not $process.ExecutablePath -or [IO.Path]::GetFullPath($process.ExecutablePath) -ne [IO.Path]::GetFullPath($ExpectedFile) -or $running.StartTime.ToUniversalTime().Ticks.ToString() -ne [string]$Identity.startedTicks) {
    throw 'El identificador registrado ya no pertenece al proceso iniciado por esta publicación. Se preserva el proceso existente.'
  }
  foreach ($argument in $ExpectedArguments) {
    if (-not $process.CommandLine -or $process.CommandLine.IndexOf($argument,[StringComparison]::OrdinalIgnoreCase) -lt 0) { throw 'Los argumentos del proceso no corresponden a esta publicación. Se preserva.' }
  }
  return $running
}

function Write-PublicState([object]$State) {
  $temporaryPath=Join-Path $publicRuntimePath ('public-state-'+[Guid]::NewGuid().ToString('N')+'.tmp')
  try {
    $State | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $temporaryPath -Encoding UTF8
    Move-Item -LiteralPath $temporaryPath -Destination $publicStatePath -Force
  } finally { if (Test-Path -LiteralPath $temporaryPath) { Remove-Item -LiteralPath $temporaryPath } }
}
