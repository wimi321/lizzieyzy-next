param(
    [Parameter(Mandatory = $true)]
    [string]$InputDir,

    [Parameter(Mandatory = $true)]
    [string]$MainJar,

    [Parameter(Mandatory = $true)]
    [string]$IconPath,

    [Parameter(Mandatory = $true)]
    [string]$UpgradeUuid,

    [string]$AppName = "LizzieYzy Next",

    [string]$MainClass = "featurecat.lizzie.Lizzie",

    [string]$Description = "LizzieYzy maintained fork with restored Fox nickname sync",

    [string]$Vendor = "wimi321",

    [string]$VersionOld = "2.6.8301",

    [string]$VersionNew = "2.6.8302",

    [string]$SmokeInstallDir = "LizzieYzyNextSmoke",

    [string]$SmokeConfigDir = "",

    [string]$RequiredLogDir = "gtp_logs"
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

function Invoke-JPackageMsiBuild {
    param(
        [string]$Version,
        [string]$DestDir
    )

    New-Item -ItemType Directory -Force -Path $DestDir | Out-Null

    $arguments = @(
        "--type", "msi",
        "--name", $AppName,
        "--input", $InputDir,
        "--main-jar", $MainJar,
        "--main-class", $MainClass,
        "--dest", $DestDir,
        "--app-version", $Version,
        "--vendor", $Vendor,
        "--description", $Description,
        "--icon", $IconPath,
        "--win-menu",
        "--win-shortcut",
        "--win-per-user-install",
        "--win-upgrade-uuid", $UpgradeUuid,
        "--install-dir", $SmokeInstallDir,
        "--java-options", "-XX:InitialRAMPercentage=1.0",
        "--java-options", "-XX:MaxRAMPercentage=50.0"
    )

    & jpackage @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed for version $Version"
    }

    $msi = Get-ChildItem -LiteralPath $DestDir -Filter *.msi | Select-Object -First 1
    if (-not $msi) {
        throw "No MSI was generated in $DestDir"
    }
    return $msi.FullName
}

function Invoke-MsiInstall {
    param(
        [string]$MsiPath,
        [string]$LogPath
    )

    $arguments = "/i `"$MsiPath`" /qn /norestart /l*v `"$LogPath`""
    $process = Start-Process -FilePath "msiexec.exe" -ArgumentList $arguments -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        if (Test-Path -LiteralPath $LogPath) {
            Write-Host "MSI log ($LogPath):"
            Get-Content -LiteralPath $LogPath -Tail 200 -ErrorAction SilentlyContinue
        }
        throw "msiexec failed for $MsiPath with exit code $($process.ExitCode)"
    }
}

function Find-InstalledAppExe {
    $roots = @(
        (Join-Path $env:LOCALAPPDATA "Programs"),
        $env:LOCALAPPDATA,
        $env:ProgramFiles,
        ${env:ProgramFiles(x86)}
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

    foreach ($root in $roots) {
        $match = Get-ChildItem -LiteralPath $root -Filter "$AppName.exe" -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -like "*$SmokeInstallDir*" } |
            Select-Object -First 1
        if ($match) {
            return $match.FullName
        }
    }

    throw "Installed application executable was not found after MSI upgrade test."
}

