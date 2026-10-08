param([string]$JavaHome=$env:EMEDICAPP_JAVA_HOME)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Public.Common.ps1')
if (-not (Test-Path -LiteralPath $publicConfigPath)) { & (Join-Path $PSScriptRoot 'Configure-Public.ps1') }
$configuration=Get-Content -LiteralPath $publicConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($configuration.version -ne 1 -or -not $configuration.protectedAuthtoken) { throw 'La configuración pública está incompleta. Ejecutá Configure-Public.ps1.' }
$url=Resolve-PublicUrl $configuration.publicUrl
if (-not (Test-Path -LiteralPath $publicAgentPath)) { throw 'Falta el agente portable de ngrok en .tools\ngrok\ngrok.exe.' }
if (-not (Test-Path -LiteralPath $publicJarPath)) { throw 'Primero compilá E-MEDICAPP con scripts\Build.ps1.' }
$headers=@{'ngrok-skip-browser-warning'='E-MEDICAPP availability check'}

if (Test-Path -LiteralPath $publicStatePath) {
  $existing=Get-Content -LiteralPath $publicStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
  if ($existing.status -ne 'STOPPED' -and $existing.tunnel) {
    $active=Get-OwnPublicProcess $existing.tunnel $publicAgentPath @($publicAgentConfigPath,'127.0.0.1:8084','--inspect=false')
    if ($active) {
      if ($existing.status -ne 'UP') { throw 'La publicación anterior no completó sus verificaciones. Detenela antes de iniciar otro intento.' }
      if ($existing.publicUrl -ne $url -or $existing.localPort -ne 8084 -or $existing.inspectorPort -ne 4041) { throw 'La sesión activa corresponde a otra configuración. Detenela antes de cambiarla.' }
      $manual=Get-OwnPublicProcess $existing.manual $existing.manual.executable @('-jar',$publicJarPath)
      if (-not $manual) { throw 'El túnel sigue activo pero su aplicación no está disponible. Detené la publicación antes de reiniciarla.' }
      $bootstrap=Invoke-RestMethod ($url+'/api/bootstrap') -Headers $headers -TimeoutSec 10
      if ($bootstrap.mode -ne 'MANUAL') { throw 'La dirección pública no confirmó el modo MANUAL. Detené la publicación y revisá su configuración.' }
      Write-Host "E-MEDICAPP está disponible en $url"
      return
    }
  }
}
foreach ($port in @(8084,4041)) {
  if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "El puerto $port está ocupado. Se conserva el proceso existente; no se publica otra aplicación." }
}
if (Test-Path -LiteralPath $publicManualStatePath) {
  $previousManual=Get-Content -LiteralPath $publicManualStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
  if (Get-Process -Id ([int]$previousManual.processId) -ErrorAction SilentlyContinue) {
    throw 'Ya hay una operación MANUAL iniciada. Detenela desde su acceso antes de publicar: el túnel requiere un inicio propio con cookies seguras.'
  }
}

# Agent settings only. The authentication token is never written to YAML or command-line arguments.
$yaml=@'
version: "3"
agent:
  console_ui: false
  inspect_db_size: -1
  remote_management: false
  web_addr: 127.0.0.1:4041
  web_allow_hosts:
    - 127.0.0.1
  log: false
  update_channel: stable
  update_check: false
