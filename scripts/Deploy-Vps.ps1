param([string]$SshHost='scalaris-vps')
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $projectRoot
try {
  if (git status --porcelain) { throw 'Guardar y publicar los cambios del proyecto antes de desplegar una nueva version.' }
  $commit=(git rev-parse HEAD).Trim()
  $archive=Join-Path $projectRoot '.runtime\vps-source.tar.gz'
  git archive --format=tar.gz -o $archive HEAD
  if ($LASTEXITCODE) { throw 'No se pudo preparar el codigo versionado.' }
  scp $archive "${SshHost}:/srv/apps/emedicapp/source.tar.gz"
  if ($LASTEXITCODE) { throw 'No se pudo subir el paquete.' }
  $remote="set -eu; cd /srv/apps/emedicapp; sudo /usr/local/sbin/scalaris-app-backup; tar -xzf source.tar.gz; cp deployment/vps/compose.yaml compose.yaml; cp deployment/vps/.dockerignore .dockerignore; sed -i 's/^BUILD_COMMIT=.*/BUILD_COMMIT=$commit/' .env; docker compose build app; docker compose up -d app"
  ssh $SshHost $remote
  if ($LASTEXITCODE) { throw 'El despliegue no termino correctamente. Revisar los registros del servidor.' }
  Write-Host "Version $commit desplegada. Verificar salud y publicar el frontend de Pages correspondiente."
} finally { Pop-Location }
