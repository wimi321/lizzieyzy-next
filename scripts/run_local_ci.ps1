param(
  [ValidateSet('Windows', 'Portable', 'All')]
  [string]$Profile = 'All',
  [switch]$DryRun,
  [switch]$RequireClean,
  [string]$SummaryDir = 'target/local-ci'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

function Test-Java21([string]$JavaHome) {
  if (-not $JavaHome) { return $false }
  $java = Join-Path $JavaHome 'bin\java.exe'
  if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { return $false }
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

if (-not (Test-Java21 $env:JAVA_HOME)) {
  $jdkCandidates = @(
    Get-ChildItem -Path (Join-Path $repoRoot '.tools\jdk-21*') -Directory -ErrorAction SilentlyContinue
    Get-ChildItem -Path (Join-Path $env:SystemDrive 'jdk21\jdk-21*') -Directory -ErrorAction SilentlyContinue
    Get-ChildItem -Path "$env:ProgramFiles\Eclipse Adoptium\jdk-21*" -Directory -ErrorAction SilentlyContinue
    Get-ChildItem -Path "$env:ProgramFiles\Java\jdk-21*" -Directory -ErrorAction SilentlyContinue
    Get-ChildItem -Path "$env:ProgramFiles\Microsoft\jdk-21*" -Directory -ErrorAction SilentlyContinue
    Get-ChildItem -Path "$env:ProgramFiles\Amazon Corretto\jdk21*" -Directory -ErrorAction SilentlyContinue
  )
  $jdk = $jdkCandidates | Where-Object { Test-Java21 $_.FullName } | Sort-Object Name | Select-Object -Last 1
  if ($jdk) {
    $env:JAVA_HOME = $jdk.FullName
    $env:Path = "$(Join-Path $jdk.FullName 'bin');$env:Path"
  }
}

$python = $env:LIZZIE_PYTHON
if (-not $python) {
  $pythonCommand = Get-Command python3, python -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($pythonCommand) { $python = $pythonCommand.Source }
}
if (-not $python) {
  throw 'Python 3 was not found. Set LIZZIE_PYTHON or add python to PATH.'
}

if (-not $env:LIZZIE_MAVEN) {
  $maven = Get-Command mvn.cmd, mvn -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $maven) {
    $maven = Get-ChildItem -Path (Join-Path $repoRoot '.tools\apache-maven-*\bin\mvn.cmd'), 'C:\tools\apache-maven-*\bin\mvn.cmd' -File -ErrorAction SilentlyContinue | Sort-Object FullName | Select-Object -Last 1
  }
  if ($maven) { $env:LIZZIE_MAVEN = $maven.FullName }
}

if (-not $env:LIZZIE_BASH) {
  $gitBash = Join-Path $env:ProgramFiles 'Git\bin\bash.exe'
  if (Test-Path -LiteralPath $gitBash -PathType Leaf) {
    $env:LIZZIE_BASH = $gitBash
  } else {
    $bash = Get-Command bash.exe, bash -ErrorAction SilentlyContinue |
      Where-Object { $_.Source -notmatch '\\Windows\\(?:System32|Sysnative)\\bash\.exe$' } |
      Select-Object -First 1
  }
  if (-not $env:LIZZIE_BASH -and $bash) {
    $env:LIZZIE_BASH = $bash.Source
  }
}

$arguments = @(
  (Join-Path $PSScriptRoot 'run_local_ci.py'),
  '--profile', $Profile.ToLowerInvariant(),
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