'@
[IO.File]::WriteAllText($publicAgentConfigPath,$yaml,[Text.UTF8Encoding]::new($false))
try {
  $configCheckOutput=& $publicAgentPath config check --config $publicAgentConfigPath 2>&1
  if ($LASTEXITCODE) { throw 'Invalid agent configuration.' }
} catch { throw 'La configuración privada del agente no fue aceptada. No se inicia el túnel ni se muestran errores que puedan contener credenciales.' }
$state=@{version=1;status='STARTING';publicUrl=$url;localPort=8084;inspectorPort=4041;agentPath=$publicAgentPath;jarPath=$publicJarPath;manual=$null;tunnel=$null;tunnelStopped=$false;manualStopped=$false;startedAt=(Get-Date).ToUniversalTime().ToString('o');verifiedAt=$null;stoppedAt=$null}
Write-PublicState $state
$keys=@('EMEDICAPP_ADDRESS','EMEDICAPP_SECURE_COOKIES','SERVER_FORWARD_HEADERS_STRATEGY','EMEDICAPP_PUBLIC_URL','EMEDICAPP_ADMIN_EMAIL')
$saved=@{}; foreach ($key in $keys) { $saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process') }
$previousTls=[Net.ServicePointManager]::SecurityProtocol
$manualStartRequested=$false
try {
  $env:EMEDICAPP_ADDRESS='127.0.0.1'
  $env:EMEDICAPP_SECURE_COOKIES='true'
  $env:SERVER_FORWARD_HEADERS_STRATEGY='NONE'
  $env:EMEDICAPP_PUBLIC_URL=$url
  $env:EMEDICAPP_ADMIN_EMAIL='softwarescalaris@gmail.com'
  & (Join-Path $PSScriptRoot 'Setup-ManualDatabase.ps1')
  $manualStartRequested=$true
  & (Join-Path $PSScriptRoot 'Start.ps1') -Environment Manual -Port 8084 -JavaHome $JavaHome -RestrictedDatabase
  $manualState=Get-Content -LiteralPath $publicManualStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
  $manualProcess=Get-Process -Id ([int]$manualState.processId) -ErrorAction Stop
  $manualIdentity=Get-CimInstance Win32_Process -Filter "ProcessId=$($manualState.processId)"
  if ($manualState.environment -ne 'Manual' -or $manualState.port -ne 8084 -or $manualIdentity.Name -ne 'java.exe' -or -not $manualIdentity.CommandLine.Contains($publicJarPath)) { throw 'El proceso local no corresponde a la operación MANUAL esperada.' }
  $state.manual=@{processId=$manualProcess.Id;startedTicks=$manualProcess.StartTime.ToUniversalTime().Ticks.ToString();executable=$manualIdentity.ExecutablePath}
  Write-PublicState $state
  $localListener=Get-NetTCPConnection -LocalPort 8084 -State Listen -ErrorAction SilentlyContinue
  if (-not $localListener -or $localListener.OwningProcess -notcontains $manualProcess.Id -or @($localListener | Where-Object { $_.LocalAddress -ne '127.0.0.1' }).Count) { throw 'La aplicación MANUAL no está limitada al puerto propio de loopback. No se publica.' }
  $health=Invoke-RestMethod 'http://127.0.0.1:8084/actuator/health/readiness' -TimeoutSec 3
  $bootstrap=Invoke-RestMethod 'http://127.0.0.1:8084/api/bootstrap' -TimeoutSec 3
  if ($health.status -ne 'UP' -or $bootstrap.mode -ne 'MANUAL') { throw 'La aplicación local todavía no confirmó disponibilidad en modo MANUAL.' }

  $previousAuthtoken=[Environment]::GetEnvironmentVariable('NGROK_AUTHTOKEN','Process')
  $secureToken=ConvertTo-SecureString $configuration.protectedAuthtoken
  try {
    $env:NGROK_AUTHTOKEN=[Net.NetworkCredential]::new('',$secureToken).Password
    # Do not capture raw ngrok errors: invalid-token errors can include authentication material.
    $tunnel=Start-Process -FilePath $publicAgentPath -ArgumentList @('http','http://127.0.0.1:8084',('--url='+$url),'--inspect=false',('--config="'+$publicAgentConfigPath+'"')) -WorkingDirectory $publicProjectRoot -WindowStyle Hidden -PassThru
  } finally {
    [Environment]::SetEnvironmentVariable('NGROK_AUTHTOKEN',$previousAuthtoken,'Process')
    $secureToken.Dispose()
  }
  $state.tunnel=@{processId=$tunnel.Id;startedTicks=$tunnel.StartTime.ToUniversalTime().Ticks.ToString()}
  $state.status='CONNECTING'
  Write-PublicState $state
  $deadline=[DateTime]::UtcNow.AddSeconds(45)
  $connected=$false
  while ([DateTime]::UtcNow -lt $deadline) {
    $tunnel.Refresh()
    if ($tunnel.HasExited) { throw ('El agente de ngrok finalizó al iniciar (código '+$tunnel.ExitCode+'). Revisá en tu cuenta el token y la dirección asignada; no se muestran credenciales.') }
    $inspector=Get-NetTCPConnection -LocalPort 4041 -State Listen -ErrorAction SilentlyContinue
    if ($inspector -and ($inspector.OwningProcess -notcontains $tunnel.Id -or @($inspector | Where-Object { $_.LocalAddress -ne '127.0.0.1' }).Count)) { throw 'El inspector no corresponde al proceso y loopback propios. No se confía en esa sesión.' }
    if ($inspector) {
      try {
        $endpoints=Invoke-RestMethod 'http://127.0.0.1:4041/api/endpoints' -TimeoutSec 2
        $endpoint=@($endpoints.endpoints | Where-Object { $_.url.TrimEnd('/') -eq $url }) | Select-Object -First 1
        if ($endpoint) {
          if ($endpoint.inspect -ne $false -or $endpoint.upstream.url.TrimEnd('/') -ne 'http://127.0.0.1:8084') { throw 'El túnel no confirmó el destino privado o la desactivación de inspección.' }
          $connected=$true; break
        }
      } catch { if ($_.Exception.Message -like 'El túnel no confirmó*') { throw } }
    }
    Start-Sleep -Milliseconds 500
  }
  if (-not $connected) { throw 'No se confirmó el túnel dentro de 45 segundos. Se cierra el intento y se conservan los datos.' }
  [Net.ServicePointManager]::SecurityProtocol=$previousTls -bor [Net.SecurityProtocolType]::Tls12
  $publicBootstrap=Invoke-RestMethod ($url+'/api/bootstrap') -Headers $headers -TimeoutSec 10
  if ($publicBootstrap.mode -ne 'MANUAL') { throw 'La dirección HTTPS no confirmó la operación MANUAL. Se cierra la publicación.' }
  $csrf=Invoke-WebRequest ($url+'/api/auth/csrf') -UseBasicParsing -Headers $headers -TimeoutSec 10
  $cookie=[string]$csrf.Headers['Set-Cookie']
  if ($cookie -notmatch 'JSESSIONID=' -or $cookie -notmatch '(?i)\bSecure\b' -or $cookie -notmatch '(?i)\bHttpOnly\b' -or $cookie -notmatch '(?i)SameSite=Lax') { throw 'La sesión HTTPS no confirmó los atributos de seguridad de la cookie. Se cierra el túnel.' }
  $state.status='UP'
  $state.verifiedAt=(Get-Date).ToUniversalTime().ToString('o')
  Write-PublicState $state
  Write-Host "E-MEDICAPP está disponible para operar en $url"
  Write-Host 'La publicación funciona mientras esta PC y su conexión estén disponibles. Para detenerla usá DetenerPublicacion.cmd.'
} catch {
  $failureMessage=$_.Exception.Message
  # Start.ps1 can register its Java process before readiness fails; record that owned attempt for cleanup.
  if (-not $state.manual -and $manualStartRequested -and (Test-Path -LiteralPath $publicManualStatePath)) {
    $attempt=Get-Content -LiteralPath $publicManualStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($attempt.port -eq 8084 -and $attempt.environment -eq 'Manual' -and [DateTime]::Parse($attempt.startedAt).ToUniversalTime() -ge [DateTime]::Parse($state.startedAt).ToUniversalTime()) {
      $attemptProcess=Get-Process -Id ([int]$attempt.processId) -ErrorAction SilentlyContinue
      $attemptIdentity=Get-CimInstance Win32_Process -Filter "ProcessId=$($attempt.processId)"
      if ($attemptProcess -and $attemptIdentity -and $attemptIdentity.Name -eq 'java.exe' -and $attemptIdentity.CommandLine.Contains($publicJarPath)) {
        $state.manual=@{processId=$attemptProcess.Id;startedTicks=$attemptProcess.StartTime.ToUniversalTime().Ticks.ToString();executable=$attemptIdentity.ExecutablePath}
      }
    }
  }
  $state.status='FAILED'
  Write-PublicState $state
  try { & (Join-Path $PSScriptRoot 'Stop-Public.ps1') } catch { Write-Warning 'El cierre necesita revisar los procesos registrados; no se detuvo ningún proceso ajeno.' }
  throw $failureMessage
} finally {
  foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') }
  [Net.ServicePointManager]::SecurityProtocol=$previousTls
}
