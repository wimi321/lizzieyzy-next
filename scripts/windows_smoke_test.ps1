param(
    [Parameter(Mandatory = $true)]
    [string]$AppExe,

    [string]$ConfigDir = "",

    [string]$RequiredLogDir = "gtp_logs",

    [int]$HealthyProcessSeconds = 15,

    [int]$WaitSeconds = 60,

    [switch]$PreserveConfig,

    [switch]$RequireConfig,

    [switch]$ProbeBoardSync
)

$ErrorActionPreference = "Stop"

function Get-SmokeConfigDirCandidates {
    param(
        [string]$PreferredConfigDir
    )

    $candidates = New-Object System.Collections.Generic.List[string]
    if ($PreferredConfigDir -and $PreferredConfigDir.Trim()) {
        $candidates.Add($PreferredConfigDir)
    }
    if ($env:PUBLIC) {
        $candidates.Add((Join-Path $env:PUBLIC "Documents\LizzieYzyNext"))
        $candidates.Add((Join-Path $env:PUBLIC "LizzieYzyNext"))
    }
    if ($env:PROGRAMDATA) {
        $candidates.Add((Join-Path $env:PROGRAMDATA "LizzieYzyNext"))
    }
    if ($env:USERPROFILE) {
        $candidates.Add((Join-Path $env:USERPROFILE ".lizzieyzy-next"))
        $candidates.Add((Join-Path $env:USERPROFILE ".lizzieyzy-next-foxuid"))
    }

    return $candidates | Where-Object { $_ -and $_.Trim() } | Select-Object -Unique
}

