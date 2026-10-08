$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Public.Common.ps1')
if (-not (Test-Path -LiteralPath $publicStatePath)) { Write-Host 'No hay una publicación registrada.'; return }
$state=Get-Content -LiteralPath $publicStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($state.localPort -ne 8084 -or $state.inspectorPort -ne 4041 -or $state.agentPath -ne $publicAgentPath -or $state.jarPath -ne $publicJarPath) { throw 'El registro no corresponde a la publicación local de E-MEDICAPP. Se preservan sus procesos.' }
$failures=New-Object 'System.Collections.Generic.List[string]'
try {
  $tunnel=Get-OwnPublicProcess $state.tunnel $publicAgentPath @($publicAgentConfigPath,'127.0.0.1:8084','--inspect=false')
  if ($tunnel) { Stop-Process -Id $tunnel.Id -ErrorAction Stop }
  $state.tunnelStopped=$true
} catch { $failures.Add($_.Exception.Message) }
try {
  if ($state.manual) {
    $manual=Get-OwnPublicProcess $state.manual $state.manual.executable @('-jar',$publicJarPath)
    if ($manual) {
      $otherListener=Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.OwningProcess -eq $manual.Id -and $_.LocalPort -ne 8084 }
      if ($otherListener) { throw 'El proceso registrado escucha en otro puerto. No se detiene una aplicación ajena a la publicación.' }
      if (-not (Test-Path -LiteralPath $publicManualStatePath)) { throw 'Falta el registro de la aplicación MANUAL. Se conserva para revisar su propiedad.' }
      $manualState=Get-Content -LiteralPath $publicManualStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
      if ($manualState.port -ne 8084 -or $manualState.environment -ne 'Manual' -or $manualState.processId -ne $manual.Id) { throw 'El registro MANUAL pertenece a otra sesión. No se modifica.' }
      & (Join-Path $PSScriptRoot 'Stop.ps1') -Environment Manual
    }
  }
  $state.manualStopped=$true
} catch { $failures.Add($_.Exception.Message) }
$state.status=if ($failures.Count) { 'STOP_NEEDS_REVIEW' } else { 'STOPPED' }
$state.stoppedAt=(Get-Date).ToUniversalTime().ToString('o')
Write-PublicState $state
if ($failures.Count) { throw ($failures -join ' ') }
Write-Host 'Publicación detenida. Se conservan los datos MANUAL y la base. La demostración de 8082 permanece independiente.'
