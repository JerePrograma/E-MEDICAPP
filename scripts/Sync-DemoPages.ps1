param([switch]$Force,[switch]$Wait)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'DemoPages.Common.ps1')

# A Quick Tunnel changes its hostname at each start. Its destination is published
# centrally; visitors never choose a backend through a query or browser setting.
if (-not (Test-Path -LiteralPath $demoPagesStatePath)) { throw 'Primero inicia la demo de Pages.' }
$state=Get-Content -LiteralPath $demoPagesStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($state.status -ne 'UP' -or -not $state.apiUrl) { throw 'La demo de Pages no esta disponible.' }
$expectedPages=@('https://scalaris.com.ar/e-medicapp/','https://jereprograma.github.io/E-MEDICAPP/')
if ($state.pagesUrl -notin $expectedPages) { throw 'La publicacion automatica solo corresponde a la pagina de este proyecto.' }
$origin=& git -C $demoProjectRoot remote get-url origin
if ($LASTEXITCODE -or $origin.Trim() -notmatch '^(https://github\.com/|git@github\.com:)JerePrograma/E-MEDICAPP(?:\.git)?$') {
  throw 'El repositorio remoto no corresponde a la pagina de este proyecto.'
}
$jarPath=Join-Path $demoProjectRoot 'backend\target\emedicapp-0.1.0-SNAPSHOT.jar'
if (-not (Get-DemoOwnedProcess $state.backend @('-jar',$jarPath)) -or
    -not (Get-DemoOwnedProcess $state.tunnel @('tunnel',"http://127.0.0.1:$($state.port)"))) {
  throw 'Los procesos de la demo no estan activos. Se conserva la configuracion publicada.'
}
$gh=Get-Command gh.exe -ErrorAction SilentlyContinue
if (-not $gh) { throw 'Falta GitHub CLI para actualizar la conexion de la pagina publica.' }
$repository='JerePrograma/E-MEDICAPP'
$expectedCommit=& $gh.Source api "repos/$repository/git/ref/heads/main" --jq '.object.sha'
if ($LASTEXITCODE -or $expectedCommit -notmatch '^[a-f0-9]{40}$') { throw 'No se pudo confirmar el commit remoto que debe publicarse.' }
$previous=& $gh.Source variable get VITE_API_BASE_URL --repo $repository 2>$null
$readSucceeded=$LASTEXITCODE -eq 0
$unchanged=$readSucceeded -and (($previous | Out-String).Trim() -eq [string]$state.apiUrl)
if (-not $unchanged) {
  & $gh.Source variable set VITE_API_BASE_URL --repo $repository --body $state.apiUrl
  if ($LASTEXITCODE) { throw 'No se pudo actualizar la direccion publica. Revisa el acceso de GitHub CLI.' }
}
function Get-PublishedDemoConfiguration {
  try {
    $nonce=[DateTime]::UtcNow.Ticks
    return Invoke-RestMethod ($state.pagesUrl+'deployment.json?check='+$nonce) -Headers @{'Cache-Control'='no-cache'} -TimeoutSec 8
  } catch { return $null }
}
$previousPublication=Get-PublishedDemoConfiguration
if (-not $Force -and $previousPublication -and ($previousPublication.backendUrl -eq $state.apiUrl) -and ($previousPublication.commit -eq $expectedCommit)) {
  Write-Host 'La pagina publica ya utiliza el commit remoto y el servidor actual de la demo.'
  return
}
& $gh.Source workflow run pages.yml --repo $repository --ref main
if ($LASTEXITCODE) { throw 'La direccion se guardo, pero falta ejecutar el flujo Frontend en GitHub Pages.' }
if (-not $Wait) {
  Write-Host 'Se solicito publicar la conexion central. El enlace general funcionara al completar la publicacion.'
  return
}
Write-Host 'Esperando que la pagina publica confirme el servidor actual...'
$deadline=[DateTime]::UtcNow.AddSeconds(180)
do {
  $published=Get-PublishedDemoConfiguration
  $backendMatches=$published -and ($published.backendUrl -eq $state.apiUrl)
  $commitMatches=$published -and ($published.commit -eq $expectedCommit)
  $freshPublication=$published -and $published.runId -and ($published.runId -ne $previousPublication.runId)
  if ($backendMatches -and $commitMatches -and (-not $Force -or $freshPublication)) {
    Write-Host 'La pagina publica confirmo el commit remoto y el servidor actual. Ya se puede compartir el enlace general.'
    return
  }
  Start-Sleep -Seconds 3
} while ([DateTime]::UtcNow -lt $deadline)
throw 'La pagina publica todavia no confirmo la nueva conexion. Revisa GitHub Actions y repeti Sync-DemoPages.ps1 -Wait.'