function Set-StaleLegacyEngineConfig {
    param(
        [string]$ConfigPath
    )

    if (-not (Test-Path -LiteralPath $ConfigPath)) {
        throw "Config file not found for stale-engine migration test: $ConfigPath"
    }

    $config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
    if (-not $config.ui) {
        $config | Add-Member -NotePropertyName ui -NotePropertyValue ([pscustomobject]@{})
    }
    if (-not $config.leelaz) {
        $config | Add-Member -NotePropertyName leelaz -NotePropertyValue ([pscustomobject]@{})
    }

    function Set-JsonProperty {
        param(
            [Parameter(Mandatory = $true)]
            [psobject]$Target,

            [Parameter(Mandatory = $true)]
            [string]$Name,

            [Parameter(Mandatory = $false)]
            $Value
        )

        $property = $Target.PSObject.Properties[$Name]
        if ($null -eq $property) {
            $Target | Add-Member -NotePropertyName $Name -NotePropertyValue $Value
            return
        }

        $property.Value = $Value
    }

    $legacyEngine = [pscustomobject]@{
        ip = ""
        initialCommand = ""
        userName = ""
        preload = $false
        command = 'java -jar legacy\broken-engine-launcher.jar'
        komi = 7.5
        password = ""
        isDefault = $true
        port = ""
        name = "Legacy KataGo"
        width = 19
        useJavaSSH = $false
        useKeyGen = $false
        keyGenPath = ""
        height = 19
    }

    Set-JsonProperty -Target $config.leelaz -Name 'engine-settings-list' -Value @($legacyEngine)
    Set-JsonProperty -Target $config.ui -Name 'default-engine' -Value 0
    Set-JsonProperty -Target $config.ui -Name 'autoload-default' -Value $true
    Set-JsonProperty -Target $config.ui -Name 'autoload-empty' -Value $false
    Set-JsonProperty -Target $config.ui -Name 'first-time-load' -Value $false
    Set-JsonProperty -Target $config.ui -Name 'analysis-engine-command' -Value 'java -jar legacy\broken-analysis-launcher.jar'
    Set-JsonProperty -Target $config.ui -Name 'estimate-command' -Value 'java -jar legacy\broken-estimate-launcher.jar'

    $json = $config | ConvertTo-Json -Depth 100
    [System.IO.File]::WriteAllText($ConfigPath, $json, [System.Text.Encoding]::UTF8)
}

function Assert-RepairedBundledEngineConfig {
    param(
        [string]$ConfigPath
    )

    if (-not (Test-Path -LiteralPath $ConfigPath)) {
        throw "Config file not found after repair test: $ConfigPath"
    }

    $config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
    $defaultIndex = [int]($config.ui.'default-engine')
    $engines = @($config.leelaz.'engine-settings-list')
    if ($defaultIndex -lt 0 -or $defaultIndex -ge $engines.Count) {
        throw "Repaired config does not point at a valid default engine."
    }

    $defaultEngine = $engines[$defaultIndex]
    $engineCommand = [string]$defaultEngine.command
    $analysisCommand = [string]$config.ui.'analysis-engine-command'
    $estimateCommand = [string]$config.ui.'estimate-command'

    if ($engineCommand -match 'java\s+-jar') {
        throw "Startup repair failed: default engine still points to a legacy java launcher."
    }
    if ($analysisCommand -match 'java\s+-jar') {
        throw "Startup repair failed: analysis engine command still points to a legacy java launcher."
    }
    if ($estimateCommand -match 'java\s+-jar') {
        throw "Startup repair failed: estimate engine command still points to a legacy java launcher."
    }
    if ($engineCommand -notmatch 'engines[\\/]+katago') {
        throw "Startup repair failed: default engine was not rewritten to the bundled KataGo command."
    }
}

