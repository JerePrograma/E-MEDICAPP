param([string]$JavaHome=$env:EMEDICAPP_JAVA_HOME)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $JavaHome) { $JavaHome='C:\Program Files\Java\jdk-25.0.4.1+1' }
& (Join-Path $PSScriptRoot 'Database.ps1') -Environment Test
$credentials=Get-Content -LiteralPath (Join-Path $projectRoot '.runtime\local-db.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$saved=@{}
foreach ($key in @('JAVA_HOME','EMEDICAPP_TEST_DB_URL','EMEDICAPP_TEST_DB_USER','EMEDICAPP_TEST_DB_PASSWORD')) { $saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process') }
try {
  $env:JAVA_HOME=$JavaHome
  $env:EMEDICAPP_TEST_DB_URL="jdbc:postgresql://127.0.0.1:$($credentials.port)/emedicapp_test_demo"
  $env:EMEDICAPP_TEST_DB_USER='emedicapp'
  $env:EMEDICAPP_TEST_DB_PASSWORD=[Net.NetworkCredential]::new('',(ConvertTo-SecureString $credentials.protectedPassword)).Password
  Push-Location $projectRoot
  try { & '.\backend\mvnw.cmd' -B -ntp -f backend\pom.xml test; if ($LASTEXITCODE) { throw 'Falló la verificación del backend.' } }
  finally { Pop-Location }
}
finally { foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') } }
