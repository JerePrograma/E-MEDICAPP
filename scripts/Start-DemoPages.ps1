param(
  [string]$PagesUrl='https://scalaris.com.ar/e-medicapp/',
  [int]$Port=8082,
  [string]$JavaHome=$env:EMEDICAPP_JAVA_HOME,
  [string]$CloudflaredPath=$env:EMEDICAPP_CLOUDFLARED,
  [switch]$Build,
  [switch]$PublishFrontend=$true,
  [switch]$OpenBrowser
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'DemoPages.Common.ps1')
$pagesUri=$null
if (-not [Uri]::TryCreate($PagesUrl,[UriKind]::Absolute,[ref]$pagesUri) -or $pagesUri.Scheme -ne 'https' -or $pagesUri.Port -ne 443 -or $pagesUri.UserInfo -or $pagesUri.Query -or $pagesUri.Fragment) {
  throw 'La direccion del frontend debe ser HTTPS, sin parametros ni fragmentos.'
}
$PagesUrl=$pagesUri.AbsoluteUri.TrimEnd('/')+'/'
$allowedOrigin=$pagesUri.GetLeftPart([UriPartial]::Authority)
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Puerto de backend invalido.' }
$jarPath=Join-Path $demoProjectRoot 'backend\target\emedicapp-0.1.0-SNAPSHOT.jar'
if (Test-Path -LiteralPath $demoPagesStatePath) {
  $existing=Get-Content -LiteralPath $demoPagesStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
  if ($existing.status -eq 'UP') {
    $activeTunnel=Get-DemoOwnedProcess $existing.tunnel @('tunnel',"http://127.0.0.1:$($existing.port)")
    $activeBackend=Get-DemoOwnedProcess $existing.backend @('-jar',$jarPath)
    if ($activeTunnel -and $activeBackend) {
      if ($Build -or $existing.pagesUrl -ne $PagesUrl -or $existing.port -ne $Port) { throw 'La demo ya esta iniciada. DetenerDemoPages.cmd permite cerrarla antes de actualizarla.' }
      $bootstrap=Invoke-RestMethod ($existing.apiUrl+'/api/bootstrap') -TimeoutSec 10
      if ($bootstrap.mode -ne 'DEMO') { throw 'El backend publico no confirma modo DEMO. Revisa el estado antes de compartir.' }
      $existing.connectionUrl=$PagesUrl
      Write-DemoPagesState $existing
      $frontendReady=$true
      if ($PublishFrontend) {
        try { & (Join-Path $PSScriptRoot 'Sync-DemoPages.ps1') -Wait }
        catch { $frontendReady=$false; Write-Warning $_.Exception.Message }
      }
      if ($frontendReady) { Write-Host "Frontend: $($existing.connectionUrl)" }
      else { Write-Warning 'El backend sigue activo; la publicacion de la conexion esta pendiente.' }
      Write-Host "Backend HTTPS de esta PC: $($existing.apiUrl)"
      if (-not $frontendReady) { throw 'La publicacion sigue pendiente; el backend y el tunel se conservaron activos.' }
      if ($OpenBrowser -and $frontendReady) { Start-Process $existing.connectionUrl -WindowStyle Hidden }
      return
    }
    # A tunnel may have died while Java remained up. Close only this prior owned attempt.
    & (Join-Path $PSScriptRoot 'Stop-DemoPages.ps1')
  } elseif ($existing.status -in @('STARTING','CONNECTING','FAILED')) {
    & (Join-Path $PSScriptRoot 'Stop-DemoPages.ps1')
  }
}
if (-not $CloudflaredPath) {
  $command=Get-Command cloudflared.exe -ErrorAction SilentlyContinue
  if ($command) { $CloudflaredPath=$command.Source }
}
if (-not $CloudflaredPath -or -not (Test-Path -LiteralPath $CloudflaredPath)) {
  throw 'Falta cloudflared. Instalalo con: winget install --id Cloudflare.cloudflared --exact. Luego abre nuevamente este acceso.'
}
if ($Build -or -not (Test-Path -LiteralPath $jarPath)) { & (Join-Path $PSScriptRoot 'Build.ps1') -JavaHome $JavaHome }
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
  throw "El puerto $Port ya esta ocupado. Si corresponde al DEMO local, cerralo con scripts\Stop.ps1 -Environment Demo antes de abrir la demo de Pages."
}
$state=@{version=1;status='STARTING';port=$Port;pagesUrl=$PagesUrl;apiUrl=$null;connectionUrl=$null;backend=$null;tunnel=$null;startedAt=(Get-Date).ToUniversalTime().ToString('o')}
Write-DemoPagesState $state
$keys=@('EMEDICAPP_ADDRESS','EMEDICAPP_ALLOWED_ORIGINS','EMEDICAPP_EMAIL_BASE_URL','EMEDICAPP_PUBLIC_URL','EMEDICAPP_SECURE_COOKIES','EMEDICAPP_SMTP_HOST')
$saved=@{}; foreach ($key in $keys) { $saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process') }
$backendStartRequested=$false
try {
  $env:EMEDICAPP_ADDRESS='127.0.0.1'
  $env:EMEDICAPP_ALLOWED_ORIGINS=(@($allowedOrigin,'https://scalaris.com.ar','https://jereprograma.github.io','https://localhost') | Select-Object -Unique) -join ','
  $env:EMEDICAPP_EMAIL_BASE_URL=$PagesUrl
  $env:EMEDICAPP_PUBLIC_URL=$null
  $env:EMEDICAPP_SECURE_COOKIES='true'
  # The demonstration uses synthetic accounts and never inherits a real mail provider.
  $env:EMEDICAPP_SMTP_HOST=$null
  $backendStartRequested=$true
  & (Join-Path $PSScriptRoot 'Start.ps1') -Environment Demo -Port $Port -JavaHome $JavaHome
  $appState=Get-Content -LiteralPath (Join-Path $demoRuntimePath 'app-Demo.json') -Raw -Encoding UTF8 | ConvertFrom-Json
  $state.backend=Get-DemoProcessIdentity (Get-Process -Id ([int]$appState.processId) -ErrorAction Stop)
  Write-DemoPagesState $state
  $bootstrap=Invoke-RestMethod "http://127.0.0.1:$Port/api/bootstrap" -TimeoutSec 5
  if ($bootstrap.mode -ne 'DEMO') { throw 'La aplicacion local no confirmo el modo DEMO.' }
  $stdoutPath=Join-Path $demoRuntimePath 'demo-tunnel.stdout.log'
  $stderrPath=Join-Path $demoRuntimePath 'demo-tunnel.stderr.log'
  # HTTP/2 uses an outbound TCP connection, including networks where UDP is blocked.
  $tunnel=Start-Process -FilePath $CloudflaredPath -ArgumentList @('tunnel','--no-autoupdate','--protocol','http2','--url',"http://127.0.0.1:$Port") -WorkingDirectory $demoProjectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
  $state.tunnel=Get-DemoProcessIdentity $tunnel
  $state.status='CONNECTING'
  Write-DemoPagesState $state
  $deadline=[DateTime]::UtcNow.AddSeconds(50)
  while ([DateTime]::UtcNow -lt $deadline) {
    $tunnel.Refresh()
    if ($tunnel.HasExited) { throw 'El tunel finalizo. Revisa .runtime\demo-tunnel.stderr.log.' }
    $logs=@((Get-Content -LiteralPath $stderrPath -Raw -ErrorAction SilentlyContinue),(Get-Content -LiteralPath $stdoutPath -Raw -ErrorAction SilentlyContinue)) -join "`n"
    $match=[regex]::Match($logs,'https://[a-z0-9-]+\.trycloudflare\.com')
    if ($match.Success) { $state.apiUrl=$match.Value; break }
    Start-Sleep -Milliseconds 500
  }
  if (-not $state.apiUrl) { throw 'No se obtuvo la URL HTTPS del tunel. Revisa .runtime\demo-tunnel.stderr.log.' }
  $state.connectionUrl=$PagesUrl
  Write-DemoPagesState $state
  Write-Host 'Esperando la propagacion del dominio HTTPS y verificando el backend...'
  $verified=$false
  $verificationDeadline=[DateTime]::UtcNow.AddSeconds(90)
  while ([DateTime]::UtcNow -lt $verificationDeadline) {
    $tunnel.Refresh()
    if ($tunnel.HasExited) { throw 'El tunel finalizo durante la verificacion. Revisa .runtime\demo-tunnel.stderr.log.' }
    if (-not (Get-DemoOwnedProcess $state.backend @('-jar',$jarPath))) { throw 'El backend finalizo durante la verificacion. Revisa .runtime\app-Demo.stderr.log.' }
    try {
      $remote=Invoke-RestMethod ($state.apiUrl+'/api/bootstrap') -TimeoutSec 5
      if ($remote.mode -eq 'DEMO') { $verified=$true; break }
    } catch { }
    Start-Sleep -Seconds 2
  }
  if (-not $verified) { throw 'El tunel no confirmo acceso al backend DEMO. Revisa la conexion antes de compartir.' }
  $cors=Invoke-WebRequest ($state.apiUrl+'/api/bootstrap') -UseBasicParsing -Headers @{Origin=$allowedOrigin} -TimeoutSec 10
  if ([string]$cors.Headers['Access-Control-Allow-Origin'] -ne $allowedOrigin) { throw 'El backend no permite la conexion desde el frontend publico. Revisa los origenes autorizados antes de compartir.' }
  $state.status='UP'
  $state.verifiedAt=(Get-Date).ToUniversalTime().ToString('o')
  Write-DemoPagesState $state
  $frontendReady=$true
  if ($PublishFrontend) {
    try { & (Join-Path $PSScriptRoot 'Sync-DemoPages.ps1') -Wait }
    catch { $frontendReady=$false; Write-Warning $_.Exception.Message }
  }
  Write-Host ''
  if ($frontendReady) { Write-Host "Frontend y enlace para compartir: $($state.connectionUrl)" }
  else { Write-Warning 'El backend sigue activo; la publicacion de la conexion esta pendiente.' }
  Write-Host "Backend HTTPS de esta PC: $($state.apiUrl)"
  Write-Host 'Mantene esta PC encendida y conectada. El tunel y Java siguen abiertos en segundo plano.'
  Write-Host 'La direccion del tunel cambia al reiniciarlo; el enlace de Pages sigue siendo el mismo.'
  Write-Host 'Para detener: DetenerDemoPages.cmd. Los datos de la demostracion se conservan.'
  if ($OpenBrowser -and $frontendReady) { Start-Process $state.connectionUrl -WindowStyle Hidden }
} catch {
  $message=$_.Exception.Message
  # Start.ps1 registers Java before checking readiness. Capture that attempt for cleanup.
  if (-not $state.backend -and $backendStartRequested) {
    $appStatePath=Join-Path $demoRuntimePath 'app-Demo.json'
    if (Test-Path -LiteralPath $appStatePath) {
      $attempt=Get-Content -LiteralPath $appStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
      if ($attempt.port -eq $Port -and [DateTime]::Parse($attempt.startedAt).ToUniversalTime() -ge [DateTime]::Parse($state.startedAt).ToUniversalTime()) {
        $running=Get-Process -Id ([int]$attempt.processId) -ErrorAction SilentlyContinue
        if ($running) { $state.backend=Get-DemoProcessIdentity $running }
      }
    }
  }
  $state.status='FAILED'
  Write-DemoPagesState $state
  try { & (Join-Path $PSScriptRoot 'Stop-DemoPages.ps1') } catch { Write-Warning 'El cierre necesita revision. Se conservaron los procesos que no pudieron identificarse como propios.' }
  throw $message
} finally { foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') } }
if (-not $frontendReady) { throw 'La publicacion sigue pendiente; el backend y el tunel se conservaron activos.' }
