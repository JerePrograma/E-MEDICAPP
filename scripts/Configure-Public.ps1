param([string]$PublicUrl)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Public.Common.ps1')
if (Test-Path -LiteralPath $publicStatePath) {
  $existing=Get-Content -LiteralPath $publicStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
  if ($existing.tunnel -and (Get-Process -Id ([int]$existing.tunnel.processId) -ErrorAction SilentlyContinue)) {
    throw 'Detené la publicación antes de cambiar su cuenta o dirección. No se altera un túnel activo.'
  }
}
if (-not $PublicUrl) { $PublicUrl=Read-Host 'URL HTTPS estable asignada en tu cuenta de ngrok' }
$url=Resolve-PublicUrl $PublicUrl
$token=Read-Host 'Authtoken de ngrok (entrada oculta; no se guarda en texto)' -AsSecureString
try {
  if ($token.Length -lt 16 -or $token.Length -gt 1024) { throw 'Ingresá el authtoken completo desde tu cuenta de ngrok.' }
  New-Item -ItemType Directory -Force -Path $publicRuntimePath | Out-Null
  $configuration=@{version=1;publicUrl=$url;protectedAuthtoken=(ConvertFrom-SecureString $token);configuredAt=(Get-Date).ToUniversalTime().ToString('o')}
  $configuration | ConvertTo-Json | Set-Content -LiteralPath $publicConfigPath -Encoding UTF8
} finally { $token.Dispose() }
Write-Host "Configuración guardada para $url. El token está protegido para tu usuario de Windows."
