param([string]$JavaHome=$env:EMEDICAPP_JAVA_HOME)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'Tools.Common.ps1')
$isolatedConfiguration=Suspend-EMedicappExternalConfiguration
try {
$JavaHome=Resolve-EMedicappJavaHome $JavaHome
$nodeHome=Resolve-EMedicappNodeHome
$savedJavaHome=$env:JAVA_HOME
$savedPath=$env:PATH
$savedBase=$env:VITE_BASE_PATH
$env:JAVA_HOME=$JavaHome
$env:PATH=$nodeHome+';'+$env:PATH
$env:VITE_BASE_PATH='/'
$npmCommand=Join-Path $nodeHome 'npm.cmd'
Push-Location $projectRoot
try {
  & '.\backend\mvnw.cmd' -B -ntp -f backend\pom.xml -Pfrontend "-Dnpm.executable=$npmCommand" package
  if ($LASTEXITCODE) { throw 'La compilación o sus pruebas fallaron.' }
} finally { Pop-Location; $env:JAVA_HOME=$savedJavaHome; $env:PATH=$savedPath; $env:VITE_BASE_PATH=$savedBase }
Write-Output 'Aplicación compilada con frontend y backend en un único JAR.'
} finally { Restore-EMedicappExternalConfiguration $isolatedConfiguration }
