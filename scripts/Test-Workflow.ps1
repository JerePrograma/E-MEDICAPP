param(
  [string]$JavaHome=$env:EMEDICAPP_JAVA_HOME,
  [string]$PostgresBin=$env:EMEDICAPP_PG_BIN,
  [string]$NodeHome=$env:EMEDICAPP_NODE_HOME
)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimePath=Join-Path $projectRoot '.runtime'
$clusterPath=Join-Path $runtimePath 'postgres'
$credentialsPath=Join-Path $runtimePath 'local-db.json'
if (-not $JavaHome) { $JavaHome='C:\Program Files\Java\jdk-25.0.4.1+1' }
if (-not $PostgresBin) { $PostgresBin='C:\Program Files\PostgreSQL\18\bin' }
if (-not $NodeHome) { $NodeHome='C:\Users\Jerem\AppData\Local\Programs\nodejs24\node-v24.21.0-win-x64' }
$javaPath=Join-Path $JavaHome 'bin\java.exe'
$psqlPath=Join-Path $PostgresBin 'psql.exe'
$pgctlPath=Join-Path $PostgresBin 'pg_ctl.exe'
$npmPath=Join-Path $NodeHome 'npm.cmd'
$jarPath=Join-Path $projectRoot 'backend\target\emedicapp-0.1.0-SNAPSHOT.jar'
foreach ($required in @($javaPath,$psqlPath,$pgctlPath,$npmPath,$jarPath,$credentialsPath)) {
  if (-not (Test-Path -LiteralPath $required)) { throw "Falta un componente local: $required. Compilá el proyecto e iniciá su base antes de verificar el flujo." }
}
$version=& $javaPath --version | Out-String
if ($version -notmatch '(?m)^(openjdk|java) 25(\.| )') { throw 'La verificación requiere Java 25 LTS.' }
if (Get-NetTCPConnection -LocalPort 8083 -State Listen -ErrorAction SilentlyContinue) {
  throw 'El puerto local 8083 está ocupado. Se preserva el proceso existente; la verificación no adopta otra aplicación.'
}
$credentials=Get-Content -LiteralPath $credentialsPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($credentials.port -ne 55432 -or $credentials.user -ne 'emedicapp') { throw 'La configuración no corresponde al cluster local de E-MEDICAPP en 55432.' }
if (-not (Test-Path -LiteralPath (Join-Path $clusterPath 'PG_VERSION'))) { throw 'El cluster propio todavía no está inicializado.' }
& $pgctlPath status -D $clusterPath *> $null
if ($LASTEXITCODE) { throw 'La base propia no está iniciada. Iniciá E-MEDICAPP antes de ejecutar esta verificación.' }

