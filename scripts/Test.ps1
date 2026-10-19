param([string]$JavaHome=$env:EMEDICAPP_JAVA_HOME)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Test-QA.ps1') -JavaHome $JavaHome
