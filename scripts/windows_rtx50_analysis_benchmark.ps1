param(
    [Parameter(Mandatory = $true)]
    [string]$KataGoExe,

    [Parameter(Mandatory = $true)]
    [string]$Model,

    [Parameter(Mandatory = $true)]
    [string]$Config,

    [string]$OutputDirectory = "analysis-benchmark",

    [ValidateRange(1, 20)]
    [int]$Runs = 3,

    [ValidateRange(1, 600)]
    [int]$SecondsPerMove = 30,

    [ValidateRange(1, 1000000)]
    [int]$VisitsPerPosition = 5000,

    [ValidateRange(30, 7200)]
    [int]$TimeoutSeconds = 1800
)

$ErrorActionPreference = "Stop"

function Resolve-RequiredPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    $resolved = Resolve-Path -LiteralPath $Path -ErrorAction SilentlyContinue
    if (-not $resolved) {
        throw "$Label not found: $Path"
    }
    return $resolved.Path
}

function Save-NvidiaSnapshot {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Destination,

        [Parameter(Mandatory = $true)]
        [string]$Phase
    )

    $timestamp = (Get-Date).ToUniversalTime().ToString("o")
    $gpu = & nvidia-smi --query-gpu=name,driver_version,pstate,power.draw,power.limit,memory.used,memory.total,utilization.gpu,utilization.memory --format=csv,noheader,nounits 2>&1
    $processes = & nvidia-smi --query-compute-apps=pid,process_name,used_memory --format=csv,noheader,nounits 2>&1
    [pscustomobject]@{
        timestamp = $timestamp
        phase = $Phase
        gpu = (($gpu | ForEach-Object { $_.ToString() }) -join " | ")
        processes = (($processes | ForEach-Object { $_.ToString() }) -join " | ")
    } | ConvertTo-Json -Compress | Add-Content -LiteralPath $Destination -Encoding utf8
}

function Quote-NativeArgument {
    param([string]$Value)
    return '"' + ($Value -replace '"', '\"') + '"'
}

function Invoke-BenchmarkRun {
    param(
        [Parameter(Mandatory = $true)]
        [int]$RunNumber,

        [Parameter(Mandatory = $true)]
        [string]$RunDirectory
    )

    New-Item -ItemType Directory -Force -Path $RunDirectory | Out-Null
    $stdout = Join-Path $RunDirectory "katago.stdout.log"
    $stderr = Join-Path $RunDirectory "katago.stderr.log"
    $telemetry = Join-Path $RunDirectory "nvidia-smi.jsonl"
    $engineHome = Join-Path $OutputDirectory "katago-home"
    New-Item -ItemType Directory -Force -Path $engineHome | Out-Null

    $arguments = @(
        "benchmark",
        "-config", (Quote-NativeArgument $script:ResolvedConfig),
        "-model", (Quote-NativeArgument $script:ResolvedModel),
        "-v", $VisitsPerPosition.ToString(),
        "-time", $SecondsPerMove.ToString(),
        "-override-config", (Quote-NativeArgument "homeDataDir=$engineHome,logToStderr=false,logAllGTPCommunication=false")
    )

    Save-NvidiaSnapshot -Destination $telemetry -Phase "before"
    $process = Start-Process `
        -FilePath $script:ResolvedKataGo `
        -ArgumentList $arguments `
        -WorkingDirectory (Split-Path -Parent $script:ResolvedKataGo) `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru

    $elapsed = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        while (-not $process.HasExited) {
            if ($elapsed.Elapsed.TotalSeconds -gt $TimeoutSeconds) {
                throw "Benchmark run $RunNumber exceeded $TimeoutSeconds seconds."
            }
            Save-NvidiaSnapshot -Destination $telemetry -Phase "running"
            Start-Sleep -Seconds 1
            $process.Refresh()
        }
        $process.WaitForExit()
    } finally {
        if (-not $process.HasExited) {
            $process.Kill()
            $process.WaitForExit(10000) | Out-Null
        }
        [pscustomobject]@{
            run = $RunNumber
            phase = $(if ($RunNumber -eq 0) { "cold-cache" } else { "warm-cache-new-process" })
            seconds = $elapsed.Elapsed.TotalSeconds
            arguments = $arguments
            exitCode = $process.ExitCode
        } | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $RunDirectory "run.json") -Encoding utf8
    }
    Save-NvidiaSnapshot -Destination $telemetry -Phase "after"

    if ($process.ExitCode -ne 0) {
        throw "KataGo benchmark run $RunNumber exited with code $($process.ExitCode). See $stderr"
    }
    $combined = ((Get-Content -LiteralPath $stdout -Raw -ErrorAction SilentlyContinue) + "`n" +
        (Get-Content -LiteralPath $stderr -Raw -ErrorAction SilentlyContinue))
    if ($combined -notmatch "KataGo v|numSearchThreads|nnEvals") {
        throw "Benchmark run $RunNumber did not produce expected KataGo metrics."
    }
}

$ResolvedKataGo = Resolve-RequiredPath -Path $KataGoExe -Label "KataGo executable"
$ResolvedModel = Resolve-RequiredPath -Path $Model -Label "KataGo model"
$ResolvedConfig = Resolve-RequiredPath -Path $Config -Label "KataGo config"

if (-not (Get-Command nvidia-smi -ErrorAction SilentlyContinue)) {
    throw "nvidia-smi was not found. Install or repair the NVIDIA driver first."
}

$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $OutputDirectory) {
    throw "Output directory already exists; choose a fresh directory to preserve evidence and cold-cache semantics."
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

$system = [pscustomobject]@{
    timestamp = (Get-Date).ToUniversalTime().ToString("o")
    os = [System.Environment]::OSVersion.VersionString
    powershell = $PSVersionTable.PSVersion.ToString()
    katago = (Split-Path -Leaf $ResolvedKataGo)
    engineSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $ResolvedKataGo).Hash.ToLowerInvariant()
    model = (Split-Path -Leaf $ResolvedModel)
    config = (Split-Path -Leaf $ResolvedConfig)
    modelSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $ResolvedModel).Hash.ToLowerInvariant()
    configSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $ResolvedConfig).Hash.ToLowerInvariant()
}
$system | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $OutputDirectory "system.json") -Encoding utf8

Invoke-BenchmarkRun -RunNumber 0 -RunDirectory (Join-Path $OutputDirectory "cold")
for ($run = 1; $run -le $Runs; $run++) {
    Write-Host "Running fixed KataGo benchmark $run/$Runs..."
    Invoke-BenchmarkRun -RunNumber $run -RunDirectory (Join-Path $OutputDirectory ("run-{0:D2}" -f $run))
}

Write-Host "Benchmark complete: $OutputDirectory"
Write-Host "Keep this directory together with LizzieYzy Next analysis-resource-diagnostics.jsonl."