# Check ownership before creating a separate, empty QA database. No DROP, rename, or data copy.
$clusterState=Get-Content -LiteralPath (Join-Path $clusterPath 'postmaster.pid') -Encoding UTF8
$postgresProcessId=[int]$clusterState[0]
$postgresProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$postgresProcessId"
$normalizedCommand=if ($postgresProcess) { $postgresProcess.CommandLine.Replace('/','\') } else { '' }
if (-not $postgresProcess -or $postgresProcess.Name -ne 'postgres.exe' -or $normalizedCommand.IndexOf($clusterPath,[StringComparison]::OrdinalIgnoreCase) -lt 0) {
  throw 'No se pudo confirmar la propiedad del proceso PostgreSQL. No se crea una base en un servicio ajeno.'
}
$postgresListener=Get-NetTCPConnection -LocalPort 55432 -State Listen -ErrorAction SilentlyContinue
if (-not $postgresListener -or $postgresListener.OwningProcess -notcontains $postgresProcessId) { throw 'El puerto de PostgreSQL no pertenece al cluster propio.' }

$token=(Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmssfff')+'_'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$databaseName='emedicapp_workflow_'+$token+'_demo'
if ($databaseName -notmatch '^emedicapp_workflow_[a-z0-9_]+_demo$') { throw 'Nombre de base QA inválido.' }
$documentsPath=Join-Path $runtimePath ('documents-workflow-'+$token)
if (Test-Path -LiteralPath $documentsPath) { throw 'El directorio de documentos QA ya existe. Se preserva.' }
$statePath=Join-Path $runtimePath ('workflow-'+$token+'.json')
$stdoutPath=Join-Path $runtimePath ('workflow-'+$token+'.stdout.log')
$stderrPath=Join-Path $runtimePath ('workflow-'+$token+'.stderr.log')
$browserLog=Join-Path $runtimePath 'verification-browser.log'
$keys=@('PGPASSWORD','PATH','EMEDICAPP_DB_PASSWORD','EMEDICAPP_DB_URL','EMEDICAPP_DB_USER','EMEDICAPP_MODE','EMEDICAPP_DOCUMENTS_DIR','EMEDICAPP_PORT','EMEDICAPP_ADDRESS','EMEDICAPP_SECURE_COOKIES','EMEDICAPP_E2E_URL','EMEDICAPP_WORKFLOW_DB','NO_COLOR')
$saved=@{}; foreach ($key in $keys) { $saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process') }
$startedProcess=$null
$startedTicks=$null
$state=@{database=$databaseName;documents=$documentsPath;port=8083;jar=$jarPath;createdAt=(Get-Date).ToUniversalTime().ToString('o');stopped=$false}
try {
  $password=[Net.NetworkCredential]::new('',(ConvertTo-SecureString $credentials.protectedPassword)).Password
  $env:PGPASSWORD=$password
  $dataDirectory=& $psqlPath -X -h 127.0.0.1 -p 55432 -U emedicapp -d postgres -tA -v ON_ERROR_STOP=1 -c "SELECT current_setting('data_directory')"
  if ($LASTEXITCODE -or [IO.Path]::GetFullPath($dataDirectory.Trim()) -ne $clusterPath) { throw 'La conexión SQL no corresponde al directorio del cluster propio.' }
  $exists=& $psqlPath -X -h 127.0.0.1 -p 55432 -U emedicapp -d postgres -tA -v ON_ERROR_STOP=1 -c "SELECT 1 FROM pg_database WHERE datname='$databaseName'"
  if ($LASTEXITCODE) { throw 'No se pudo comprobar el nombre de la nueva base QA.' }
  if ($exists -eq '1') { throw 'La base QA ya existe. No se reutiliza ni se borra.' }
  & $psqlPath -X -h 127.0.0.1 -p 55432 -U emedicapp -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $databaseName OWNER emedicapp"
  if ($LASTEXITCODE) { throw 'No se pudo crear la base aislada de verificación.' }
  New-Item -ItemType Directory -Path $documentsPath | Out-Null
  $env:EMEDICAPP_DB_PASSWORD=$password
  $env:EMEDICAPP_DB_URL="jdbc:postgresql://127.0.0.1:55432/$databaseName"
  $env:EMEDICAPP_DB_USER='emedicapp'
  $env:EMEDICAPP_MODE='DEMO'
  $env:EMEDICAPP_DOCUMENTS_DIR=$documentsPath
  $env:EMEDICAPP_ADDRESS='127.0.0.1'
  $env:EMEDICAPP_PORT='8083'
  $env:EMEDICAPP_SECURE_COOKIES='false'
  $env:EMEDICAPP_E2E_URL='http://127.0.0.1:8083'
  $env:EMEDICAPP_WORKFLOW_DB=$databaseName
  $env:PATH=$NodeHome+';'+$env:PATH
  $env:NO_COLOR=$null
  $startedProcess=Start-Process -FilePath $javaPath -ArgumentList @('-jar',('"'+$jarPath+'"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
  $startedTicks=$startedProcess.StartTime.ToUniversalTime().Ticks
  $state.processId=$startedProcess.Id
  $state.processStartedTicks=$startedTicks
  $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
  $deadline=[DateTime]::UtcNow.AddSeconds(45)
  $ready=$false
  while ([DateTime]::UtcNow -lt $deadline) {
    if ($startedProcess.HasExited) { throw "La aplicación QA finalizó al iniciar. Revisá $stdoutPath y $stderrPath." }
    try {
      $health=Invoke-RestMethod 'http://127.0.0.1:8083/actuator/health/readiness' -TimeoutSec 2
      if ($health.status -eq 'UP') { $ready=$true; break }
    } catch { }
    Start-Sleep -Milliseconds 500
  }
  if (-not $ready) { throw 'La aplicación QA no confirmó disponibilidad dentro de 45 segundos.' }
  $workflowListener=Get-NetTCPConnection -LocalPort 8083 -State Listen -ErrorAction SilentlyContinue
  if (-not $workflowListener -or $workflowListener.OwningProcess -notcontains $startedProcess.Id) { throw 'El puerto QA no pertenece al proceso iniciado. No se ejecutan pruebas contra otra aplicación.' }
  $bootstrap=Invoke-RestMethod 'http://127.0.0.1:8083/api/bootstrap' -TimeoutSec 3
  if ($bootstrap.mode -ne 'DEMO') { throw 'La aplicación QA no informó el modo DEMO esperado.' }
  Write-Host "Verificación aislada en http://127.0.0.1:8083. Base: $databaseName"
  Push-Location (Join-Path $projectRoot 'frontend')
  try {
    $savedErrorAction=$ErrorActionPreference
    try {
      $ErrorActionPreference='Continue'
      & $npmPath run test:e2e *>&1 | Tee-Object -FilePath $browserLog
      $testExitCode=$LASTEXITCODE
    } finally { $ErrorActionPreference=$savedErrorAction }
    $state.testExitCode=$testExitCode
    if ($testExitCode) { throw 'Falló un recorrido en navegador. La base, los documentos y registros QA se conservan para revisarlo.' }
  } finally { Pop-Location }
} finally {
  if ($startedProcess) {
    $running=Get-Process -Id $startedProcess.Id -ErrorAction SilentlyContinue
    if ($running) {
      $identity=Get-CimInstance Win32_Process -Filter "ProcessId=$($startedProcess.Id)"
      if ($identity -and $identity.Name -eq 'java.exe' -and $identity.CommandLine.Contains($jarPath) -and $running.StartTime.ToUniversalTime().Ticks -eq $startedTicks) {
        Stop-Process -Id $startedProcess.Id
        $state.stopped=$true
      } else { Write-Warning 'El PID ya no coincide con la aplicación QA iniciada. Se preserva el proceso para revisión.' }
    } else { $state.stopped=$true }
  }
  $state.finishedAt=(Get-Date).ToUniversalTime().ToString('o')
  $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
  foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') }
  Write-Host "Evidencia QA conservada: $statePath. La verificación usa su propia base y sus documentos."
}
