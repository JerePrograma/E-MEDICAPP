param([string]$JavaHome=$env:EMEDICAPP_JAVA_HOME)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $JavaHome) { $JavaHome='C:\Program Files\Java\jdk-25.0.4.1+1' }
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))) { throw 'Indicá EMEDICAPP_JAVA_HOME con un JDK 25.' }
$version=& (Join-Path $JavaHome 'bin\java.exe') --version 2>&1 | Out-String
if ($version -notmatch '(?m)^(openjdk|java) 25(\.| )') { throw 'El proyecto requiere Java 25 LTS.' }
$savedJavaHome=$env:JAVA_HOME
$env:JAVA_HOME=$JavaHome
$npmCommand=(Get-Command npm.cmd -ErrorAction Stop).Source
Push-Location $projectRoot
try {
  & '.\backend\mvnw.cmd' -B -ntp -f backend\pom.xml -Pfrontend "-Dnpm.executable=$npmCommand" package
  if ($LASTEXITCODE) { throw 'La compilación o sus pruebas fallaron.' }
} finally { Pop-Location; $env:JAVA_HOME=$savedJavaHome }
Write-Output 'Aplicación compilada con frontend y backend en un único JAR.'
