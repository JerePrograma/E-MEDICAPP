param([ValidateSet('Demo','Manual')][string]$Environment='Demo')
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$statePath=Join-Path $projectRoot ".runtime\app-$Environment.json"
if (-not (Test-Path -LiteralPath $statePath)) { Write-Host 'No hay un proceso de la aplicación registrado.'; return }
$state=Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$($state.processId)"
$jarPath=Join-Path $projectRoot 'backend\target\emedicapp-0.1.0-SNAPSHOT.jar'
if ($process) {
  if (-not $process.CommandLine.Contains($jarPath) -or $process.Name -ne 'java.exe') { throw 'El identificador pertenece a otro proceso. Se preserva.' }
  Stop-Process -Id $state.processId -ErrorAction Stop
}
Remove-Item -LiteralPath $statePath
Write-Host 'Aplicación detenida. La base de datos conserva sus datos.'
