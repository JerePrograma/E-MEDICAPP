param([ValidateSet('Start','Stop','Status')][string]$Action='Start', [ValidateSet('Demo','Manual','Test')][string]$Environment='Demo', [int]$Port=0, [string]$PostgresBin=$env:EMEDICAPP_PG_BIN)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimePath=Join-Path $projectRoot '.runtime'
$databasePath=Join-Path $runtimePath 'postgres'
$credentialsPath=Join-Path $runtimePath 'local-db.json'
if ($Port -eq 0) {
  $Port=if (Test-Path -LiteralPath $credentialsPath) { [int](Get-Content -LiteralPath $credentialsPath -Raw -Encoding UTF8 | ConvertFrom-Json).port } else { 5544 }
}
. (Join-Path $PSScriptRoot 'Tools.Common.ps1')
$PostgresBin=Resolve-EMedicappPostgresBin $PostgresBin
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Puerto de base inválido.' }
$pgctl=Join-Path $PostgresBin 'pg_ctl.exe'
if ($Action -eq 'Stop') {
  if (Test-Path -LiteralPath (Join-Path $databasePath 'PG_VERSION')) { & $pgctl stop -D $databasePath -m fast -w; if ($LASTEXITCODE) { throw 'No se pudo detener la base propia.' } }
  exit
}
if ($Action -eq 'Status') { & $pgctl status -D $databasePath; exit $LASTEXITCODE }
New-Item -ItemType Directory -Force -Path $runtimePath | Out-Null
if (-not (Test-Path -LiteralPath $credentialsPath)) {
  if (Test-Path -LiteralPath (Join-Path $databasePath 'PG_VERSION')) { throw 'Existe una base sin configuración local. No se reinicializa ni adopta automáticamente.' }
  $randomBytes=New-Object byte[] 32
  $generator=[Security.Cryptography.RandomNumberGenerator]::Create()
  try { $generator.GetBytes($randomBytes) } finally { $generator.Dispose() }
  $newPassword=[Convert]::ToBase64String($randomBytes)
  $protectedPassword=ConvertFrom-SecureString (ConvertTo-SecureString $newPassword -AsPlainText -Force)
  @{ user='emedicapp'; protectedPassword=$protectedPassword; port=$Port } | ConvertTo-Json | Set-Content -LiteralPath $credentialsPath -Encoding UTF8
}
$credentials=Get-Content -LiteralPath $credentialsPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($credentials.port -ne $Port) { throw 'El puerto difiere de la configuración local existente.' }
$securePassword=ConvertTo-SecureString $credentials.protectedPassword
$plainPassword=[Net.NetworkCredential]::new('', $securePassword).Password
if (-not (Test-Path -LiteralPath (Join-Path $databasePath 'PG_VERSION'))) {
  if ((Test-Path -LiteralPath $databasePath) -and @(Get-ChildItem -LiteralPath $databasePath -Force).Count -gt 0) { throw 'El directorio de base ya tiene archivos; se preservan para revisar.' }
  $passwordFile=Join-Path $runtimePath ('init-'+[Guid]::NewGuid().ToString()+'.tmp')
  try {
    [IO.File]::WriteAllText($passwordFile,$plainPassword+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
    & (Join-Path $PostgresBin 'initdb.exe') -D $databasePath -U emedicapp -A scram-sha-256 --pwfile=$passwordFile -E UTF8 --locale=C
    if ($LASTEXITCODE) { throw 'No se pudo inicializar la base local aislada.' }
  } finally { if (Test-Path -LiteralPath $passwordFile) { Remove-Item -LiteralPath $passwordFile -Force } }
}
& $pgctl status -D $databasePath *> $null
if ($LASTEXITCODE -ne 0) {
  if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw 'El puerto de base está ocupado por otro proceso. No se adopta ni detiene.' }
  # Give PostgreSQL its own console: closing or interrupting the launcher must not
  # send Ctrl+C to the database shared by the running application.
  $startArguments=@('start','-D',('"'+$databasePath+'"'),'-l',('"'+(Join-Path $runtimePath 'postgres.log')+'"'),'-o',('"-h 127.0.0.1 -p '+$Port+'"'),'-w','-t','30')
  $databaseStarter=Start-Process -FilePath $pgctl -ArgumentList $startArguments -WindowStyle Hidden -PassThru
  if (-not $databaseStarter.WaitForExit(35000)) { throw 'El inicio de la base no terminó a tiempo. Revisá .runtime\postgres.log antes de volver a intentar.' }
  if ($databaseStarter.ExitCode) { throw 'No se pudo iniciar la base propia. Revisá .runtime\postgres.log.' }
}
$databaseName=@{Demo='emedicapp_demo';Manual='emedicapp_manual';Test='emedicapp_test_demo'}[$Environment]
$previousPgPassword=$env:PGPASSWORD
try {
  $env:PGPASSWORD=$plainPassword
  $psql=Join-Path $PostgresBin 'psql.exe'
  $exists=& $psql -h 127.0.0.1 -p $Port -U emedicapp -d postgres -tA -c "SELECT 1 FROM pg_database WHERE datname='$databaseName'"
  if ($LASTEXITCODE) { throw 'No se pudo comprobar la base propia.' }
  if ($exists -ne '1') { & $psql -h 127.0.0.1 -p $Port -U emedicapp -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $databaseName OWNER emedicapp"; if ($LASTEXITCODE) { throw 'No se pudo crear la base del ambiente.' } }
} finally { $env:PGPASSWORD=$previousPgPassword }
Write-Output "Base $databaseName disponible solamente en 127.0.0.1:$Port."
