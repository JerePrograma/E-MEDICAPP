param([ValidateSet('Demo','Manual')][string]$Environment='Manual',[string]$Destination)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $Destination) { $Destination=Join-Path $projectRoot '.runtime\backups' }
$destinationRoot=[IO.Path]::GetFullPath($Destination)
$backupPath=Join-Path $destinationRoot ("emedicapp-{0}-{1}" -f $Environment,(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $backupPath -Force | Out-Null
& (Join-Path $PSScriptRoot 'Database.ps1') -Action Start -Environment $Environment
$credentials=Get-Content -LiteralPath (Join-Path $projectRoot '.runtime\local-db.json') -Raw -Encoding UTF8 | ConvertFrom-Json
. (Join-Path $PSScriptRoot 'Tools.Common.ps1')
$postgresBin=Resolve-EMedicappPostgresBin
$databaseName=if ($Environment -eq 'Demo') {'emedicapp_demo'}else{'emedicapp_manual'}
$savedPassword=$env:PGPASSWORD
try {
  $env:PGPASSWORD=[Net.NetworkCredential]::new('',(ConvertTo-SecureString $credentials.protectedPassword)).Password
  & (Join-Path $postgresBin 'pg_dump.exe') -h 127.0.0.1 -p $credentials.port -U emedicapp -d $databaseName -F c -f (Join-Path $backupPath 'database.dump')
  if ($LASTEXITCODE) { throw 'No se completó el respaldo de la base.' }
  & (Join-Path $postgresBin 'pg_restore.exe') --list (Join-Path $backupPath 'database.dump') | Set-Content -LiteralPath (Join-Path $backupPath 'database-index.txt') -Encoding UTF8
  if ($LASTEXITCODE) { throw 'El archivo de respaldo no se pudo leer.' }
  $documents=Join-Path $projectRoot ".runtime\documents-$Environment"
  if (Test-Path -LiteralPath $documents) { Copy-Item -LiteralPath $documents -Destination (Join-Path $backupPath 'documents') -Recurse }
  @{
    environment=$Environment; database=$databaseName; createdAt=(Get-Date).ToUniversalTime().ToString('o');
    format='PostgreSQL custom + private documents'; schemaVersion='Flyway';
    files=@(Get-ChildItem -LiteralPath $backupPath -Recurse -File | ForEach-Object { @{path=$_.FullName.Substring($backupPath.Length+1); sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash} })
  } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $backupPath 'manifest.json') -Encoding UTF8
  Write-Host "Respaldo creado en $backupPath"
}
finally { $env:PGPASSWORD=$savedPassword }