function Invoke-UpgradedAppRepairSmoke {
    param(
        [Parameter(Mandatory = $true)]
        [string]$AppExe,

        [Parameter(Mandatory = $true)]
        [string]$ConfigDir,

        [string]$RequiredLogDir = "gtp_logs",

        [int]$WaitSeconds = 90
    )

    if (-not (Test-Path -LiteralPath $AppExe)) {
        throw "App executable not found for upgrade repair validation: $AppExe"
    }

    $appDir = Split-Path -Parent $AppExe
    $consoleLogs = Join-Path $appDir "LastConsoleLogs_*.txt"
    $errorLogs = Join-Path $appDir "LastErrorLogs_*.txt"
    $configDirCandidates = @(Get-SmokeConfigDirCandidates -PreferredConfigDir $ConfigDir)
    if ($configDirCandidates.Count -eq 0) {
        throw "No candidate config directory could be resolved for upgrade repair validation."
    }

    Get-ChildItem -Path $consoleLogs -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
    Get-ChildItem -Path $errorLogs -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

    Write-Host "Launching $AppExe for upgrade repair validation"
    $process = Start-Process -FilePath $AppExe -WorkingDirectory $appDir -PassThru
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    $lastError = $null
    $activeConfigDir = Resolve-ExistingSmokeConfigDir -Candidates $configDirCandidates
    $loggedConfigDetected = $false
    $loggedRepairWaiting = $false

    try {
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 2

            if ($process.HasExited) {
                $exitCode = $process.ExitCode
                throw "Application exited early with code $exitCode during upgrade repair validation."
            }

            $activeConfigDir = Resolve-ExistingSmokeConfigDir -Candidates $configDirCandidates
            $runtimeDir = Join-Path $activeConfigDir "runtime"
            $requiredRuntimeLogDir = Join-Path $runtimeDir $RequiredLogDir
            $configPath = Join-Path $activeConfigDir "config.txt"
            $persistFile = Join-Path $activeConfigDir "persist"
            $hasConfig = (Test-Path -LiteralPath $configPath) -and (Test-Path -LiteralPath $persistFile)
            $hasRuntimeLogs = Test-Path -LiteralPath $requiredRuntimeLogDir
            $hasRuntimeState = $hasRuntimeLogs -or (Test-Path -LiteralPath $runtimeDir)

            if ($hasConfig -and (-not $loggedConfigDetected)) {
                Write-Host "Config files detected. Waiting for repaired bundled engine config..."
                $loggedConfigDetected = $true
            }

            if ($hasConfig -and $hasRuntimeState) {
                Write-Host "Resolved config dir: $activeConfigDir"
                try {
                    if ($hasRuntimeLogs) {
                        Start-Sleep -Seconds 2
                        Assert-NoBundledEngineStartupFailure -RuntimeLogDir $requiredRuntimeLogDir
                    }
                    Assert-RepairedBundledEngineConfig -ConfigPath $configPath
                    if ($hasRuntimeLogs) {
                        Write-Host "Upgrade smoke test passed. Config repair completed and bundled KataGo runtime logs were created."
                    }
                    else {
                        Write-Host "Upgrade smoke test passed. Config repair completed."
                    }
                    return
                } catch {
                    $lastError = $_
                    if (-not $loggedRepairWaiting) {
                        Write-Host "Runtime is ready. Waiting for startup repair to rewrite bundled engine commands..."
                        $loggedRepairWaiting = $true
                    }
                }
            }
        }
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

    if ($lastError) {
        throw $lastError
    }

    throw "Timed out while waiting for upgraded app repair validation."
}

$tempRoot = Join-Path $env:RUNNER_TEMP "lizzieyzy-next-msi-smoke"
$oldDest = Join-Path $tempRoot "old"
$newDest = Join-Path $tempRoot "new"
$logsDir = Join-Path $tempRoot "logs"
$smokeConfigCandidates = @(Get-SmokeConfigDirCandidates -PreferredConfigDir $SmokeConfigDir)

Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $logsDir | Out-Null

$msiOld = Invoke-JPackageMsiBuild -Version $VersionOld -DestDir $oldDest
$msiNew = Invoke-JPackageMsiBuild -Version $VersionNew -DestDir $newDest

Invoke-MsiInstall -MsiPath $msiOld -LogPath (Join-Path $logsDir "install-old.log")
& (Join-Path $PSScriptRoot "windows_smoke_test.ps1") `
    -AppExe (Find-InstalledAppExe) `
    -ConfigDir $SmokeConfigDir `
    -RequiredLogDir $RequiredLogDir `
    -WaitSeconds 60 `
    -RequireConfig

$resolvedSmokeConfigDir = Resolve-ExistingSmokeConfigDir -Candidates $smokeConfigCandidates
if (-not $resolvedSmokeConfigDir) {
    throw "Smoke config directory was not created by the first installed app launch."
}
Write-Host "Resolved smoke config dir: $resolvedSmokeConfigDir"
$configPath = Join-Path $resolvedSmokeConfigDir "config.txt"
Set-StaleLegacyEngineConfig -ConfigPath $configPath

Invoke-MsiInstall -MsiPath $msiNew -LogPath (Join-Path $logsDir "install-new.log")

$appExe = Find-InstalledAppExe
Write-Host "Installed exe: $appExe"

Invoke-UpgradedAppRepairSmoke `
    -AppExe $appExe `
    -ConfigDir $resolvedSmokeConfigDir `
    -RequiredLogDir $RequiredLogDir `
    -WaitSeconds 90
