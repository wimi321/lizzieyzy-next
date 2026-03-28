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
        "--java-options", "-Xmx4096m"
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

function Wait-ForRepairedBundledEngineConfig {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ConfigPath,

        [int]$TimeoutSeconds = 45,

        [int]$PollIntervalSeconds = 2
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = $null

    while ((Get-Date) -lt $deadline) {
        try {
            Assert-RepairedBundledEngineConfig -ConfigPath $ConfigPath
            Write-Host "Bundled engine config repair detected."
            return
        } catch {
            $lastError = $_
            Start-Sleep -Seconds $PollIntervalSeconds
        }
    }

    if ($lastError) {
        throw $lastError
    }

    throw "Timed out while waiting for bundled engine config repair."
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

& (Join-Path $PSScriptRoot "windows_smoke_test.ps1") `
    -AppExe $appExe `
    -ConfigDir $resolvedSmokeConfigDir `
    -RequiredLogDir $RequiredLogDir `
    -WaitSeconds 60 `
    -PreserveConfig `
    -RequireConfig

if ($LASTEXITCODE -ne 0) {
    throw "Installed app smoke test failed after MSI upgrade."
}

Wait-ForRepairedBundledEngineConfig -ConfigPath $configPath
