param([switch]$Force)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'DemoPages.Common.ps1')

# A Quick Tunnel changes its hostname at each start. Update the compiled default
# as well as the complete sharing link so a new device can open the bare Pages URL.
if (-not (Test-Path -LiteralPath $demoPagesStatePath)) { throw 'Primero inicia la demo de Pages.' }
$state=Get-Content -LiteralPath $demoPagesStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($state.status -ne 'UP' -or -not $state.apiUrl) { throw 'La demo de Pages no esta disponible.' }
$expectedPages='https://jereprograma.github.io/E-MEDICAPP/'
if ($state.pagesUrl -ne $expectedPages) { throw 'La publicacion automatica solo corresponde a la pagina de este proyecto.' }
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
if (-not $gh) { throw 'Falta GitHub CLI para actualizar la pagina publica; el enlace completo sigue disponible.' }
$repository='JerePrograma/E-MEDICAPP'
$previous=& $gh.Source variable get VITE_API_BASE_URL --repo $repository 2>$null
$readSucceeded=$LASTEXITCODE -eq 0
$unchanged=$readSucceeded -and (($previous | Out-String).Trim() -eq [string]$state.apiUrl)
if ($unchanged -and -not $Force) {
  Write-Host 'La direccion predeterminada de Pages ya esta configurada para esta demo.'
  return
}
if (-not $unchanged) {
  & $gh.Source variable set VITE_API_BASE_URL --repo $repository --body $state.apiUrl
  if ($LASTEXITCODE) { throw 'No se pudo actualizar la direccion publica. Revisa el acceso de GitHub CLI.' }
}
& $gh.Source workflow run pages.yml --repo $repository --ref main
if ($LASTEXITCODE) { throw 'La direccion se guardo, pero falta ejecutar el flujo Frontend en GitHub Pages.' }
Write-Host 'Se solicito publicar Pages con la direccion actual. El enlace general funcionara al completar la publicacion.'
