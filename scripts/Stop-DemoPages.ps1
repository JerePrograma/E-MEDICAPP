param()
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'DemoPages.Common.ps1')
if (-not (Test-Path -LiteralPath $demoPagesStatePath)) { Write-Host 'No hay una demo de Pages registrada.'; return }
$state=Get-Content -LiteralPath $demoPagesStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($state.status -eq 'STOPPED') { Write-Host 'La demo de Pages ya esta detenida.'; return }
$tunnel=Get-DemoOwnedProcess $state.tunnel @('tunnel',"http://127.0.0.1:$($state.port)")
if ($tunnel) { Stop-Process -Id $tunnel.Id -ErrorAction Stop; $tunnel.WaitForExit(5000) | Out-Null }
$jarPath=Join-Path $demoProjectRoot 'backend\target\emedicapp-0.1.0-SNAPSHOT.jar'
$backend=Get-DemoOwnedProcess $state.backend @('-jar',$jarPath)
if ($backend) { Stop-Process -Id $backend.Id -ErrorAction Stop; $backend.WaitForExit(5000) | Out-Null }
$appStatePath=Join-Path $demoRuntimePath 'app-Demo.json'
if ($state.backend -and (Test-Path -LiteralPath $appStatePath)) {
  $appState=Get-Content -LiteralPath $appStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
  if ($appState.processId -eq $state.backend.processId) { Remove-Item -LiteralPath $appStatePath }
}
$state.status='STOPPED'
$state | Add-Member -NotePropertyName stoppedAt -NotePropertyValue (Get-Date).ToUniversalTime().ToString('o') -Force
Write-DemoPagesState $state
Write-Host 'Backend y tunel de la demo detenidos. PostgreSQL, datos y documentos se conservan.'