function Resolve-ExistingSmokeConfigDir {
    param(
        [string[]]$Candidates
    )

    foreach ($candidate in $Candidates) {
        if ((Test-Path -LiteralPath (Join-Path $candidate "config.txt")) `
            -or (Test-Path -LiteralPath (Join-Path $candidate "persist")) `
            -or (Test-Path -LiteralPath (Join-Path $candidate "runtime")) `
            -or (Test-Path -LiteralPath (Join-Path $candidate "save"))) {
            return $candidate
        }
    }

    if ($Candidates.Count -gt 0) {
        return $Candidates[0]
    }
    return ""
}

function Assert-NoBundledEngineStartupFailure {
    param(
        [string]$RuntimeLogDir
    )

    if (-not $RuntimeLogDir -or -not (Test-Path -LiteralPath $RuntimeLogDir)) {
        return
    }

    $patterns = @(
        'Got nonfinite for policy sum',
        'Error creating directory',
        'Could not create file',
        'Uncaught exception'
    )

    $logFiles = Get-ChildItem -LiteralPath $RuntimeLogDir -Filter *.log -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending
    foreach ($logFile in $logFiles) {
        $content = Get-Content -LiteralPath $logFile.FullName -Raw -ErrorAction SilentlyContinue
        if (-not $content) {
            continue
        }
        foreach ($pattern in $patterns) {
            if ($content -match $pattern) {
                throw "Bundled KataGo startup log contains '$pattern': $($logFile.FullName)"
            }
        }
    }
}

function Get-BundledKataGoProbeAssets {
    param(
        [Parameter(Mandatory = $true)]
        [string]$AppExe
    )

    $appDir = Split-Path -Parent $AppExe
    $appRoot = Join-Path $appDir "app"
    if (-not (Test-Path -LiteralPath $appRoot)) {
        return $null
    }

    $engineRoot = Join-Path $appRoot "engines\katago"
    $weightPath = Join-Path $appRoot "weights\default.bin.gz"
    $configPath = Join-Path $engineRoot "configs\gtp.cfg"

    if (-not (Test-Path -LiteralPath $engineRoot) -or `
        -not (Test-Path -LiteralPath $weightPath) -or `
        -not (Test-Path -LiteralPath $configPath)) {
        return $null
    }

    $engineCandidates = Get-ChildItem -LiteralPath $engineRoot -Filter "katago.exe" -File -Recurse -ErrorAction SilentlyContinue |
        Sort-Object FullName
    if (-not $engineCandidates) {
        return $null
    }

    $engine = $engineCandidates |
        Where-Object { $_.FullName -notmatch '[\\/]windows-x64-nvidia[\\/]' } |
        Select-Object -First 1
    if (-not $engine) {
        $engine = $engineCandidates | Select-Object -First 1
    }

    return [pscustomobject]@{
        EnginePath = $engine.FullName
        EngineDir = $engine.Directory.FullName
        WeightPath = $weightPath
        ConfigPath = $configPath
    }
}

function Invoke-BundledKataGoBenchmarkProbe {
    param(
        [Parameter(Mandatory = $true)]
        [string]$AppExe,

        [string]$ConfigDir = ""
    )

    $assets = Get-BundledKataGoProbeAssets -AppExe $AppExe
    if (-not $assets) {
        throw "Bundled KataGo probe assets were not found under $AppExe"
    }

    $probeHome = if ($ConfigDir -and $ConfigDir.Trim()) {
        Join-Path $ConfigDir "runtime\katago-home-smoke"
    }
    elseif ($env:TEMP) {
        Join-Path $env:TEMP "LizzieYzyNext\katago-home-smoke"
    }
    else {
        Join-Path $assets.EngineDir "katago-home-smoke"
    }

    New-Item -ItemType Directory -Force -Path $probeHome | Out-Null

    $arguments = @(
        "benchmark",
        "-config", $assets.ConfigPath,
        "-model", $assets.WeightPath,
        "-n", "1",
        "-v", "32",
        "-time", "1",
        "-override-config", "homeDataDir=$probeHome,logToStderr=false,logAllGTPCommunication=false,logSearchInfo=false"
    )

    $originalPath = $env:PATH
    try {
        $env:PATH = "$($assets.EngineDir);$originalPath"
        Push-Location $assets.EngineDir
        Write-Host "Running bundled KataGo benchmark probe: $($assets.EnginePath)"
        $output = & $assets.EnginePath @arguments 2>&1
        $exitCode = $LASTEXITCODE
        $joined = (($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine)
        if ($joined) {
            Write-Host $joined
        }

        $patterns = @(
            'Got nonfinite for policy sum',
            'Error creating directory',
            'Could not create file',
            'Uncaught exception'
        )
        foreach ($pattern in $patterns) {
            if ($joined -match [regex]::Escape($pattern)) {
                throw "Bundled KataGo benchmark probe output contains '$pattern'"
            }
        }

        if ($exitCode -ne 0) {
            throw "Bundled KataGo benchmark probe exited with code $exitCode"
        }

        if (-not $joined -or $joined -notmatch 'numSearchThreads|KataGo v') {
            throw "Bundled KataGo benchmark probe did not produce the expected benchmark output."
        }
    }
    finally {
        try {
            Pop-Location
        }
        catch {
        }
        $env:PATH = $originalPath
    }
}

function Get-NativeReadBoardAssets {
    param(
        [Parameter(Mandatory = $true)]
        [string]$AppExe
    )

    $appDir = Split-Path -Parent $AppExe
    $readBoardDir = Join-Path (Join-Path $appDir "app") "readboard"
    $readBoardExe = Join-Path $readBoardDir "readboard.exe"
    if (-not (Test-Path -LiteralPath $readBoardExe)) {
        throw "Native readboard.exe was not found in the packaged app layout: $readBoardExe"
    }

    return [pscustomobject]@{
        Directory = $readBoardDir
        ExePath = $readBoardExe
    }
}

function Stop-NativeReadBoardProcesses {
    param(
        [string]$ReadBoardExePath = ""
    )

    Get-CimInstance Win32_Process -Filter "Name = 'readboard.exe'" -ErrorAction SilentlyContinue |
        Where-Object {
            (-not $ReadBoardExePath) -or
            ($_.ExecutablePath -and ($_.ExecutablePath -ieq $ReadBoardExePath)) -or
            ($_.CommandLine -and ($_.CommandLine -like "*$ReadBoardExePath*"))
        } |
        ForEach-Object {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
}

function Invoke-NativeReadBoardPipeProbe {
    param(
        [Parameter(Mandatory = $true)]
        [string]$AppExe
    )

    $assets = Get-NativeReadBoardAssets -AppExe $AppExe
    Stop-NativeReadBoardProcesses -ReadBoardExePath $assets.ExePath

    $stdoutPath = Join-Path $assets.Directory "lizzieyzy-next-readboard-probe.stdout.txt"
    $stderrPath = Join-Path $assets.Directory "lizzieyzy-next-readboard-probe.stderr.txt"
    Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue

    $process = $null
    try {
        $psi = [System.Diagnostics.ProcessStartInfo]::new()
        $psi.FileName = $assets.ExePath
        $psi.WorkingDirectory = $assets.Directory
        $psi.UseShellExecute = $false
        $psi.RedirectStandardInput = $true
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.CreateNoWindow = $false
        $psi.Arguments = 'yzy " " " " " " 0 en -1'

        Write-Host "Probing native readboard pipe launch: $($assets.ExePath)"
        $process = [System.Diagnostics.Process]::Start($psi)
        Start-Sleep -Seconds 5

        if ($process.HasExited) {
            $stdout = $process.StandardOutput.ReadToEnd()
            $stderr = $process.StandardError.ReadToEnd()
            [System.IO.File]::WriteAllText($stdoutPath, $stdout)
            [System.IO.File]::WriteAllText($stderrPath, $stderr)
            throw "Native readboard exited during pipe launch probe with code $($process.ExitCode). Stdout: $stdout Stderr: $stderr"
        }

        Write-Host "Native readboard pipe probe passed. Process id: $($process.Id)"
    }
    finally {
        if ($process -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
        Stop-NativeReadBoardProcesses -ReadBoardExePath $assets.ExePath
    }
}

function Invoke-BoardSyncHotkeyProbe {
    param(
        [Parameter(Mandatory = $true)]
        [System.Diagnostics.Process]$AppProcess,

        [Parameter(Mandatory = $true)]
        [string]$AppExe,

        [int]$TimeoutSeconds = 25
    )

    $assets = Get-NativeReadBoardAssets -AppExe $AppExe
    Stop-NativeReadBoardProcesses -ReadBoardExePath $assets.ExePath

    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class LizzieYzySmokeWin32 {
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")]
    public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
}
"@

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $windowHandle = [IntPtr]::Zero
    while ((Get-Date) -lt $deadline) {
        $AppProcess.Refresh()
        if ($AppProcess.HasExited) {
            throw "Application exited before board sync hotkey probe could run."
        }
        if ($AppProcess.MainWindowHandle -ne [IntPtr]::Zero) {
            $windowHandle = $AppProcess.MainWindowHandle
            break
        }
        Start-Sleep -Milliseconds 500
    }

    if ($windowHandle -eq [IntPtr]::Zero) {
        throw "Application main window handle was not available for board sync hotkey probe."
    }

    [void][LizzieYzySmokeWin32]::ShowWindow($windowHandle, 9)
    [void][LizzieYzySmokeWin32]::SetForegroundWindow($windowHandle)
    Start-Sleep -Seconds 1

    Write-Host "Sending Alt+O to trigger native board sync."
    [System.Windows.Forms.SendKeys]::SendWait("%o")

    $probeDeadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $probeDeadline) {
        Start-Sleep -Milliseconds 500
        $match = Get-CimInstance Win32_Process -Filter "Name = 'readboard.exe'" -ErrorAction SilentlyContinue |
            Where-Object {
                ($_.ExecutablePath -and ($_.ExecutablePath -ieq $assets.ExePath)) -or
                ($_.CommandLine -and ($_.CommandLine -like "*$($assets.ExePath)*")) -or
                ($_.ExecutablePath -and ($_.ExecutablePath -like "*\app\readboard\readboard.exe"))
            } |
            Select-Object -First 1
        if ($match) {
            Write-Host "Board sync hotkey probe passed. readboard.exe process id: $($match.ProcessId)"
            Stop-NativeReadBoardProcesses -ReadBoardExePath $assets.ExePath
            return
        }
    }

    throw "Alt+O did not start packaged native readboard.exe within $TimeoutSeconds seconds."
}

if (-not (Test-Path -LiteralPath $AppExe)) {
    throw "App executable not found: $AppExe"
}

$appDir = Split-Path -Parent $AppExe
$consoleLogs = Join-Path $appDir "LastConsoleLogs_*.txt"
$errorLogs = Join-Path $appDir "LastErrorLogs_*.txt"
$configDirCandidates = @(Get-SmokeConfigDirCandidates -PreferredConfigDir $ConfigDir)

if ($configDirCandidates.Count -eq 0) {
    throw "No candidate config directory could be resolved for the Windows smoke test."
}

if (-not $PreserveConfig) {
    foreach ($candidate in $configDirCandidates) {
        if (Test-Path -LiteralPath $candidate) {
            Remove-Item -LiteralPath $candidate -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

Get-ChildItem -Path $consoleLogs -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
Get-ChildItem -Path $errorLogs -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

Write-Host "Launching $AppExe"
$process = Start-Process -FilePath $AppExe -WorkingDirectory $appDir -PassThru

$deadline = (Get-Date).AddSeconds($WaitSeconds)
$passed = $false
$healthyDeadline = (Get-Date).AddSeconds($HealthyProcessSeconds)
$activeConfigDir = Resolve-ExistingSmokeConfigDir -Candidates $configDirCandidates

try {
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2

        if ($process.HasExited) {
            $exitCode = $process.ExitCode
            throw "Application exited early with code $exitCode"
        }

        $activeConfigDir = Resolve-ExistingSmokeConfigDir -Candidates $configDirCandidates
        $runtimeDir = Join-Path $activeConfigDir "runtime"
        $requiredRuntimeLogDir = Join-Path $runtimeDir $RequiredLogDir
        $configFile = Join-Path $activeConfigDir "config.txt"
        $persistFile = Join-Path $activeConfigDir "persist"
        $hasConfig = (Test-Path -LiteralPath $configFile) -and (Test-Path -LiteralPath $persistFile)
        $hasRuntimeLogs = Test-Path -LiteralPath $requiredRuntimeLogDir
        $hasRuntimeState = $hasRuntimeLogs -or (Test-Path -LiteralPath $runtimeDir)

        if ($hasConfig -and $hasRuntimeState) {
            Write-Host "Resolved config dir: $activeConfigDir"
            if ($hasRuntimeLogs) {
                Start-Sleep -Seconds 2
                Assert-NoBundledEngineStartupFailure -RuntimeLogDir $requiredRuntimeLogDir
            }
            Invoke-BundledKataGoBenchmarkProbe -AppExe $AppExe -ConfigDir $activeConfigDir
            if ($ProbeBoardSync) {
                Invoke-NativeReadBoardPipeProbe -AppExe $AppExe
                Invoke-BoardSyncHotkeyProbe -AppProcess $process -AppExe $AppExe
            }
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

        if ((Get-Date) -ge $healthyDeadline -and (-not $RequireConfig)) {
            Write-Host "Resolved config dir candidate: $activeConfigDir"
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

    $activeConfigDir = Resolve-ExistingSmokeConfigDir -Candidates $configDirCandidates
    Write-Host "--- Config dir ---"
    Write-Host $activeConfigDir
    if ($activeConfigDir -and (Test-Path -LiteralPath $activeConfigDir)) {
        Get-ChildItem -LiteralPath $activeConfigDir -Recurse -Force | Select-Object FullName, Length
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
