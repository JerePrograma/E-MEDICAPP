param([int]$Port=55432, [string]$PostgresBin=$env:EMEDICAPP_PG_BIN)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimePath=Join-Path $projectRoot '.runtime'
$appCredentialsPath=Join-Path $runtimePath 'manual-db.json'
$databaseName='emedicapp_manual'
$appUser='emedicapp_manual_app'
if (-not $PostgresBin) { $PostgresBin='C:\Program Files\PostgreSQL\18\bin' }
$psql=Join-Path $PostgresBin 'psql.exe'
& (Join-Path $PSScriptRoot 'Database.ps1') -Environment Manual -Port $Port -PostgresBin $PostgresBin
$admin=Get-Content -LiteralPath (Join-Path $runtimePath 'local-db.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$previousPgPassword=$env:PGPASSWORD
try {
  $env:PGPASSWORD=[Net.NetworkCredential]::new('',(ConvertTo-SecureString $admin.protectedPassword)).Password
  $actualPath=& $psql -X -h 127.0.0.1 -p $Port -U $admin.user -d postgres -tA -c 'SHOW data_directory'
  if ($LASTEXITCODE -or [IO.Path]::GetFullPath($actualPath.Trim()) -ne [IO.Path]::GetFullPath((Join-Path $runtimePath 'postgres'))) { throw 'La conexión no corresponde a la base local propia. Se preserva.' }
  $roleExists=& $psql -X -h 127.0.0.1 -p $Port -U $admin.user -d postgres -tA -c "SELECT 1 FROM pg_roles WHERE rolname='$appUser'"
  if ($LASTEXITCODE) { throw 'No se pudo comprobar el usuario de operación.' }
  if (Test-Path -LiteralPath $appCredentialsPath) {
    $app=Get-Content -LiteralPath $appCredentialsPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($app.user -ne $appUser -or $app.database -ne $databaseName -or $app.port -ne $Port -or -not $app.protectedPassword) { throw 'La configuración de base MANUAL no coincide; se preserva para revisar.' }
    $appPassword=[Net.NetworkCredential]::new('',(ConvertTo-SecureString $app.protectedPassword)).Password
  } else {
    if ($roleExists -eq '1') { throw 'Ya existe el usuario de operación sin sus credenciales propias. No se adopta ni cambia su contraseña.' }
    $randomBytes=New-Object byte[] 32
    $generator=[Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($randomBytes) } finally { $generator.Dispose() }
    $appPassword=[Convert]::ToBase64String($randomBytes)
    $protectedPassword=ConvertFrom-SecureString (ConvertTo-SecureString $appPassword -AsPlainText -Force)
    @{user=$appUser;database=$databaseName;port=$Port;protectedPassword=$protectedPassword} | ConvertTo-Json | Set-Content -LiteralPath $appCredentialsPath -Encoding UTF8
  }
  if ($appPassword -notmatch '^[A-Za-z0-9+/=]{40,}$') { throw 'La credencial de base no tiene el formato generado por este proyecto.' }
  $memberships=& $psql -X -h 127.0.0.1 -p $Port -U $admin.user -d postgres -tA -c "SELECT count(*) FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member WHERE r.rolname='$appUser'"
  if ($LASTEXITCODE -or $memberships -ne '0') { throw 'El usuario de operación tiene membresías inesperadas. Se preserva para revisar.' }
  if ($roleExists -eq '1') {
    $env:PGPASSWORD=$appPassword
    $identity=& $psql -X -h 127.0.0.1 -p $Port -U $appUser -d $databaseName -tA -c 'SELECT current_user' 2>&1
    if ($LASTEXITCODE -or $identity -ne $appUser) { throw 'Las credenciales limitadas no acreditan el usuario existente. No se modifican sus permisos.' }
    $env:PGPASSWORD=[Net.NetworkCredential]::new('',(ConvertTo-SecureString $admin.protectedPassword)).Password
  }
  if ($roleExists -ne '1') {
    $secretSql="CREATE ROLE $appUser LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '$appPassword';"
    $privateResult=$secretSql | & $psql -X -q -h 127.0.0.1 -p $Port -U $admin.user -d postgres -v ON_ERROR_STOP=1 -f - 2>&1
    if ($LASTEXITCODE) { throw 'No se pudo crear el usuario limitado. Los errores con material de autenticación no se muestran.' }
    $secretSql=$null; $privateResult=$null
  }
  $ownershipSql=@'
BEGIN;
ALTER ROLE emedicapp_manual_app NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER DATABASE emedicapp_manual OWNER TO emedicapp_manual_app;
ALTER SCHEMA public OWNER TO emedicapp_manual_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('ALTER TABLE %I.%I OWNER TO emedicapp_manual_app', schemaname, tablename)
FROM pg_tables WHERE schemaname='public' AND tableowner='emedicapp'
\gexec
SELECT format('ALTER SEQUENCE %I.%I OWNER TO emedicapp_manual_app', schemaname, sequencename)
FROM pg_sequences WHERE schemaname='public' AND sequenceowner='emedicapp'
\gexec
SELECT format('ALTER VIEW %I.%I OWNER TO emedicapp_manual_app', schemaname, viewname)
FROM pg_views WHERE schemaname='public' AND viewowner='emedicapp'
\gexec
COMMIT;
'@
  $ownershipSql | & $psql -X -q -h 127.0.0.1 -p $Port -U $admin.user -d $databaseName -v ON_ERROR_STOP=1 -f -
  if ($LASTEXITCODE) { throw 'No se pudieron asignar los permisos de la base MANUAL.' }
  $env:PGPASSWORD=$appPassword
  $verified=& $psql -X -h 127.0.0.1 -p $Port -U $appUser -d $databaseName -tA -c "SELECT current_user || ':' || (NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit)::text FROM pg_roles WHERE rolname=current_user"
  if ($LASTEXITCODE -or $verified -ne ($appUser+':true')) { throw 'El acceso limitado no confirmó sus permisos. No se inicia la aplicación.' }
  Write-Output "Base MANUAL verificada: $appUser sin privilegios de administración del clúster."
} finally {
  $env:PGPASSWORD=$previousPgPassword
  $appPassword=$null; $secretSql=$null; $privateResult=$null
}
