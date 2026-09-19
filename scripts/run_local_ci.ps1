param(
  [ValidateSet('Windows', 'Portable', 'All')]
  [string]$Profile = 'All',
  [ValidateSet('All', 'Repository', 'Scripts', 'Java', 'Desktop', 'EngineProcess', 'TensorRtUi')]
  [string]$Group = 'All',
  [switch]$DryRun,
  [switch]$RequireClean,
  [string]$SummaryDir = 'target/local-ci'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

function Test-Java21([string]$JavaHome, [bool]$RequireJpackage = $false) {
  if (-not $JavaHome) { return $false }
  $java = Join-Path $JavaHome 'bin\java.exe'
  $jpackage = Join-Path $JavaHome 'bin\jpackage.exe'
  if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { return $false }
  if ($RequireJpackage -and -not (Test-Path -LiteralPath $jpackage -PathType Leaf)) { return $false }
  $previousPreference = $ErrorActionPreference
  try {
    # java -version intentionally writes to stderr. Windows PowerShell wraps
    # that successful output in NativeCommandError when the global mode is Stop.
    $ErrorActionPreference = 'Continue'
    $version = & $java -version 2>&1 | Out-String
  } finally {
    $ErrorActionPreference = $previousPreference
  }
  return $version -match 'version "21(?:\.|\")'
}

$requiresJpackage = $Group -eq 'TensorRtUi'
if ($Group -in @('All', 'Java', 'Desktop', 'EngineProcess', 'TensorRtUi') -and -not $DryRun -and -not (Test-Java21 $env:JAVA_HOME $requiresJpackage)) {
  $jdkCandidates = @(
    Get-ChildItem -Path (Join-Path $repoRoot '.tools\jdk-21*') -Directory -ErrorAction SilentlyContinue
    Get-ChildItem -Path (Join-Path $env:SystemDrive 'jdk21\jdk-21*') -Directory -ErrorAction SilentlyContinue
    Get-ChildItem -Path "$env:ProgramFiles\Eclipse Adoptium\jdk-21*" -Directory -ErrorAction SilentlyContinue
    Get-ChildItem -Path "$env:ProgramFiles\Java\jdk-21*" -Directory -ErrorAction SilentlyContinue
    Get-ChildItem -Path "$env:ProgramFiles\Microsoft\jdk-21*" -Directory -ErrorAction SilentlyContinue
    Get-ChildItem -Path "$env:ProgramFiles\Amazon Corretto\jdk21*" -Directory -ErrorAction SilentlyContinue
  )
  $jdk = $jdkCandidates | Where-Object { Test-Java21 $_.FullName $requiresJpackage } | Sort-Object Name | Select-Object -Last 1
  if ($jdk) {
    $env:JAVA_HOME = $jdk.FullName
    $env:Path = "$(Join-Path $jdk.FullName 'bin');$env:Path"
  }
}

$python = $env:LIZZIE_PYTHON
if (-not $python) {
  $pythonCommand = Get-Command py, python3, python -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($pythonCommand) { $python = $pythonCommand.Source }
}
if (-not $python) {
  throw 'Python 3 was not found. Set LIZZIE_PYTHON or add python to PATH.'
}

$runnerGroup = if ($Group -eq 'TensorRtUi') { 'tensorrt-ui' } elseif ($Group -eq 'EngineProcess') { 'engine-process' } else { $Group.ToLowerInvariant() }

$arguments = @(
  (Join-Path $PSScriptRoot 'run_local_ci.py'),
  '--profile', $Profile.ToLowerInvariant(),
  '--group', $runnerGroup,
  '--summary-dir', $SummaryDir
)
if ($DryRun) { $arguments += '--dry-run' }
if ($RequireClean) { $arguments += '--require-clean' }

Push-Location $repoRoot
try {
  & $python @arguments
  exit $LASTEXITCODE
} finally {
  Pop-Location
}
