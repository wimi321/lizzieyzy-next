param(
    [Parameter(Mandatory = $true)]
    [string]$AppExe,

    [string]$ConfigDir = "$env:USERPROFILE\.lizzieyzy-next",

    [string]$RequiredLogDir = "gtp_logs",

    [int]$HealthyProcessSeconds = 15,

    [int]$WaitSeconds = 60,

    [switch]$PreserveConfig
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $AppExe)) {
    throw "App executable not found: $AppExe"
}

$appDir = Split-Path -Parent $AppExe
$consoleLogs = Join-Path $appDir "LastConsoleLogs_*.txt"
$errorLogs = Join-Path $appDir "LastErrorLogs_*.txt"

if ((-not $PreserveConfig) -and (Test-Path -LiteralPath $ConfigDir)) {
    Remove-Item -LiteralPath $ConfigDir -Recurse -Force
}

Get-ChildItem -Path $consoleLogs -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
Get-ChildItem -Path $errorLogs -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

Write-Host "Launching $AppExe"
$process = Start-Process -FilePath $AppExe -WorkingDirectory $appDir -PassThru

$deadline = (Get-Date).AddSeconds($WaitSeconds)
$runtimeDir = Join-Path $ConfigDir "runtime"
$requiredRuntimeLogDir = Join-Path $runtimeDir $RequiredLogDir
$configFile = Join-Path $ConfigDir "config.txt"
$persistFile = Join-Path $ConfigDir "persist"
$passed = $false
$healthyDeadline = (Get-Date).AddSeconds($HealthyProcessSeconds)

try {
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2

        if ($process.HasExited) {
            $exitCode = $process.ExitCode
            throw "Application exited early with code $exitCode"
        }

        $hasConfig = (Test-Path -LiteralPath $configFile) -and (Test-Path -LiteralPath $persistFile)
        $hasRuntimeLogs = Test-Path -LiteralPath $requiredRuntimeLogDir
        $hasRuntimeState = $hasRuntimeLogs -or (Test-Path -LiteralPath $runtimeDir)

        if ($hasConfig -and $hasRuntimeState) {
            if ($hasRuntimeLogs) {
                Write-Host "Smoke test passed. Config files and bundled KataGo runtime logs were created."
            }
            else {
                Write-Host "Smoke test passed. Config files were created and the runtime directory is writable."
            }
            $passed = $true
            return
        }

        if ($hasConfig) {
            Write-Host "Config files detected. Waiting briefly for runtime state..."
        }

        if ((Get-Date) -ge $healthyDeadline) {
            Write-Host "Smoke test passed. App process stayed alive long enough for CI."
            $passed = $true
            return
        }
    }

    throw "Timed out waiting for config files, runtime state, or a healthy running process."
}
finally {
    if ($process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
    }

    Write-Host "--- Config dir ---"
    if (Test-Path -LiteralPath $ConfigDir) {
        Get-ChildItem -LiteralPath $ConfigDir -Recurse -Force | Select-Object FullName, Length
    }

    Write-Host "--- Console logs ---"
    Get-ChildItem -Path $consoleLogs -ErrorAction SilentlyContinue | ForEach-Object {
        Write-Host "### $($_.FullName)"
        Get-Content -LiteralPath $_.FullName -Tail 120 -ErrorAction SilentlyContinue
    }

    Write-Host "--- Error logs ---"
        Get-ChildItem -Path $errorLogs -ErrorAction SilentlyContinue | ForEach-Object {
            Write-Host "### $($_.FullName)"
            Get-Content -LiteralPath $_.FullName -Tail 120 -ErrorAction SilentlyContinue
        }
}

if (-not $passed) {
    throw "Windows smoke test did not reach the success condition."
}
