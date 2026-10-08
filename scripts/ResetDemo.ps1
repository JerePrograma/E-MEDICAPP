param([switch]$NoStart,[switch]$OpenBrowser)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimeRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot '.runtime'))
$archiveName='emedicapp_archive_'+(Get-Date -Format 'yyyyMMdd_HHmmss_fff')+'_demo'
if ($archiveName -notmatch '^emedicapp_archive_[0-9_]+_demo$') { throw 'Nombre de archivo de demostración inválido.' }
& (Join-Path $PSScriptRoot 'Backup.ps1') -Environment Demo
& (Join-Path $PSScriptRoot 'Stop.ps1') -Environment Demo
$credentials=Get-Content -LiteralPath (Join-Path $runtimeRoot 'local-db.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$postgresBin=if ($env:EMEDICAPP_PG_BIN) {$env:EMEDICAPP_PG_BIN}else{'C:\Program Files\PostgreSQL\18\bin'}
$savedPassword=$env:PGPASSWORD
try {
  $env:PGPASSWORD=[Net.NetworkCredential]::new('',(ConvertTo-SecureString $credentials.protectedPassword)).Password
  $psql=Join-Path $postgresBin 'psql.exe'
  $connections=& $psql -h 127.0.0.1 -p $credentials.port -U emedicapp -d postgres -At -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM pg_stat_activity WHERE datname='emedicapp_demo'"
  if ($LASTEXITCODE -or $connections.Trim() -ne '0') { throw 'La demostración tiene conexiones abiertas. Se preservan sus datos y procesos.' }
  & $psql -h 127.0.0.1 -p $credentials.port -U emedicapp -d postgres -v ON_ERROR_STOP=1 -c "ALTER DATABASE emedicapp_demo RENAME TO $archiveName"
  if ($LASTEXITCODE) { throw 'No se pudo archivar la demostración anterior.' }
  $documentsPath=[IO.Path]::GetFullPath((Join-Path $runtimeRoot 'documents-Demo'))
  $archivedDocuments=[IO.Path]::GetFullPath((Join-Path $runtimeRoot ('documents-'+$archiveName)))
  foreach ($path in @($documentsPath,$archivedDocuments)) {
    if (-not $path.StartsWith($runtimeRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'El destino de documentos está fuera del proyecto.' }
  }
  if (Test-Path -LiteralPath $archivedDocuments) { throw 'El archivo de documentos ya existe y se conserva.' }
  if (Test-Path -LiteralPath $documentsPath) { Move-Item -LiteralPath $documentsPath -Destination $archivedDocuments }
  & $psql -h 127.0.0.1 -p $credentials.port -U emedicapp -d postgres -v ON_ERROR_STOP=1 -c 'CREATE DATABASE emedicapp_demo OWNER emedicapp'
  if ($LASTEXITCODE) { throw "No se pudo crear la demostración nueva. Los datos anteriores se conservaron en $archiveName." }
  @{previousDatabase=$archiveName;previousDocuments=$archivedDocuments;newDatabase='emedicapp_demo';createdAt=(Get-Date).ToUniversalTime().ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtimeRoot 'demo-reset.json') -Encoding UTF8
} finally { $env:PGPASSWORD=$savedPassword }
Write-Host "Escenario anterior conservado en $archiveName; demostración nueva preparada."
if (-not $NoStart) { & (Join-Path $PSScriptRoot 'Start.ps1') -Environment Demo -OpenBrowser:$OpenBrowser }
