param([string]$SmtpHost,[int]$Port=0,[string]$From,[string]$BaseUrl,[ValidateSet('StartTls','Ssl','Local')][string]$Transport)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Mail.Common.ps1')
$mailProjectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$mailRuntimePath=Join-Path $mailProjectRoot '.runtime'
$mailSettingsPath=Join-Path $mailRuntimePath 'mail-settings.json'

Write-Host 'Preparación de correo para operación MANUAL. Este paso no inicia la aplicación ni envía correos.'
if (-not $SmtpHost) { $SmtpHost=Read-Host 'Servidor SMTP del proveedor (dominio o IP, sin protocolo)' }
if (-not $Transport) {
  $choice=(Read-Host 'Seguridad: STARTTLS, SSL o LOCAL (prueba local sin cifrado) [STARTTLS]').Trim()
  if (-not $choice) { $choice='StartTls' }
  if ($choice -notin @('StartTls','Ssl','Local')) { throw 'Elegí STARTTLS, SSL o LOCAL.' }
  $Transport=$choice
}
if ($Port -eq 0) {
  $suggestedPort=if ($Transport -eq 'Ssl') {465} elseif ($Transport -eq 'Local') {2525} else {587}
  $inputPort=(Read-Host "Puerto SMTP [$suggestedPort]").Trim()
  if (-not $inputPort) { $Port=$suggestedPort }
  elseif (-not [int]::TryParse($inputPort,[ref]$Port)) { throw 'Ingresá un puerto SMTP numérico.' }
}
if (-not $From) { $From=Read-Host 'Correo remitente autorizado por el proveedor' }
if (-not $BaseUrl) {
  $suggestedUrl=$env:EMEDICAPP_PUBLIC_URL
  $inputUrl=Read-Host $(if ($suggestedUrl) {"Dirección HTTPS de E-MEDICAPP [$suggestedUrl]"} else {'Dirección HTTPS de E-MEDICAPP (o HTTP loopback para una prueba local)'})
  $BaseUrl=if ($inputUrl) {$inputUrl} else {$suggestedUrl}
}
$startTls=$Transport -eq 'StartTls'; $ssl=$Transport -eq 'Ssl'
# Validate public settings before requesting credentials. No network checks or mail send.
$parameters=Resolve-MailParameters -SmtpHost $SmtpHost -Port $Port -From $From -BaseUrl $BaseUrl -StartTls $startTls -Ssl $ssl -Username '' -Password ''
$authentication=(Read-Host '¿El SMTP requiere usuario y contraseña? [S/n]').Trim()
if (-not $authentication) { $authentication='S' }
if ($authentication -notin @('S','Si','Sí','N','No')) { throw 'Respondé S o N.' }
$userSecret=$null; $passwordSecret=$null; $username=''; $password=''; $temporaryPath=$null
try {
  $protectedUsername=$null; $protectedPassword=$null
  if ($authentication -in @('S','Si','Sí')) {
    $userSecret=Read-Host 'Usuario SMTP (entrada oculta)' -AsSecureString
    $passwordSecret=Read-Host 'Contraseña SMTP o contraseña de aplicación (entrada oculta)' -AsSecureString
    if ($userSecret.Length -eq 0 -or $passwordSecret.Length -eq 0) { throw 'Ingresá ambos datos de autenticación o elegí SMTP sin autenticación.' }
    $username=[Net.NetworkCredential]::new('',$userSecret).Password
    $password=[Net.NetworkCredential]::new('',$passwordSecret).Password
    if ([string]::IsNullOrWhiteSpace($username) -or [string]::IsNullOrWhiteSpace($password)) { throw 'Ingresá usuario y contraseña completos o elegí SMTP sin autenticación.' }
    $parameters=Resolve-MailParameters -SmtpHost $SmtpHost -Port $Port -From $From -BaseUrl $BaseUrl -StartTls $startTls -Ssl $ssl -Username $username -Password $password
    $protectedUsername=ConvertFrom-SecureString $userSecret
    $protectedPassword=ConvertFrom-SecureString $passwordSecret
  }
  New-Item -ItemType Directory -Force -Path $mailRuntimePath | Out-Null
  $configuration=@{version=1;host=$parameters.EMEDICAPP_SMTP_HOST;port=$Port;from=$parameters.EMEDICAPP_MAIL_FROM;baseUrl=$parameters.EMEDICAPP_EMAIL_BASE_URL;startTls=$startTls;ssl=$ssl;protectedUsername=$protectedUsername;protectedPassword=$protectedPassword;configuredAt=(Get-Date).ToUniversalTime().ToString('o')}
  $temporaryPath=Join-Path $mailRuntimePath ('mail-settings.'+[Guid]::NewGuid().ToString('N')+'.tmp')
  [IO.File]::WriteAllText($temporaryPath,($configuration | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
  if (Test-Path -LiteralPath $mailSettingsPath) { [IO.File]::Replace($temporaryPath,$mailSettingsPath,$null) }
  else { [IO.File]::Move($temporaryPath,$mailSettingsPath) }
} finally {
  if ($temporaryPath -and (Test-Path -LiteralPath $temporaryPath)) { Remove-Item -LiteralPath $temporaryPath -Force }
  if ($userSecret) { $userSecret.Dispose() }
  if ($passwordSecret) { $passwordSecret.Dispose() }
  $username=$null; $password=$null; $parameters=$null
}
Write-Host 'Configuración guardada. Usuario y contraseña están protegidos para este usuario de Windows.'
Write-Host 'Se aplicará en el próximo inicio MANUAL. Si la aplicación está abierta, detenela con su acceso habitual y volvé a iniciarla.'
Write-Host 'No se envió ningún correo de prueba. Aún falta comprobar entrega, remitente autorizado y recepción de los enlaces.'
