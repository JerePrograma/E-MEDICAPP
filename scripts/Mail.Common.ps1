$mailEnvironmentKeys=@('EMEDICAPP_SMTP_HOST','EMEDICAPP_SMTP_PORT','EMEDICAPP_SMTP_USERNAME','EMEDICAPP_SMTP_PASSWORD','EMEDICAPP_MAIL_FROM','EMEDICAPP_EMAIL_BASE_URL','EMEDICAPP_SMTP_STARTTLS','EMEDICAPP_SMTP_SSL')

function Resolve-MailParameters {
  param([string]$SmtpHost,[int]$Port,[string]$From,[string]$BaseUrl,[bool]$StartTls,[bool]$Ssl,[string]$Username,[string]$Password)
  $smtp=([string]$SmtpHost).Trim()
  if (-not $smtp -or $smtp -match '[\s/@#?]' -or [Uri]::CheckHostName($smtp.Trim('[',']')) -eq [UriHostNameType]::Unknown) { throw 'Ingresá un servidor SMTP válido, sin protocolo, ruta ni espacios.' }
  if ($Port -lt 1 -or $Port -gt 65535) { throw 'El puerto SMTP debe estar entre 1 y 65535.' }
  $sender=([string]$From).Trim()
  if ($sender.Length -gt 254 -or $sender -notmatch '^[^\s@]+@[^\s@]+\.[^\s@]+$') { throw 'Ingresá un correo remitente válido autorizado por tu proveedor.' }
  $link=([string]$BaseUrl).Trim()
  $uri=$null
  if (-not [Uri]::TryCreate($link,[UriKind]::Absolute,[ref]$uri) -or -not $uri.Host -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/' -or $uri.Port -lt 1 -or $uri.Port -gt 65535) { throw 'La dirección de E-MEDICAPP debe incluir sólo el origen, sin ruta, usuario, consulta ni fragmento.' }
  $loopback=$uri.Host.Trim('[',']').ToLowerInvariant() -in @('localhost','127.0.0.1','::1')
  if ($uri.Scheme -ne 'https' -and -not ($uri.Scheme -eq 'http' -and $loopback)) { throw 'Usá HTTPS para E-MEDICAPP; HTTP se permite únicamente en localhost, 127.0.0.1 o ::1 para pruebas locales.' }
  if ($StartTls -and $Ssl) { throw 'Elegí STARTTLS o SSL; no ambos al mismo tiempo.' }
  if (-not $StartTls -and -not $Ssl -and $smtp.Trim('[',']').ToLowerInvariant() -notin @('localhost','127.0.0.1','::1')) { throw 'El SMTP remoto requiere STARTTLS o SSL. El transporte sin cifrar queda limitado a pruebas locales.' }
  if ([string]::IsNullOrWhiteSpace($Username) -ne [string]::IsNullOrWhiteSpace($Password)) { throw 'El usuario SMTP y su contraseña deben estar ambos completos o ambos vacíos.' }
  return @{
    EMEDICAPP_SMTP_HOST=$smtp; EMEDICAPP_SMTP_PORT=$Port.ToString([Globalization.CultureInfo]::InvariantCulture)
    EMEDICAPP_SMTP_USERNAME=$Username; EMEDICAPP_SMTP_PASSWORD=$Password; EMEDICAPP_MAIL_FROM=$sender
    EMEDICAPP_EMAIL_BASE_URL=$link.TrimEnd('/'); EMEDICAPP_SMTP_STARTTLS=$StartTls.ToString().ToLowerInvariant(); EMEDICAPP_SMTP_SSL=$Ssl.ToString().ToLowerInvariant()
  }
}

function Read-MailParameters {
  param([string]$Path)
  $userSecret=$null; $passwordSecret=$null; $username=''; $password=''
  try {
    try {
      $data=Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
      if ($data.version -ne 1 -or $data.startTls -isnot [bool] -or $data.ssl -isnot [bool]) { throw 'Invalid mail settings.' }
      if (($data.port -isnot [int] -and $data.port -isnot [long]) -or $data.port -lt 1 -or $data.port -gt 65535) { throw 'Invalid SMTP port.' }
      if ([string]::IsNullOrWhiteSpace($data.protectedUsername) -ne [string]::IsNullOrWhiteSpace($data.protectedPassword)) { throw 'Incomplete mail credentials.' }
      if ($data.protectedUsername) {
        $userSecret=ConvertTo-SecureString $data.protectedUsername
        $passwordSecret=ConvertTo-SecureString $data.protectedPassword
        $username=[Net.NetworkCredential]::new('',$userSecret).Password
        $password=[Net.NetworkCredential]::new('',$passwordSecret).Password
        if ([string]::IsNullOrWhiteSpace($username) -or [string]::IsNullOrWhiteSpace($password)) { throw 'Empty mail credentials.' }
      }
    } catch { throw 'No se pudo leer la configuración de correo protegida. Ejecutá ConfigurarCorreo.cmd desde el mismo usuario de Windows que inicia E-MEDICAPP.' }
    return Resolve-MailParameters -SmtpHost $data.host -Port $data.port -From $data.from -BaseUrl $data.baseUrl -StartTls $data.startTls -Ssl $data.ssl -Username $username -Password $password
  } finally {
    if ($userSecret) { $userSecret.Dispose() }
    if ($passwordSecret) { $passwordSecret.Dispose() }
    $username=$null; $password=$null
  }
}
