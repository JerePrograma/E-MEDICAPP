# Shared, non-invasive discovery. Overrides remain available for portable installs.
$toolsProjectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

function Suspend-EMedicappExternalConfiguration {
  # Other projects may define global Spring/JVM overrides. Local launchers accept
  # EMEDICAPP_* settings and must not inherit another application's port or DB.
  # This changes only the current process, never Windows user/machine settings.
  $snapshot=@{}
  $jvmKeys=@('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','JAVA_OPTS','MAVEN_OPTS','MAVEN_ARGS')
  $processEnvironment=[Environment]::GetEnvironmentVariables('Process')
  foreach ($key in @($processEnvironment.Keys)) {
    if ([string]$key -match '^(SPRING[_.]|SERVER[_.])' -or $jvmKeys -contains [string]$key) {
      $snapshot[[string]$key]=[string]$processEnvironment[$key]
      [Environment]::SetEnvironmentVariable([string]$key,$null,'Process')
    }
  }
  return $snapshot
}

function Restore-EMedicappExternalConfiguration([hashtable]$Snapshot) {
  if (-not $Snapshot) { return }
  foreach ($key in $Snapshot.Keys) { [Environment]::SetEnvironmentVariable($key,$Snapshot[$key],'Process') }
}

function Resolve-EMedicappJavaHome([string]$Preferred=$env:EMEDICAPP_JAVA_HOME) {
  $candidates=@()
  if ($Preferred) { $candidates=@($Preferred) }
  else {
    if ($env:JAVA_HOME) { $candidates+=$env:JAVA_HOME }
    $javaCommand=Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) { $candidates+=Split-Path (Split-Path $javaCommand.Source -Parent) -Parent }
    foreach ($root in @((Join-Path $toolsProjectRoot '.tools'),(Join-Path $env:ProgramFiles 'Java'),(Join-Path $env:ProgramFiles 'Eclipse Adoptium'),(Join-Path $env:ProgramFiles 'Microsoft'))) {
      if (Test-Path -LiteralPath $root) {
        $candidates+=@(Get-ChildItem -LiteralPath $root -Filter javac.exe -File -Recurse -ErrorAction SilentlyContinue | ForEach-Object { Split-Path (Split-Path $_.FullName -Parent) -Parent })
      }
    }
  }
  foreach ($candidate in @($candidates | Select-Object -Unique)) {
    $javaPath=Join-Path $candidate 'bin\java.exe'
    if ((Test-Path -LiteralPath $javaPath) -and (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe'))) {
      $version=& $javaPath --version 2>&1 | Out-String
      if ($LASTEXITCODE -eq 0 -and $version -match '(?m)^(openjdk|java) 25(\.| )') { return [IO.Path]::GetFullPath($candidate) }
    }
  }
  throw 'No se encontro un JDK 25. Instalalo o indica EMEDICAPP_JAVA_HOME (tambien se detecta .tools\java\<jdk>).'
}

function Resolve-EMedicappPostgresBin([string]$Preferred=$env:EMEDICAPP_PG_BIN) {
  $clusterVersionPath=Join-Path $toolsProjectRoot '.runtime\postgres\PG_VERSION'
  $requiredMajor=if (Test-Path -LiteralPath $clusterVersionPath) { (Get-Content -LiteralPath $clusterVersionPath -Raw).Trim() } else { $null }
  $candidates=@()
  if ($Preferred) { $candidates=@($Preferred) }
  else {
    $pgCommand=Get-Command pg_ctl.exe -ErrorAction SilentlyContinue
    if ($pgCommand) { $candidates+=Split-Path $pgCommand.Source -Parent }
    foreach ($root in @((Join-Path $env:ProgramFiles 'PostgreSQL'),(Join-Path $toolsProjectRoot '.tools\postgres'))) {
      if (Test-Path -LiteralPath $root) {
        $candidates+=@(Get-ChildItem -LiteralPath $root -Directory | Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'bin' })
        $candidates+=Join-Path $root 'bin'
      }
    }
  }
  foreach ($candidate in @($candidates | Select-Object -Unique)) {
    $pgctl=Join-Path $candidate 'pg_ctl.exe'
    if (-not (Test-Path -LiteralPath $pgctl)) { continue }
    $version=& $pgctl --version 2>&1 | Out-String
    if ($LASTEXITCODE -eq 0 -and $version -match 'PostgreSQL\) (\d+)\.') {
      $major=[int]$Matches[1]
      if ($major -ge 17 -and (-not $requiredMajor -or $major -eq [int]$requiredMajor)) {
        foreach ($binary in @('psql.exe','initdb.exe','pg_dump.exe','pg_restore.exe')) {
          if (-not (Test-Path -LiteralPath (Join-Path $candidate $binary))) { throw "La instalacion PostgreSQL esta incompleta: falta $binary." }
        }
        return [IO.Path]::GetFullPath($candidate)
      }
    }
  }
  $detail=if ($requiredMajor) { " de la version $requiredMajor para conservar el cluster existente" } else { ' 17 o superior' }
  throw "No se encontro PostgreSQL$detail. Instala los binarios o indica EMEDICAPP_PG_BIN."
}

function Resolve-EMedicappNodeHome([string]$Preferred=$env:EMEDICAPP_NODE_HOME) {
  $candidates=@()
  if ($Preferred) { $candidates=@($Preferred) }
  else {
    $nodeCommand=Get-Command node.exe -ErrorAction SilentlyContinue
    if ($nodeCommand) { $candidates+=Split-Path $nodeCommand.Source -Parent }
    $portableRoot=Join-Path $toolsProjectRoot '.tools\node'
    if (Test-Path -LiteralPath $portableRoot) {
      $candidates+=$portableRoot
      $candidates+=@(Get-ChildItem -LiteralPath $portableRoot -Directory | ForEach-Object { $_.FullName })
    }
  }
  foreach ($candidate in @($candidates | Select-Object -Unique)) {
    $nodePath=Join-Path $candidate 'node.exe'
    if ((Test-Path -LiteralPath $nodePath) -and (Test-Path -LiteralPath (Join-Path $candidate 'npm.cmd'))) {
      $version=& $nodePath --version 2>&1 | Out-String
      if ($LASTEXITCODE -eq 0 -and $version.Trim() -match '^v24\.') { return [IO.Path]::GetFullPath($candidate) }
    }
  }
  throw 'No se encontro Node.js 24 con npm. Instalalo o indica EMEDICAPP_NODE_HOME.'
}
