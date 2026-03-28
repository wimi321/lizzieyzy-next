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

    [string]$SmokeConfigDir = "$env:USERPROFILE\.lizzieyzy-next",

    [string]$RequiredLogDir = "gtp_logs"
)

$ErrorActionPreference = "Stop"

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

    $config.leelaz.'engine-settings-list' = @($legacyEngine)
    $config.ui.'default-engine' = 0
    $config.ui.'autoload-default' = $true
    $config.ui.'autoload-empty' = $false
    $config.ui.'first-time-load' = $false
    $config.ui.'analysis-engine-command' = 'java -jar legacy\broken-analysis-launcher.jar'
    $config.ui.'estimate-command' = 'java -jar legacy\broken-estimate-launcher.jar'

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

$tempRoot = Join-Path $env:RUNNER_TEMP "lizzieyzy-next-msi-smoke"
$oldDest = Join-Path $tempRoot "old"
$newDest = Join-Path $tempRoot "new"
$logsDir = Join-Path $tempRoot "logs"

Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $logsDir | Out-Null

$msiOld = Invoke-JPackageMsiBuild -Version $VersionOld -DestDir $oldDest
$msiNew = Invoke-JPackageMsiBuild -Version $VersionNew -DestDir $newDest

Invoke-MsiInstall -MsiPath $msiOld -LogPath (Join-Path $logsDir "install-old.log")
& (Join-Path $PSScriptRoot "windows_smoke_test.ps1") `
    -AppExe (Find-InstalledAppExe) `
    -ConfigDir $SmokeConfigDir `
    -RequiredLogDir $RequiredLogDir `
    -WaitSeconds 60

$configPath = Join-Path $SmokeConfigDir "config.txt"
Set-StaleLegacyEngineConfig -ConfigPath $configPath

Invoke-MsiInstall -MsiPath $msiNew -LogPath (Join-Path $logsDir "install-new.log")

$appExe = Find-InstalledAppExe
Write-Host "Installed exe: $appExe"

& (Join-Path $PSScriptRoot "windows_smoke_test.ps1") `
    -AppExe $appExe `
    -ConfigDir $SmokeConfigDir `
    -RequiredLogDir $RequiredLogDir `
    -WaitSeconds 60 `
    -PreserveConfig

if ($LASTEXITCODE -ne 0) {
    throw "Installed app smoke test failed after MSI upgrade."
}

Assert-RepairedBundledEngineConfig -ConfigPath $configPath
