param([ValidateSet('Demo','Manual')][string]$Environment='Demo', [string]$JavaHome=$env:EMEDICAPP_JAVA_HOME, [int]$Port=8082, [switch]$OpenBrowser, [switch]$RestrictedDatabase)
$ErrorActionPreference='Stop'
if ($RestrictedDatabase -and $Environment -ne 'Manual') { throw 'La base limitada corresponde solamente a operación MANUAL.' }
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'Mail.Common.ps1')
. (Join-Path $PSScriptRoot 'Tools.Common.ps1')
$isolatedConfiguration=Suspend-EMedicappExternalConfiguration
try {
$JavaHome=Resolve-EMedicappJavaHome $JavaHome
$javaPath=Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaPath)) { throw 'No se encontró Java 25. Indicá EMEDICAPP_JAVA_HOME.' }
$version=& $javaPath --version 2>&1 | Out-String
if ($version -notmatch '(?m)^(openjdk|java) 25(\.| )') { throw 'El proyecto requiere Java 25 LTS.' }
$jarPath=Join-Path $projectRoot 'backend\target\emedicapp-0.1.0-SNAPSHOT.jar'
$statePath=Join-Path $projectRoot ".runtime\app-$Environment.json"
if (-not (Test-Path -LiteralPath $jarPath)) { throw 'Primero ejecutá scripts\Build.ps1.' }
$listening=Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($listening) {
  if (Test-Path -LiteralPath $statePath) {
    $state=Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($RestrictedDatabase -and ($state.restrictedDatabase -ne $true -or $state.databaseUser -ne 'emedicapp_manual_app')) { throw 'La operación existente no acredita la base limitada. Detenela con su script antes de iniciarla en modo público.' }
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($state.processId)"
    if ($process -and $process.CommandLine.Contains($jarPath) -and $state.port -eq $Port -and $listening.OwningProcess -contains $state.processId) {
      $health=Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health/readiness" -TimeoutSec 3
      if ($health.status -eq 'UP') { Write-Host "Aplicación disponible: http://127.0.0.1:$Port"; if ($OpenBrowser) { Start-Process "http://127.0.0.1:$Port" -WindowStyle Hidden }; return }
    }
  }
  throw 'El puerto está ocupado. Se preserva el proceso existente.'
}
$mailParameters=$null
$mailSettingsPath=Join-Path $projectRoot '.runtime\mail-settings.json'
if ($Environment -eq 'Manual' -and [string]::IsNullOrWhiteSpace($env:EMEDICAPP_SMTP_HOST) -and (Test-Path -LiteralPath $mailSettingsPath)) {
  $mailParameters=Read-MailParameters -Path $mailSettingsPath
}
& (Join-Path $PSScriptRoot 'Database.ps1') -Environment $Environment
$credentials=Get-Content -LiteralPath (Join-Path $projectRoot '.runtime\local-db.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($RestrictedDatabase) {
  $limitedPath=Join-Path $projectRoot '.runtime\manual-db.json'
  if (-not (Test-Path -LiteralPath $limitedPath)) { throw 'Primero ejecutá scripts\Setup-ManualDatabase.ps1.' }
  $limited=Get-Content -LiteralPath $limitedPath -Raw -Encoding UTF8 | ConvertFrom-Json
  if ($limited.user -ne 'emedicapp_manual_app' -or $limited.database -ne 'emedicapp_manual' -or $limited.port -ne $credentials.port -or -not $limited.protectedPassword) { throw 'La configuración limitada no corresponde a la operación MANUAL.' }
  $credentials=$limited
}
$keys=@('EMEDICAPP_DB_PASSWORD','EMEDICAPP_DB_URL','EMEDICAPP_DB_USER','EMEDICAPP_MODE','EMEDICAPP_DOCUMENTS_DIR','EMEDICAPP_PORT','EMEDICAPP_ADMIN_EMAIL','EMEDICAPP_ADMIN_PASSWORD') + $mailEnvironmentKeys
$saved=@{}; foreach ($key in $keys) { $saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process') }
try {
  if ($mailParameters) {
    foreach ($mailKey in $mailParameters.Keys) { [Environment]::SetEnvironmentVariable($mailKey,$mailParameters[$mailKey],'Process') }
  }
  $env:EMEDICAPP_DB_PASSWORD=[Net.NetworkCredential]::new('',(ConvertTo-SecureString $credentials.protectedPassword)).Password
  $databaseName=if ($Environment -eq 'Demo') {'emedicapp_demo'}else{'emedicapp_manual'}
  $env:EMEDICAPP_DB_URL="jdbc:postgresql://127.0.0.1:$($credentials.port)/$databaseName"
  $env:EMEDICAPP_DB_USER=$credentials.user
  $env:EMEDICAPP_MODE=if ($Environment -eq 'Demo') {'DEMO'}else{'MANUAL'}
  $env:EMEDICAPP_DOCUMENTS_DIR=Join-Path $projectRoot ".runtime\documents-$Environment"
  $env:EMEDICAPP_PORT=$Port
  if ($Environment -eq 'Manual' -and -not (Test-Path -LiteralPath (Join-Path $projectRoot '.runtime\manual-initialized'))) {
    if (-not $env:EMEDICAPP_ADMIN_EMAIL) { $env:EMEDICAPP_ADMIN_EMAIL=Read-Host 'Correo de la primera cuenta administradora' }
    if (-not $env:EMEDICAPP_ADMIN_PASSWORD) { $env:EMEDICAPP_ADMIN_PASSWORD=[Net.NetworkCredential]::new('',(Read-Host 'Contraseña propia (mínimo 12 caracteres)' -AsSecureString)).Password }
  }
  $process=Start-Process -FilePath $javaPath -ArgumentList @('-jar',('"'+$jarPath+'"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $projectRoot ".runtime\app-$Environment.stdout.log") -RedirectStandardError (Join-Path $projectRoot ".runtime\app-$Environment.stderr.log")
  @{processId=$process.Id;port=$Port;environment=$Environment;startedAt=(Get-Date).ToUniversalTime().ToString('o');jar=$jarPath;restrictedDatabase=[bool]$RestrictedDatabase;databaseUser=$credentials.user} | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
}
finally { foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') }; $mailParameters=$null }
$ready=$false
for ($attempt=0; $attempt -lt 45; $attempt++) {
  if (-not (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) { throw "La aplicación finalizó al iniciar. Revisá .runtime\app-$Environment.stderr.log y .stdout.log." }
  try { $health=Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health/readiness" -TimeoutSec 2; if ($health.status -eq 'UP') { $ready=$true; break } } catch { }
  Start-Sleep -Seconds 1
}
if (-not $ready) { throw 'El proceso sigue iniciando pero todavía no confirmó disponibilidad. Revisá sus registros antes de operar.' }
$ownedListener=Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if (-not $ownedListener -or $ownedListener.OwningProcess -notcontains $process.Id) { throw 'El puerto esperado no pertenece al proceso de E-MEDICAPP iniciado. No se confirma disponibilidad de otra aplicación.' }
if ($Environment -eq 'Manual') { Set-Content -LiteralPath (Join-Path $projectRoot '.runtime\manual-initialized') -Value 'Initialized' -Encoding UTF8 }
Write-Host "Aplicación disponible: http://127.0.0.1:$Port"
if ($OpenBrowser) { Start-Process "http://127.0.0.1:$Port" -WindowStyle Hidden }
} finally { Restore-EMedicappExternalConfiguration $isolatedConfiguration }
