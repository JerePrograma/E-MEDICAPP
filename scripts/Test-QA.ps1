param([string]$JavaHome=$env:EMEDICAPP_JAVA_HOME, [string]$PostgresBin=$env:EMEDICAPP_PG_BIN, [string]$NodeHome=$env:EMEDICAPP_NODE_HOME, [switch]$Package)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimePath=Join-Path $projectRoot '.runtime'
$clusterPath=Join-Path $runtimePath 'postgres'
. (Join-Path $PSScriptRoot 'Tools.Common.ps1')
$isolatedConfiguration=Suspend-EMedicappExternalConfiguration
try {
$JavaHome=Resolve-EMedicappJavaHome $JavaHome
$PostgresBin=Resolve-EMedicappPostgresBin $PostgresBin
$NodeHome=Resolve-EMedicappNodeHome $NodeHome
foreach ($required in @((Join-Path $JavaHome 'bin\java.exe'),(Join-Path $PostgresBin 'psql.exe'),(Join-Path $NodeHome 'npm.cmd'))) {
  if (-not (Test-Path -LiteralPath $required)) { throw "Falta un componente de verificación: $required" }
}
& (Join-Path $PSScriptRoot 'Database.ps1') -Environment Test -PostgresBin $PostgresBin
$credentials=Get-Content -LiteralPath (Join-Path $runtimePath 'local-db.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($credentials.user -ne 'emedicapp' -or $credentials.port -lt 1024 -or $credentials.port -gt 65535) { throw 'La configuración no corresponde al clúster propio.' }
$databasePort=[int]$credentials.port
$token=(Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmssfff')+'_'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$databaseName='emedicapp_qa_'+$token+'_demo'
if ($databaseName -notmatch '^emedicapp_qa_[a-z0-9_]+_demo$') { throw 'Nombre de base QA inválido.' }
$logPath=Join-Path $runtimePath ('qa-'+$token+'.log')
$statePath=Join-Path $runtimePath ('qa-'+$token+'.json')
$keys=@('JAVA_HOME','PATH','PGPASSWORD','EMEDICAPP_TEST_DB_URL','EMEDICAPP_TEST_DB_USER','EMEDICAPP_TEST_DB_PASSWORD','EMEDICAPP_DOCUMENTS_DIR','EMEDICAPP_SMTP_HOST')
$saved=@{}; foreach ($key in $keys) { $saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process') }
$state=@{database=$databaseName;createdAt=(Get-Date).ToUniversalTime().ToString('o');log=$logPath;package=[bool]$Package}
try {
  $password=[Net.NetworkCredential]::new('',(ConvertTo-SecureString $credentials.protectedPassword)).Password
  $env:PGPASSWORD=$password
  $psql=Join-Path $PostgresBin 'psql.exe'
  $dataDirectory=& $psql -X -h 127.0.0.1 -p $databasePort -U emedicapp -d postgres -tA -v ON_ERROR_STOP=1 -c "SELECT current_setting('data_directory')"
  if ($LASTEXITCODE -or [IO.Path]::GetFullPath($dataDirectory.Trim()) -ne $clusterPath) { throw 'La conexión no pertenece al directorio del clúster propio.' }
  $exists=& $psql -X -h 127.0.0.1 -p $databasePort -U emedicapp -d postgres -tA -v ON_ERROR_STOP=1 -c "SELECT 1 FROM pg_database WHERE datname='$databaseName'"
  if ($LASTEXITCODE -or $exists -eq '1') { throw 'No se pudo confirmar una base QA nueva. No se borra ni reutiliza ninguna base.' }
  & $psql -X -h 127.0.0.1 -p $databasePort -U emedicapp -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $databaseName OWNER emedicapp"
  if ($LASTEXITCODE) { throw 'No se pudo crear la base QA.' }
  $env:JAVA_HOME=$JavaHome
  $env:PATH=$NodeHome+';'+$env:PATH
  $env:EMEDICAPP_TEST_DB_URL="jdbc:postgresql://127.0.0.1:$databasePort/$databaseName"
  $env:EMEDICAPP_TEST_DB_USER='emedicapp'
  $env:EMEDICAPP_TEST_DB_PASSWORD=$password
  $env:EMEDICAPP_DOCUMENTS_DIR=Join-Path $runtimePath ('documents-qa-'+$token)
  # QA never sends messages through an inherited real provider.
  $env:EMEDICAPP_SMTP_HOST=$null
  Push-Location $projectRoot
  try {
    # Each cached test application owns a pool. Keep the complete suite below
    # the local cluster's connection limit without changing the running app.
    $mavenArguments=@('-B','-ntp','-f','backend\pom.xml','-Dspring.datasource.hikari.maximum-pool-size=4','-Dspring.datasource.hikari.minimum-idle=0')
    if ($Package) {
      # Packaging QA must never replace a JAR currently used by the presentation.
      $qaArtifactName='emedicapp-qa-'+$token
      $state.jar=Join-Path $projectRoot ('backend\target\'+$qaArtifactName+'.jar')
      $mavenArguments+=@('-Pfrontend',('-Dnpm.executable='+(Join-Path $NodeHome 'npm.cmd')),('-Demedicapp.artifact-name='+$qaArtifactName),'package')
    } else { $mavenArguments+='test' }
    $previousErrorAction=$ErrorActionPreference
    try {
      $ErrorActionPreference='Continue'
      & '.\backend\mvnw.cmd' @mavenArguments *>&1 | Tee-Object -FilePath $logPath
      $state.exitCode=$LASTEXITCODE
    } finally { $ErrorActionPreference=$previousErrorAction }
    if ($state.exitCode) { throw "Falló la verificación; se conserva la evidencia en $logPath" }
  } finally { Pop-Location }
} finally {
  $state.finishedAt=(Get-Date).ToUniversalTime().ToString('o')
  $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
  foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') }
  Write-Host "Evidencia QA: $statePath"
}
} finally { Restore-EMedicappExternalConfiguration $isolatedConfiguration }
