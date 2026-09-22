param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("Prepare", "Start", "Status", "Stop", "Run")]
    [string]$Command,

    [string]$AssetFile = "",
    [string]$ProvenanceFile = "",
    [string]$Platform = "windows",
    [string]$DateTag = "",
    [string]$ReleaseTag = "",
    [string]$TargetSha = "",
    [long]$RunId = 0,
    [int]$RunAttempt = 0,
    [string]$EvidenceDir = "",
    [string]$CandidateJson = "",
    [string]$PriorCandidateJson = "",
    [string]$ExpectedArtifactKey = "",
    [string]$ExpectedArtifactName = "",
    [string]$ExpectedArtifactClass = "",
    [string]$Scenario = "live-session",
    [string]$RunJson = "",
    [string]$EngineOracleFile = "",
    [int]$WaitSeconds = 90,
    [int]$StopTimeoutSeconds = 15,
    [switch]$KeepPreparedProduct
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$script:SchemaVersion = 1
$script:PortableClasses = @("portable-product")
$script:InstallerClasses = @("installer-product")
$script:RunnableClasses = @("portable-product", "installer-product")
$script:ScenarioIds = @(
    "portable-offline-first-run",
    "installer-offline-first-run",
    "installer-upgrade-preserve",
    "core-update-preserve",
    "variant-launch",
    "live-session"
)

function Require-Value {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        throw $Message
    }
}

function Resolve-FullPath {
    param([string]$Path, [string]$Label, [switch]$MustExist)
    Require-Value -Condition ([bool]$Path.Trim()) -Message "$Label is required."
    $full = [System.IO.Path]::GetFullPath($Path)
    if ($MustExist) {
        Require-Value -Condition (Test-Path -LiteralPath $full) -Message "$Label does not exist: $full"
    }
    return $full
}

function Get-FileSha256 {
    param([string]$Path)
    Require-Value -Condition (Test-Path -LiteralPath $Path -PathType Leaf) -Message "File not found: $Path"
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-ObjectSha256 {
    param([object]$Value)
    function ConvertTo-CanonicalValue {
        param([object]$InputValue)
        if ($null -eq $InputValue) { return $null }
        if ($InputValue -is [System.Collections.IDictionary]) {
            $result = [ordered]@{}
            foreach ($key in @($InputValue.Keys | Sort-Object)) { $result[[string]$key] = ConvertTo-CanonicalValue -InputValue $InputValue[$key] }
            return $result
        }
        if ($InputValue -is [pscustomobject]) {
            $result = [ordered]@{}
            foreach ($name in @($InputValue.PSObject.Properties.Name | Sort-Object)) { $result[$name] = ConvertTo-CanonicalValue -InputValue $InputValue.$name }
            return $result
        }
        if ($InputValue -is [System.Collections.IEnumerable] -and $InputValue -isnot [string]) {
            return @($InputValue | ForEach-Object { ConvertTo-CanonicalValue -InputValue $_ })
        }
        return $InputValue
    }
    $canonical = ConvertTo-CanonicalValue -InputValue $Value
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($canonical | ConvertTo-Json -Depth 100 -Compress))
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Get-DirectoryTreeSha256 {
    param([string]$Root)
    $resolved = Resolve-FullPath -Path $Root -Label "component directory" -MustExist
    $rows = @(Get-ChildItem -LiteralPath $resolved -File -Recurse -Force | Sort-Object FullName | ForEach-Object {
        "{0}|{1}|{2}" -f $_.FullName.Substring($resolved.Length + 1).Replace('\', '/'), $_.Length, (Get-FileSha256 -Path $_.FullName)
    })
    return Get-ObjectSha256 -Value $rows
}

function Read-JsonFile {
    param([string]$Path, [string]$Label)
    $resolved = Resolve-FullPath -Path $Path -Label $Label -MustExist
    try {
        return Get-Content -LiteralPath $resolved -Raw -Encoding UTF8 | ConvertFrom-Json
    }
    catch {
        throw "Unable to parse $Label ${resolved}: $($_.Exception.Message)"
    }
}

function Write-JsonAtomic {
    param([string]$Path, [object]$Value)
    $resolved = [System.IO.Path]::GetFullPath($Path)
    $parent = Split-Path -Parent $resolved
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $temporary = Join-Path $parent (".{0}.{1}.tmp" -f ([System.IO.Path]::GetFileName($resolved)), [guid]::NewGuid().ToString("N"))
    $backup = Join-Path $parent (".{0}.{1}.bak" -f ([System.IO.Path]::GetFileName($resolved)), [guid]::NewGuid().ToString("N"))
    try {
        $json = $Value | ConvertTo-Json -Depth 100
        [System.IO.File]::WriteAllText($temporary, $json + [Environment]::NewLine, $script:Utf8NoBom)
        if ([System.IO.File]::Exists($resolved)) {
            [System.IO.File]::Replace($temporary, $resolved, $backup, $true)
        }
        else {
            [System.IO.File]::Move($temporary, $resolved)
        }
    }
    finally {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
    }
}

function Get-UtcTimestamp {
    return [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
}

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Assert-Administrator {
    param([string]$Operation)
    Require-Value -Condition (Test-IsAdministrator) -Message "$Operation requires an elevated Windows administrator session."
}

function Get-PythonInvocation {
    $py = Get-Command py.exe -ErrorAction SilentlyContinue
    if ($py) {
        return [pscustomobject]@{ File = $py.Source; Prefix = @("-3", "-X", "utf8") }
    }
    foreach ($name in @("python.exe", "python3.exe")) {
        $python = Get-Command $name -ErrorAction SilentlyContinue
        if ($python) {
            return [pscustomobject]@{ File = $python.Source; Prefix = @("-X", "utf8") }
        }
    }
    throw "Windows Python 3 is required to verify release provenance."
}

function Assert-CandidateIdentity {
    param([string]$Path, [string[]]$AllowedClasses = @())
    $resolved = Resolve-FullPath -Path $Path -Label "candidate.json" -MustExist
    $candidate = Read-JsonFile -Path $resolved -Label "candidate.json"
    Require-Value -Condition ($candidate.schemaVersion -eq 1) -Message "Unsupported candidate schema."
    Require-Value -Condition ($candidate.platform -eq "windows") -Message "Candidate platform must be windows."
    Require-Value -Condition ($candidate.architecture -eq "x86_64") -Message "Candidate architecture must be x86_64."
    Require-Value -Condition ($candidate.targetSha -match '^[0-9a-f]{40}$') -Message "Candidate targetSha is invalid."
    Require-Value -Condition ($candidate.artifact -and $candidate.provenance) -Message "Candidate identity is incomplete."
    if ($AllowedClasses.Count -gt 0) {
        Require-Value -Condition ($AllowedClasses -contains [string]$candidate.artifact.class) -Message "Candidate class '$($candidate.artifact.class)' is not valid for this command."
    }

    $asset = Resolve-FullPath -Path ([string]$candidate.artifact.sourcePath) -Label "candidate asset" -MustExist
    $provenance = Resolve-FullPath -Path ([string]$candidate.provenance.path) -Label "candidate provenance" -MustExist
    $assetInfo = Get-Item -LiteralPath $asset
    Require-Value -Condition ($assetInfo.Length -eq [long]$candidate.artifact.sizeBytes) -Message "Candidate asset size drift detected."
    Require-Value -Condition ((Get-FileSha256 -Path $asset) -eq [string]$candidate.artifact.sha256) -Message "Candidate asset hash drift detected."
    Require-Value -Condition ((Get-FileSha256 -Path $provenance) -eq [string]$candidate.provenance.sha256) -Message "Candidate provenance hash drift detected."

    return [pscustomobject]@{
        Path = $resolved
        Hash = Get-FileSha256 -Path $resolved
        Value = $candidate
        AssetPath = $asset
        ProvenancePath = $provenance
    }
}

function Test-ZipEntryIsSafe {
    param([string]$DestinationRoot, [string]$EntryName)
    if (-not $EntryName -or $EntryName.IndexOf([char]0) -ge 0) {
        return $false
    }
    $normalized = $EntryName.Replace('/', [System.IO.Path]::DirectorySeparatorChar)
    if ([System.IO.Path]::IsPathRooted($normalized)) {
        return $false
    }
    $target = [System.IO.Path]::GetFullPath((Join-Path $DestinationRoot $normalized))
    $prefix = [System.IO.Path]::GetFullPath($DestinationRoot).TrimEnd('\') + '\'
    return $target.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)
}

function Resolve-ContainedPath {
    param([string]$Root, [string]$RelativePath, [string]$Label)
    Require-Value -Condition ([bool]$RelativePath -and -not [System.IO.Path]::IsPathRooted($RelativePath)) -Message "$Label must be a relative path: $RelativePath"
    $rootPath = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')
    $resolved = [System.IO.Path]::GetFullPath((Join-Path $rootPath $RelativePath))
    $prefix = $rootPath + '\'
    Require-Value -Condition ($resolved.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) -Message "$Label escapes its owned root: $RelativePath"
    return $resolved
}

function New-BlockedException {
    param([string]$Phase, [string]$Reason)
    $exception = New-Object System.InvalidOperationException($Reason)
    $exception.Data["WindowsAcceptanceBlocked"] = $true
    $exception.Data["BlockedPhase"] = $Phase
    return $exception
}

function Throw-Blocked {
    param([string]$Phase, [string]$Reason)
    throw (New-BlockedException -Phase $Phase -Reason $Reason)
}

function Expand-VerifiedZip {
    param([string]$ZipPath, [string]$Destination, [switch]$RequireSingleRoot)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    Require-Value -Condition (-not (Test-Path -LiteralPath $Destination)) -Message "Extraction destination already exists: $Destination"
    New-Item -ItemType Directory -Path $Destination | Out-Null
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        Require-Value -Condition ($archive.Entries.Count -gt 0) -Message "Candidate archive is empty."
        $roots = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
        foreach ($entry in $archive.Entries) {
            Require-Value -Condition (Test-ZipEntryIsSafe -DestinationRoot $Destination -EntryName $entry.FullName) -Message "Unsafe archive entry: $($entry.FullName)"
            $first = ($entry.FullName -split '[\\/]')[0]
            if ($first) { [void]$roots.Add($first) }
        }
        if ($RequireSingleRoot) {
            Require-Value -Condition ($roots.Count -eq 1) -Message "Portable archive must contain exactly one top-level product root."
        }
    }
    finally {
        $archive.Dispose()
    }
    [System.IO.Compression.ZipFile]::ExtractToDirectory($ZipPath, $Destination)
    if ($RequireSingleRoot) {
        $root = Get-ChildItem -LiteralPath $Destination -Force | Select-Object -First 1
        Require-Value -Condition ($root -and $root.PSIsContainer) -Message "Portable archive top-level entry must be a directory."
        return $root.FullName
    }
    return $Destination
}

function Get-BackendExpectation {
    param([string]$ArtifactKey)
    switch ($ArtifactKey) {
        "windows_portable" { return "cpu" }
        "windows_installer" { return "cpu" }
        "windows_opencl_portable" { return "opencl" }
        "windows_opencl_installer" { return "opencl" }
        "windows_nvidia_portable" { return "nvidia" }
        "windows_nvidia_installer" { return "nvidia" }
        "windows_directml_experimental" { return "directml" }
        "windows_openvino_experimental" { return "openvino" }
        "windows_rocm_gfx103x_experimental" { return "rocm-gfx103x" }
        "windows_rocm_gfx110x_experimental" { return "rocm-gfx110x" }
        "windows_rocm_gfx1151_experimental" { return "rocm-gfx1151" }
        "windows_rocm_gfx120x_experimental" { return "rocm-gfx120x" }
        "windows_no_engine_portable" { return "none" }
        "windows_no_engine_installer" { return "none" }
        default { throw "Unsupported Windows product key: $ArtifactKey" }
    }
}

function Resolve-ProductLayout {
    param([string]$ProductRoot, [object]$Candidate, [bool]$Portable, [bool]$ProbeRuntime = $true)
    $root = Resolve-FullPath -Path $ProductRoot -Label "prepared product root" -MustExist
    if ($Portable) {
        Require-Value -Condition (Test-Path -LiteralPath (Join-Path $root ".lizzie-portable") -PathType Leaf) -Message "Portable marker is missing."
        Require-Value -Condition (Test-Path -LiteralPath (Join-Path $root "user-data") -PathType Container) -Message "Portable user-data directory is missing."
    }

    $launchers = @(Get-ChildItem -LiteralPath $root -Filter "LizzieYzy Next*.exe" -File)
    Require-Value -Condition ($launchers.Count -eq 1) -Message "Prepared product must contain exactly one root launcher."
    $launcher = $launchers[0].FullName
    $runtime = Join-Path $root "runtime\bin\java.exe"
    Require-Value -Condition (Test-Path -LiteralPath $runtime -PathType Leaf) -Message "Packaged runtime is missing: $runtime"
    $jvmDlls = @(Get-ChildItem -LiteralPath (Join-Path $root "runtime") -Filter "jvm.dll" -File -Recurse)
    Require-Value -Condition ($jvmDlls.Count -eq 1) -Message "Packaged runtime must contain exactly one jvm.dll."
    $appRoot = Join-Path $root "app"
    $cfg = Join-Path $appRoot (([System.IO.Path]::GetFileNameWithoutExtension($launcher)) + ".cfg")
    Require-Value -Condition (Test-Path -LiteralPath $cfg -PathType Leaf) -Message "Launcher configuration is missing: $cfg"
    $jars = @(Get-ChildItem -LiteralPath $appRoot -Filter "*-shaded.jar" -File)
    Require-Value -Condition ($jars.Count -eq 1) -Message "Prepared product must contain exactly one shaded JAR."
    $installedManifest = Join-Path $appRoot "lizzieyzy-next-installed-manifest.json"
    Require-Value -Condition (Test-Path -LiteralPath $installedManifest -PathType Leaf) -Message "Installed product manifest is missing."
    $manifest = Read-JsonFile -Path $installedManifest -Label "installed product manifest"
    Require-Value -Condition ([string]$manifest.releaseTag -eq [string]$Candidate.releaseTag) -Message "Installed product release identity does not match candidate."

    foreach ($required in @(
        "jcef-bundle\libcef.dll",
        "jcef-bundle\lizzieyzy-next-jcef-manifest.txt",
        "readboard\readboard.exe",
        "readboard\lizzieyzy-next-readboard-manifest.txt"
    )) {
        Require-Value -Condition (Test-Path -LiteralPath (Join-Path $appRoot $required) -PathType Leaf) -Message "Packaged component is missing: $required"
    }

    $backend = Get-BackendExpectation -ArtifactKey ([string]$Candidate.artifact.key)
    $markerPath = $null
    $markerValue = $null
    $engine = $null
    $engineConfig = $null
    $model = $null
    if ($backend -eq "none") {
        Require-Value -Condition (-not (Test-Path -LiteralPath (Join-Path $appRoot "weights\default.bin.gz"))) -Message "No-engine product unexpectedly contains the default weight."
        Require-Value -Condition (@(Get-ChildItem -LiteralPath (Join-Path $appRoot "engines") -Filter "katago.exe" -File -Recurse -ErrorAction SilentlyContinue).Count -eq 0) -Message "No-engine product unexpectedly contains a bundled KataGo executable."
    }
    else {
        $model = Join-Path $appRoot "weights\default.bin.gz"
        $engineConfig = Join-Path $appRoot "engines\katago\configs\gtp.cfg"
        Require-Value -Condition (Test-Path -LiteralPath $model -PathType Leaf) -Message "Default model is missing."
        Require-Value -Condition (Test-Path -LiteralPath $engineConfig -PathType Leaf) -Message "Bundled engine config is missing."
        $engines = @(Get-ChildItem -LiteralPath (Join-Path $appRoot "engines\katago") -Filter "katago.exe" -File -Recurse)
        Require-Value -Condition ($engines.Count -eq 1) -Message "Bundled KataGo executable is missing or ambiguous."
        $engine = $engines[0].FullName
        $markers = @(Get-ChildItem -LiteralPath (Join-Path $appRoot "engines\katago") -Filter "lizzieyzy-next-engine-backend.txt" -File -Recurse)
        Require-Value -Condition ($markers.Count -eq 1) -Message "Bundled backend marker is missing or ambiguous."
        $markerPath = $markers[0].FullName
        $markerValue = (Get-Content -LiteralPath $markerPath -Raw -Encoding UTF8).Trim()
        Require-Value -Condition ($markerValue.ToLowerInvariant().Contains($backend)) -Message "Backend marker '$markerValue' does not match expected backend '$backend'."
    }

    $joinedRuntime = $null
    if ($ProbeRuntime) {
        $runtimeOutput = @()
        $runtimeExit = $null
        try {
            $runtimeOutput = & $runtime -version 2>&1 | ForEach-Object { $_.ToString() }
            $runtimeExit = $LASTEXITCODE
        }
        catch {
            $runtimeOutput = @($_.Exception.Message)
            $runtimeExit = -1
        }
        Require-Value -Condition ($runtimeExit -eq 0) -Message "Packaged Java failed version probe: $($runtimeOutput -join ' ')"
        $joinedRuntime = $runtimeOutput -join [Environment]::NewLine
        Require-Value -Condition ($joinedRuntime -match '(?i)(64-Bit|amd64|x86_64)') -Message "Packaged Java version output does not prove x86_64: $joinedRuntime"
    }

    return [pscustomobject]@{
        Root = $root
        AppRoot = $appRoot
        Launcher = $launcher
        LauncherSha256 = Get-FileSha256 -Path $launcher
        Runtime = $runtime
        RuntimeSha256 = Get-FileSha256 -Path $runtime
        RuntimeVersion = $joinedRuntime
        JvmDll = $jvmDlls[0].FullName
        JvmDllSha256 = Get-FileSha256 -Path $jvmDlls[0].FullName
        Jar = $jars[0].FullName
        JarSha256 = Get-FileSha256 -Path $jars[0].FullName
        Config = $cfg
        ConfigSha256 = Get-FileSha256 -Path $cfg
        InstalledManifest = $installedManifest
        InstalledManifestSha256 = Get-FileSha256 -Path $installedManifest
        InstalledReleaseTag = [string]$manifest.releaseTag
        Backend = $backend
        BackendMarker = $markerPath
        BackendMarkerSha256 = if ($markerPath) { Get-FileSha256 -Path $markerPath } else { $null }
        BackendMarkerValue = $markerValue
        Engine = $engine
        EngineSha256 = if ($engine) { Get-FileSha256 -Path $engine } else { $null }
        EngineConfig = $engineConfig
        EngineConfigSha256 = if ($engineConfig) { Get-FileSha256 -Path $engineConfig } else { $null }
        Model = $model
        ModelSha256 = if ($model) { Get-FileSha256 -Path $model } else { $null }
        Jcef = Join-Path $appRoot "jcef-bundle"
        JcefSha256 = Get-DirectoryTreeSha256 -Root (Join-Path $appRoot "jcef-bundle")
        ReadBoard = Join-Path $appRoot "readboard"
        ReadBoardSha256 = Get-DirectoryTreeSha256 -Root (Join-Path $appRoot "readboard")
    }
}

function Get-UninstallEntries {
    $roots = @(
        "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*"
    )
    return @(
        foreach ($root in $roots) {
            Get-ItemProperty -Path $root -ErrorAction SilentlyContinue |
                Where-Object { $_.PSObject.Properties["DisplayName"] -and $_.DisplayName -like "LizzieYzy Next*" -and $_.PSObject.Properties["UninstallString"] -and $_.UninstallString }
        }
    )
}

function Convert-UninstallEntry {
    param([object]$Entry, [string]$LogPath, [string]$FallbackRoot)
    $location = if ($Entry.PSObject.Properties["InstallLocation"]) { [string]$Entry.InstallLocation } else { "" }
    return [pscustomobject]@{
        ProductRoot = if ($location) { [System.IO.Path]::GetFullPath($location) } else { [System.IO.Path]::GetFullPath($FallbackRoot) }
        RegistryPath = [string]$Entry.PSPath
        ProductCode = [string]$Entry.PSChildName
        DisplayName = [string]$Entry.DisplayName
        DisplayVersion = if ($Entry.PSObject.Properties["DisplayVersion"]) { [string]$Entry.DisplayVersion } else { "" }
        UninstallString = [string]$Entry.UninstallString
        QuietUninstallString = if ($Entry.PSObject.Properties["QuietUninstallString"]) { [string]$Entry.QuietUninstallString } else { "" }
        LogPath = $LogPath
    }
}

function Invoke-InstallerProcess {
    param([string]$Installer, [string]$InstallRoot, [string]$LogPath)
    & $Installer "/qn" "/norestart" "INSTALLDIR=$InstallRoot" "/l*v" $LogPath
    return $LASTEXITCODE
}

function Invoke-Installer {
    param([string]$Installer, [string]$InstallRoot, [string]$LogPath, [object]$AllowedExistingInstall = $null)
    Assert-Administrator -Operation "Windows installer acceptance"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $LogPath) | Out-Null
    $beforeEntries = @(Get-UninstallEntries)
    if ($AllowedExistingInstall) {
        $unexpected = @($beforeEntries | Where-Object { [string]$_.PSPath -ine [string]$AllowedExistingInstall.RegistryPath })
        Require-Value -Condition ($unexpected.Count -eq 0 -and @($beforeEntries | Where-Object { [string]$_.PSPath -ieq [string]$AllowedExistingInstall.RegistryPath }).Count -eq 1) -Message "Installer upgrade ownership does not match the single allowed predecessor."
    }
    else {
        Require-Value -Condition ($beforeEntries.Count -eq 0) -Message "A related LizzieYzy Next installation already exists outside this acceptance run."
    }
    if (-not $AllowedExistingInstall) { Require-Value -Condition (-not (Test-Path -LiteralPath $InstallRoot)) -Message "Disposable installer target already exists: $InstallRoot" }
    $beforePaths = @($beforeEntries | ForEach-Object { [string]$_.PSPath })
    try {
        $exitCode = Invoke-InstallerProcess -Installer $Installer -InstallRoot $InstallRoot -LogPath $LogPath
        Require-Value -Condition ($exitCode -eq 0) -Message "Installer exited with code $exitCode."
        $entries = @(Get-UninstallEntries | Where-Object {
            ($_.PSObject.Properties["InstallLocation"] -and $_.InstallLocation -and ([System.IO.Path]::GetFullPath([string]$_.InstallLocation) -ieq [System.IO.Path]::GetFullPath($InstallRoot))) -or
            ($beforePaths -notcontains [string]$_.PSPath)
        })
        Require-Value -Condition ($entries.Count -eq 1) -Message "Installer evidence did not identify exactly one installed product instance."
        $installed = Convert-UninstallEntry -Entry $entries[0] -LogPath $LogPath -FallbackRoot $InstallRoot
        Require-Value -Condition ($installed.ProductRoot -ieq [System.IO.Path]::GetFullPath($InstallRoot)) -Message "Installer ignored the requested disposable install path."
        return $installed
    }
    catch {
        $failure = $_
        $rollbackErrors = New-Object System.Collections.Generic.List[string]
        $ownedEntries = @(Get-UninstallEntries | Where-Object {
            ($_.PSObject.Properties["InstallLocation"] -and $_.InstallLocation -and ([System.IO.Path]::GetFullPath([string]$_.InstallLocation) -ieq [System.IO.Path]::GetFullPath($InstallRoot))) -or
            ($beforePaths -notcontains [string]$_.PSPath)
        })
        foreach ($entry in $ownedEntries) {
            try {
                $owned = Convert-UninstallEntry -Entry $entry -LogPath $LogPath -FallbackRoot $InstallRoot
                Invoke-Uninstall -InstallEvidence $owned -LogPath ($LogPath + ".rollback.log")
            }
            catch { $rollbackErrors.Add($_.Exception.Message) }
        }
        if (-not $AllowedExistingInstall -and (Test-Path -LiteralPath $InstallRoot)) {
            try { Remove-Item -LiteralPath $InstallRoot -Recurse -Force -ErrorAction Stop } catch { $rollbackErrors.Add("partial install root: $($_.Exception.Message)") }
        }
        $remainingOwned = @(Get-UninstallEntries | Where-Object { $beforePaths -notcontains [string]$_.PSPath })
        if ($remainingOwned.Count -gt 0) { $rollbackErrors.Add("$($remainingOwned.Count) owned installer registration(s) remain") }
        if ($rollbackErrors.Count -gt 0) { throw "$($failure.Exception.Message) Installer rollback failed: $($rollbackErrors -join '; ')" }
        throw $failure
    }
}


function Invoke-Uninstall {
    param([object]$InstallEvidence, [string]$LogPath)
    if (-not $InstallEvidence) { return }
    $commandLine = if ($InstallEvidence.QuietUninstallString) { [string]$InstallEvidence.QuietUninstallString } else { [string]$InstallEvidence.UninstallString }
    if (-not $commandLine) { throw "Installed product has no uninstall command." }
    $match = [regex]::Match($commandLine, '^\s*"(?<file>[^"]+)"\s*(?<args>.*)$')
    if ($match.Success) {
        $file = $match.Groups['file'].Value
        $arguments = $match.Groups['args'].Value
    }
    else {
        $parts = $commandLine.Split(' ', 2)
        $file = $parts[0]
        $arguments = if ($parts.Count -gt 1) { $parts[1] } else { "" }
    }
    if ($file -match '(?i)msiexec(\.exe)?$') {
        $arguments = $arguments -replace '(?i)(^|\s)/I(?=\s|\{)', '$1/X'
        $arguments = "$arguments /qn /norestart /l*v `"$LogPath`""
    }
    elseif ($arguments -notmatch '(?i)(/S|/quiet)') {
        $arguments = "$arguments /S"
    }
    $process = Start-Process -FilePath $file -ArgumentList $arguments -Wait -PassThru
    Require-Value -Condition ($process.ExitCode -eq 0) -Message "Uninstaller exited with code $($process.ExitCode)."
}

function Invoke-Prepare {
    $evidence = Resolve-FullPath -Path $EvidenceDir -Label "evidence directory"
    Require-Value -Condition (-not (Test-Path -LiteralPath $evidence)) -Message "Evidence directory must be new: $evidence"
    New-Item -ItemType Directory -Path $evidence | Out-Null
    $asset = Resolve-FullPath -Path $AssetFile -Label "final asset" -MustExist
    $provenance = Resolve-FullPath -Path $ProvenanceFile -Label "provenance file" -MustExist
    $candidatePath = Join-Path $evidence "candidate.json"
    $python = Get-PythonInvocation
    $provenanceScript = Join-Path $PSScriptRoot "release_asset_provenance.py"
    $arguments = @($python.Prefix) + @(
        $provenanceScript, "verify-candidate",
        "--manifest", $provenance,
        "--platform", $Platform,
        "--date-tag", $DateTag,
        "--release-tag", $ReleaseTag,
        "--target-sha", $TargetSha,
        "--run-id", [string]$RunId,
        "--run-attempt", [string]$RunAttempt,
        "--asset-name", [System.IO.Path]::GetFileName($asset),
        "--asset-file", $asset,
        "--output", $candidatePath
    )
    & $python.File @arguments
    Require-Value -Condition ($LASTEXITCODE -eq 0) -Message "Final asset provenance verification failed."
    $identity = Assert-CandidateIdentity -Path $candidatePath
    $candidate = $identity.Value
    $preparedRoot = Join-Path $evidence "prepared product 验收"
    $installEvidence = $null
    $layout = $null
    $updateRoot = $null

    try {
        switch ([string]$candidate.artifact.class) {
            "portable-product" {
                $productRoot = Expand-VerifiedZip -ZipPath $identity.AssetPath -Destination $preparedRoot -RequireSingleRoot
                $layout = Resolve-ProductLayout -ProductRoot $productRoot -Candidate $candidate -Portable $true
            }
            "installer-product" {
                $logPath = Join-Path $evidence "installer-prepare.log"
                $installEvidence = Invoke-Installer -Installer $identity.AssetPath -InstallRoot $preparedRoot -LogPath $logPath
                $layout = Resolve-ProductLayout -ProductRoot $installEvidence.ProductRoot -Candidate $candidate -Portable $false
            }
            "core-update" {
                $updateRoot = Expand-VerifiedZip -ZipPath $identity.AssetPath -Destination $preparedRoot
                $manifestPath = Join-Path $updateRoot "lizzieyzy-next-core-update-manifest.json"
                Require-Value -Condition (Test-Path -LiteralPath $manifestPath -PathType Leaf) -Message "Core update manifest is missing."
                $manifest = Read-JsonFile -Path $manifestPath -Label "core update manifest"
                Require-Value -Condition ($manifest.kind -eq "windows-core-update") -Message "Core update manifest kind is invalid."
                Require-Value -Condition ($manifest.releaseTag -eq $candidate.releaseTag) -Message "Core update release identity does not match candidate."
                $declared = @($manifest.files | ForEach-Object { ([string]$_.path).Replace('\', '/') })
                $actual = @(Get-ChildItem -LiteralPath $updateRoot -File -Recurse | ForEach-Object { $_.FullName.Substring($updateRoot.Length + 1).Replace('\', '/') })
                $allowedSupport = @("README.txt", "lizzieyzy-next-core-update-manifest.json")
                $unexpected = @($actual | Where-Object { $declared -notcontains $_ -and $allowedSupport -notcontains $_ })
                Require-Value -Condition ($unexpected.Count -eq 0) -Message "Core update contains undeclared files: $($unexpected -join ', ')"
                foreach ($file in $manifest.files) {
                    $relativePath = ([string]$file.path).Replace('\', '/').TrimStart('/')
                    $preservedPrefixes = @("user-data/", "runtime/", "app/engines/", "app/weights/", "app/jcef-bundle/", "app/readboard/")
                    Require-Value -Condition (-not ($preservedPrefixes | Where-Object { $relativePath.StartsWith($_, [StringComparison]::OrdinalIgnoreCase) })) -Message "Core update manifest attempts to replace preserved resource: $relativePath"
                    $path = Resolve-ContainedPath -Root $updateRoot -RelativePath ([string]$file.path) -Label "core update source"
                    Require-Value -Condition (Test-Path -LiteralPath $path -PathType Leaf) -Message "Core update declared file is missing: $($file.path)"
                    Require-Value -Condition ((Get-Item -LiteralPath $path).Length -eq [long]$file.sizeBytes) -Message "Core update file size mismatch: $($file.path)"
                    Require-Value -Condition ((Get-FileSha256 -Path $path) -eq [string]$file.sha256) -Message "Core update file hash mismatch: $($file.path)"
                }
            }
            default { throw "Unsupported Windows candidate class: $($candidate.artifact.class)" }
        }
    }
    catch {
        $failure = $_
        if ($installEvidence) {
            try { Invoke-Uninstall -InstallEvidence $installEvidence -LogPath (Join-Path $evidence "installer-prepare-rollback.log") }
            catch { throw "$($failure.Exception.Message) Installer preparation rollback failed: $($_.Exception.Message)" }
        }
        throw $failure
    }

    $prepared = [ordered]@{
        schemaVersion = $script:SchemaVersion
        preparedAt = Get-UtcTimestamp
        candidatePath = $identity.Path
        candidateSha256 = $identity.Hash
        artifactSha256 = [string]$candidate.artifact.sha256
        artifactClass = [string]$candidate.artifact.class
        productRoot = if ($layout) { $layout.Root } else { $null }
        updateRoot = $updateRoot
        coreManifestSha256 = if ($updateRoot) { Get-FileSha256 -Path (Join-Path $updateRoot "lizzieyzy-next-core-update-manifest.json") } else { $null }
        layout = if ($layout) { [ordered]@{
            launcher = $layout.Launcher
            launcherSha256 = $layout.LauncherSha256
            runtime = $layout.Runtime
            runtimeSha256 = $layout.RuntimeSha256
            runtimeVersion = $layout.RuntimeVersion
            jvmDll = $layout.JvmDll
            jvmDllSha256 = $layout.JvmDllSha256
            jar = $layout.Jar
            jarSha256 = $layout.JarSha256
            config = $layout.Config
            configSha256 = $layout.ConfigSha256
            installedManifest = $layout.InstalledManifest
            installedManifestSha256 = $layout.InstalledManifestSha256
            installedReleaseTag = $layout.InstalledReleaseTag
            backend = $layout.Backend
            backendMarker = $layout.BackendMarker
            backendMarkerSha256 = $layout.BackendMarkerSha256
            backendMarkerValue = $layout.BackendMarkerValue
            engine = $layout.Engine
            engineSha256 = $layout.EngineSha256
            engineConfig = $layout.EngineConfig
            engineConfigSha256 = $layout.EngineConfigSha256
            model = $layout.Model
            modelSha256 = $layout.ModelSha256
            jcef = $layout.Jcef
            jcefSha256 = $layout.JcefSha256
            readBoard = $layout.ReadBoard
            readBoardSha256 = $layout.ReadBoardSha256
        } } else { $null }
        install = $installEvidence
    }
    $preparedPath = Join-Path $evidence "prepared.json"
    Write-JsonAtomic -Path $preparedPath -Value $prepared
    Write-Host "Prepared verified Windows candidate: $candidatePath"
    Write-Host "Prepared identity: $preparedPath"
}

function Read-PreparedIdentity {
    param([string]$CandidatePath)
    $layout = $null
    $identity = Assert-CandidateIdentity -Path $CandidatePath
    $preparedPath = Join-Path (Split-Path -Parent $identity.Path) "prepared.json"
    $prepared = Read-JsonFile -Path $preparedPath -Label "prepared candidate identity"
    Require-Value -Condition ($prepared.schemaVersion -eq 1) -Message "Unsupported prepared identity schema."
    Require-Value -Condition ([string]$prepared.candidatePath -ieq $identity.Path) -Message "Prepared candidate path drift detected."
    Require-Value -Condition ([string]$prepared.candidateSha256 -eq $identity.Hash) -Message "Prepared candidate hash drift detected."
    Require-Value -Condition ([string]$prepared.artifactSha256 -eq [string]$identity.Value.artifact.sha256) -Message "Prepared artifact identity drift detected."
    if ($identity.Value.artifact.class -in $script:RunnableClasses) {
        foreach ($item in @(
            @{ path = [string]$prepared.layout.launcher; hash = [string]$prepared.layout.launcherSha256; label = "launcher" },
            @{ path = [string]$prepared.layout.runtime; hash = [string]$prepared.layout.runtimeSha256; label = "runtime" },
            @{ path = [string]$prepared.layout.jvmDll; hash = [string]$prepared.layout.jvmDllSha256; label = "JVM module" },
            @{ path = [string]$prepared.layout.jar; hash = [string]$prepared.layout.jarSha256; label = "shaded JAR" },
            @{ path = [string]$prepared.layout.config; hash = [string]$prepared.layout.configSha256; label = "launcher configuration" },
            @{ path = [string]$prepared.layout.installedManifest; hash = [string]$prepared.layout.installedManifestSha256; label = "installed manifest" }
        )) {
            Require-Value -Condition (Test-Path -LiteralPath $item.path -PathType Leaf) -Message "Prepared $($item.label) is missing."
            Require-Value -Condition ((Get-FileSha256 -Path $item.path) -eq $item.hash) -Message "Prepared $($item.label) drift detected."
        }
        $unprobed = Resolve-ProductLayout -ProductRoot ([string]$prepared.productRoot) -Candidate $identity.Value -Portable ($identity.Value.artifact.class -eq "portable-product") -ProbeRuntime $false
        foreach ($item in @(
            @{ actual = $unprobed.Backend; expected = [string]$prepared.layout.backend; label = "backend identity" },
            @{ actual = $unprobed.BackendMarker; expected = [string]$prepared.layout.backendMarker; label = "backend marker path" },
            @{ actual = $unprobed.BackendMarkerSha256; expected = [string]$prepared.layout.backendMarkerSha256; label = "backend marker" },
            @{ actual = $unprobed.Engine; expected = [string]$prepared.layout.engine; label = "engine path" },
            @{ actual = $unprobed.EngineSha256; expected = [string]$prepared.layout.engineSha256; label = "engine" },
            @{ actual = $unprobed.EngineConfig; expected = [string]$prepared.layout.engineConfig; label = "engine config path" },
            @{ actual = $unprobed.EngineConfigSha256; expected = [string]$prepared.layout.engineConfigSha256; label = "engine config" },
            @{ actual = $unprobed.Model; expected = [string]$prepared.layout.model; label = "model path" },
            @{ actual = $unprobed.ModelSha256; expected = [string]$prepared.layout.modelSha256; label = "model" },
            @{ actual = $unprobed.JcefSha256; expected = [string]$prepared.layout.jcefSha256; label = "JCEF closure" },
            @{ actual = $unprobed.ReadBoardSha256; expected = [string]$prepared.layout.readBoardSha256; label = "ReadBoard closure" }
        )) {
            Require-Value -Condition ([string]$item.actual -ceq [string]$item.expected) -Message "Prepared $($item.label) drift detected."
        }
        $layout = Resolve-ProductLayout -ProductRoot ([string]$prepared.productRoot) -Candidate $identity.Value -Portable ($identity.Value.artifact.class -eq "portable-product")
    }
    elseif ($identity.Value.artifact.class -eq "core-update") {
        $manifestPath = Resolve-ContainedPath -Root ([string]$prepared.updateRoot) -RelativePath "lizzieyzy-next-core-update-manifest.json" -Label "core update manifest"
        Require-Value -Condition ((Get-FileSha256 -Path $manifestPath) -eq [string]$prepared.coreManifestSha256) -Message "Prepared core update manifest drift detected."
        $manifest = Read-JsonFile -Path $manifestPath -Label "core update manifest"
        foreach ($file in $manifest.files) {
            $source = Resolve-ContainedPath -Root ([string]$prepared.updateRoot) -RelativePath ([string]$file.path) -Label "core update source"
            Require-Value -Condition ((Get-Item -LiteralPath $source).Length -eq [long]$file.sizeBytes) -Message "Prepared core update file size drift detected: $($file.path)"
            Require-Value -Condition ((Get-FileSha256 -Path $source) -eq [string]$file.sha256) -Message "Prepared core update file hash drift detected: $($file.path)"
        }
    }
    return [pscustomobject]@{ Candidate = $identity; Prepared = $prepared; PreparedPath = $preparedPath; Layout = $layout }
}

function Read-PreparedInstallerOwnership {
    param([string]$CandidatePath, [string]$EvidenceRoot)
    $candidate = [System.IO.Path]::GetFullPath($CandidatePath)
    $preparedPath = Join-Path (Split-Path -Parent $candidate) "prepared.json"
    if (-not (Test-Path -LiteralPath $preparedPath -PathType Leaf)) { return $null }
    $prepared = Read-JsonFile -Path $preparedPath -Label "prepared installer ownership"
    if ([string]$prepared.artifactClass -ne "installer-product") { return $null }
    Require-Value -Condition ($prepared.schemaVersion -eq 1 -and [System.IO.Path]::GetFullPath([string]$prepared.candidatePath) -ieq $candidate) -Message "Prepared installer ownership identity is invalid."
    $productRoot = [System.IO.Path]::GetFullPath([string]$prepared.productRoot)
    $evidencePrefix = [System.IO.Path]::GetFullPath($EvidenceRoot).TrimEnd('\') + '\'
    Require-Value -Condition ($productRoot.StartsWith($evidencePrefix, [StringComparison]::OrdinalIgnoreCase)) -Message "Prepared installer root is outside this acceptance evidence directory."
    Require-Value -Condition ($prepared.install -and $prepared.install.RegistryPath -and $prepared.install.ProductCode) -Message "Prepared installer ownership record is incomplete."
    return [pscustomobject]@{ PreparedPath = $preparedPath; ProductRoot = $productRoot; Install = $prepared.install }
}

function Remove-PreparedInstallerOwnership {
    param([object]$Ownership, [string]$Evidence, [string]$LogName)
    $registryPath = [string]$Ownership.Install.RegistryPath
    $productRoot = [string]$Ownership.ProductRoot
    $entries = @(Get-UninstallEntries | Where-Object {
        [string]$_.PSPath -ieq $registryPath -and $_.InstallLocation -and
        [System.IO.Path]::GetFullPath([string]$_.InstallLocation) -ieq [System.IO.Path]::GetFullPath($productRoot)
    })
    Require-Value -Condition ($entries.Count -le 1) -Message "Owned prepared installer registration is ambiguous."
    if ($entries.Count -eq 1) {
        $install = Convert-UninstallEntry -Entry $entries[0] -LogPath (Join-Path $Evidence $LogName) -FallbackRoot $productRoot
        Invoke-Uninstall -InstallEvidence $install -LogPath (Join-Path $Evidence $LogName)
    }
    if (Test-Path -LiteralPath $productRoot) { Remove-Item -LiteralPath $productRoot -Recurse -Force -ErrorAction Stop }
    $remaining = @(Get-UninstallEntries | Where-Object { [string]$_.PSPath -ieq $registryPath })
    Require-Value -Condition ($remaining.Count -eq 0) -Message "Owned prepared installer registration remains."
}

function Get-CleanupRemainingResources {
    param([object]$Cleanup)
    $remaining = New-Object System.Collections.Generic.List[string]
    foreach ($processId in @($Cleanup.remainingOwnedPids)) { $remaining.Add("process:$processId") }
    foreach ($rule in @($Cleanup.firewallRulesRemaining)) { $remaining.Add("firewall:$rule") }
    foreach ($errorMessage in @($Cleanup.errors)) { $remaining.Add([string]$errorMessage) }
    if ($Cleanup.auditPolicyRestored -eq $false) { $remaining.Add("audit policy not restored") }
    if ($Cleanup.configRestored -eq $false) { $remaining.Add("launcher configuration not restored") }
    if ($Cleanup.sharedDataUnchanged -eq $false) { $remaining.Add("shared/profile data changed") }
    if ($Cleanup.uninstallComplete -eq $false) { $remaining.Add("owned installer not removed") }
    return $remaining.ToArray()
}

function Get-ProcessSnapshot {
    param([int[]]$ProcessIds)
    $wanted = New-Object 'System.Collections.Generic.HashSet[int]'
    foreach ($id in $ProcessIds) { [void]$wanted.Add($id) }
    return @(
        Get-CimInstance Win32_Process -ErrorAction Stop |
            Where-Object { $wanted.Contains([int]$_.ProcessId) } |
            ForEach-Object {
                [ordered]@{
                    pid = [int]$_.ProcessId
                    parentPid = [int]$_.ParentProcessId
                    image = [string]$_.ExecutablePath
                    commandLine = [string]$_.CommandLine
                    creationDate = if ($_.CreationDate) { ([DateTime]$_.CreationDate).ToUniversalTime().ToString("o") } else { $null }
                }
            }
    )
}

function Get-OwnedProcessTree {
    param([int]$RootPid, [string]$ProductRoot)
    $all = @(Get-CimInstance Win32_Process -ErrorAction Stop)
    $ids = New-Object 'System.Collections.Generic.HashSet[int]'
    [void]$ids.Add($RootPid)
    $changed = $true
    while ($changed) {
        $changed = $false
        foreach ($process in $all) {
            if ($ids.Contains([int]$process.ParentProcessId) -and -not $ids.Contains([int]$process.ProcessId)) {
                [void]$ids.Add([int]$process.ProcessId)
                $changed = $true
            }
        }
    }
    $prefix = [System.IO.Path]::GetFullPath($ProductRoot).TrimEnd('\') + '\'
    return @(
        $all | Where-Object {
            $ids.Contains([int]$_.ProcessId) -and (
                [int]$_.ProcessId -eq $RootPid -or
                ($_.ExecutablePath -and [System.IO.Path]::GetFullPath([string]$_.ExecutablePath).StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase))
            )
        } | ForEach-Object { [int]$_.ProcessId }
    )
}

function Get-ExternalConnections {
    param([int[]]$ProcessIds)
    $wanted = New-Object 'System.Collections.Generic.HashSet[int]'
    foreach ($id in $ProcessIds) { [void]$wanted.Add($id) }
    return @(
        Get-NetTCPConnection -ErrorAction Stop |
            Where-Object {
                $wanted.Contains([int]$_.OwningProcess) -and
                $_.RemoteAddress -and
                $_.RemoteAddress -notin @("0.0.0.0", "127.0.0.1", "::", "::1") -and
                -not $_.RemoteAddress.StartsWith("127.")
            } | ForEach-Object {
                [ordered]@{
                    pid = [int]$_.OwningProcess
                    state = [string]$_.State
                    remoteAddress = [string]$_.RemoteAddress
                    remotePort = [int]$_.RemotePort
                }
            }
    )
}

function Get-SharedDataSnapshot {
    $paths = New-Object System.Collections.Generic.List[string]
    if ($env:PUBLIC) {
        $paths.Add((Join-Path $env:PUBLIC "Documents\LizzieYzyNext"))
        $paths.Add((Join-Path $env:PUBLIC "LizzieYzyNext"))
    }
    if ($env:PROGRAMDATA) { $paths.Add((Join-Path $env:PROGRAMDATA "LizzieYzyNext")) }
    if ($env:USERPROFILE) {
        $paths.Add((Join-Path $env:USERPROFILE ".lizzieyzy-next"))
        $paths.Add((Join-Path $env:USERPROFILE ".lizzieyzy-next-foxuid"))
    }
    $snapshot = [ordered]@{}
    foreach ($path in ($paths | Select-Object -Unique)) {
        if (Test-Path -LiteralPath $path) {
            $rows = @(Get-ChildItem -LiteralPath $path -File -Recurse -Force -ErrorAction Stop | Sort-Object FullName | ForEach-Object {
                "{0}|{1}|{2}" -f $_.FullName.Substring($path.Length), $_.Length, (Get-FileSha256 -Path $_.FullName)
            })
            $bytes = [System.Text.Encoding]::UTF8.GetBytes(($rows -join "`n"))
            $sha = [System.Security.Cryptography.SHA256]::Create()
            try { $digest = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant() } finally { $sha.Dispose() }
            $snapshot[$path] = $digest
        }
        else {
            $snapshot[$path] = $null
        }
    }
    return $snapshot
}

function Add-FirewallBoundary {
    param([object]$Layout, [string]$Token, [System.Collections.Generic.List[string]]$Rules)
    Assert-Administrator -Operation "Offline acceptance firewall isolation"
    $executables = @($Layout.Launcher) + @(Get-ChildItem -LiteralPath $Layout.Root -Filter "*.exe" -File -Recurse | ForEach-Object { $_.FullName })
    $remoteRanges = @("0.0.0.0-126.255.255.255", "128.0.0.0-255.255.255.255", "::2-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")
    foreach ($executable in ($executables | Select-Object -Unique)) {
        $name = "LizzieYzy-Acceptance-$Token-$($Rules.Count)"
        New-NetFirewallRule -DisplayName $name -Direction Outbound -Action Block -Program $executable -Profile Any -RemoteAddress $remoteRanges | Out-Null
        $Rules.Add($name)
    }
}

function Remove-FirewallBoundary {
    param([string[]]$Rules)
    $errors = New-Object System.Collections.Generic.List[string]
    foreach ($rule in $Rules) {
        try { Remove-NetFirewallRule -DisplayName $rule -ErrorAction Stop }
        catch { $errors.Add("$rule`: $($_.Exception.Message)") }
    }
    if ($errors.Count -gt 0) { throw "Unable to remove all acceptance firewall rules: $($errors -join '; ')" }
}

function Get-RemainingFirewallRules {
    param([string[]]$Rules)
    if ($Rules.Count -eq 0) { return @() }
    $existing = @(Get-NetFirewallRule -PolicyStore ActiveStore -ErrorAction Stop | ForEach-Object { [string]$_.DisplayName })
    return @($Rules | Where-Object { $existing -contains $_ })
}

function Get-SecurityDenyEvents {
    try { return @(Get-WinEvent -FilterHashtable @{ LogName = "Security"; Id = 5157 } -ErrorAction Stop) }
    catch {
        if ($_.FullyQualifiedErrorId -like "NoMatchingEventsFound*") { return @() }
        throw
    }
}

function Start-NetworkAuditBoundary {
    param([string]$Evidence)
    $backup = Join-Path $Evidence "audit-policy-backup.csv"
    & auditpol.exe /backup "/file:$backup" | Out-Null
    Require-Value -Condition ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $backup -PathType Leaf)) -Message "Unable to back up Windows audit policy for offline evidence."
    $audit = [ordered]@{ backupPath = $backup; startRecordId = 0; restored = $false }
    try {
        & auditpol.exe /set '/subcategory:{0CCE9226-69AE-11D9-BED3-505054503030}' /failure:enable | Out-Null
        Require-Value -Condition ($LASTEXITCODE -eq 0) -Message "Unable to enable Windows Filtering Platform failure auditing."
        $latest = @(Get-SecurityDenyEvents | Sort-Object RecordId -Descending | Select-Object -First 1)
        $audit.startRecordId = if ($latest.Count -eq 1) { [long]$latest[0].RecordId } else { 0 }
        return $audit
    }
    catch {
        $failure = $_
        try { Restore-NetworkAuditBoundary -Audit $audit }
        catch { throw "$($failure.Exception.Message) Audit-policy rollback failed: $($_.Exception.Message)" }
        throw $failure
    }
}

function Restore-NetworkAuditBoundary {
    param([object]$Audit)
    if (-not $Audit -or -not $Audit.backupPath) { return }
    & auditpol.exe /restore "/file:$($Audit.backupPath)" | Out-Null
    Require-Value -Condition ($LASTEXITCODE -eq 0) -Message "Unable to restore Windows audit policy."
    $Audit.restored = $true
}

function Get-ExecutableIdentityVariants {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return @() }
    $normalized = $Path.Replace('/', '\').Trim().ToLowerInvariant()
    if ($normalized -notmatch '^[a-z]:\\') { return @($normalized) }
    $full = [System.IO.Path]::GetFullPath($normalized).ToLowerInvariant()
    $variants = New-Object System.Collections.Generic.List[string]
    $variants.Add($full)
    if (-not ([System.Management.Automation.PSTypeName]'WindowsAcceptance.PathIdentity').Type) {
        Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;
using System.Text;
namespace WindowsAcceptance {
  public static class PathIdentity {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern uint QueryDosDevice(string deviceName, StringBuilder targetPath, int maxLength);
  }
}
'@
    }
    $drive = $full.Substring(0, 2)
    $buffer = New-Object System.Text.StringBuilder(1024)
    $length = [WindowsAcceptance.PathIdentity]::QueryDosDevice($drive, $buffer, $buffer.Capacity)
    if ($length -gt 0) { $variants.Add(($buffer.ToString() + $full.Substring(2)).ToLowerInvariant()) }
    return $variants.ToArray()
}

function Get-BlockedConnectionEvents {
    param([long]$AfterRecordId, [string]$ProductRoot)
    $packagedExecutables = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($executable in @(Get-ChildItem -LiteralPath $ProductRoot -Filter "*.exe" -File -Recurse -ErrorAction Stop)) {
        foreach ($identity in @(Get-ExecutableIdentityVariants -Path $executable.FullName)) { [void]$packagedExecutables.Add($identity) }
    }
    return @(
        Get-SecurityDenyEvents |
            Where-Object { [long]$_.RecordId -gt $AfterRecordId } |
            ForEach-Object {
                $values = [ordered]@{}
                $xml = [xml]$_.ToXml()
                foreach ($data in $xml.Event.EventData.Data) { $values[[string]$data.Name] = [string]$data.'#text' }
                $pidText = [string]$values.ProcessID
                $pidValue = if ($pidText.StartsWith("0x")) { [Convert]::ToInt32($pidText.Substring(2), 16) } elseif ($pidText) { [int]$pidText } else { 0 }
                $ownedApplication = @((Get-ExecutableIdentityVariants -Path ([string]$values.Application)) | Where-Object { $packagedExecutables.Contains($_) }).Count -gt 0
                if ($ownedApplication -and $values.DestAddress -notin @("0.0.0.0", "127.0.0.1", "::", "::1") -and -not ([string]$values.DestAddress).StartsWith("127.")) {
                    [ordered]@{ recordId = [long]$_.RecordId; timeCreated = $_.TimeCreated.ToUniversalTime().ToString("o"); pid = $pidValue; application = $values.Application; destinationAddress = $values.DestAddress; destinationPort = $values.DestPort }
                }
            }
    )
}

function Get-PackagedJvmEvidence {
    param([int[]]$ProcessIds, [string]$JvmDll)
    $expected = [System.IO.Path]::GetFullPath($JvmDll)
    foreach ($processId in $ProcessIds) {
        $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if (-not $process) { continue }
        foreach ($module in @($process.Modules)) {
            if ($module.FileName -and [System.IO.Path]::GetFullPath([string]$module.FileName) -ieq $expected) {
                return [ordered]@{ pid = [int]$processId; modulePath = $expected; moduleSha256 = Get-FileSha256 -Path $expected }
            }
        }
    }
    throw "No owned launcher process has loaded the packaged JVM module: $expected"
}

function Add-WorkDirectoryOption {
    param([string]$ConfigPath, [string]$DataRoot)
    $bytes = [System.IO.File]::ReadAllBytes($ConfigPath)
    $line = "java-options=-Dlizzie.work.dir=$DataRoot"
    $content = [System.IO.File]::ReadAllText($ConfigPath)
    if ($content -notmatch [regex]::Escape($line)) {
        $temporary = $ConfigPath + "." + [guid]::NewGuid().ToString("N") + ".tmp"
        $backup = $temporary + ".bak"
        try {
            [System.IO.File]::WriteAllText($temporary, $content + [Environment]::NewLine + $line + [Environment]::NewLine, $script:Utf8NoBom)
            [System.IO.File]::Replace($temporary, $ConfigPath, $backup, $true)
        }
        finally {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
        }
    }
    return [Convert]::ToBase64String($bytes)
}

function Restore-ConfigBytes {
    param([string]$ConfigPath, [string]$Base64)
    if ($ConfigPath -and $Base64) {
        $temporary = $ConfigPath + "." + [guid]::NewGuid().ToString("N") + ".restore"
        $backup = $temporary + ".bak"
        try {
            [System.IO.File]::WriteAllBytes($temporary, [Convert]::FromBase64String($Base64))
            [System.IO.File]::Replace($temporary, $ConfigPath, $backup, $true)
        }
        finally {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-OwnedStartupEngine {
    param([object]$Layout, [object[]]$Processes)
    if ($Layout.Backend -eq "none") { return $true }
    if (-not $Layout.Engine) { return $false }
    $expected = [System.IO.Path]::GetFullPath([string]$Layout.Engine)
    $matches = @($Processes | Where-Object {
        $_.image -and [System.IO.Path]::GetFullPath([string]$_.image) -ieq $expected
    })
    return $matches.Count -eq 1
}

function Start-PreparedProduct {
    param([object]$PreparedIdentity, [string]$OutputRunJson, [string]$ScenarioId, [string]$Evidence, [bool]$Offline)
    $candidateClass = [string]$PreparedIdentity.Candidate.Value.artifact.class
    Require-Value -Condition ($candidateClass -in @("portable-product", "installer-product", "core-update")) -Message "Only prepared runnable products or an applied core update can be started."
    Require-Value -Condition ($script:ScenarioIds -contains $ScenarioId) -Message "Unknown Windows acceptance scenario: $ScenarioId"
    Require-Value -Condition (-not (Test-Path -LiteralPath $OutputRunJson)) -Message "run.json already exists: $OutputRunJson"
    $layout = $PreparedIdentity.Layout
    $portable = $candidateClass -in @("portable-product", "core-update")
    $dataRoot = if ($portable) { Join-Path $layout.Root "user-data" } else { Join-Path $Evidence "isolated data 验收" }
    if ($Offline) { Assert-Administrator -Operation "Offline acceptance firewall and audit isolation" }
    New-Item -ItemType Directory -Force -Path $dataRoot | Out-Null
    $configBackup = $null
    $firewallRuleList = New-Object System.Collections.Generic.List[string]
    $networkAudit = $null
    $sharedBefore = Get-SharedDataSnapshot
    $token = [guid]::NewGuid().ToString("N")
    $process = $null
    $ownedHistory = New-Object 'System.Collections.Generic.HashSet[int]'
    $processHistory = New-Object System.Collections.Generic.List[object]
    $seenIncarnations = New-Object 'System.Collections.Generic.HashSet[string]'
    try {
        if ($Offline) { $networkAudit = Start-NetworkAuditBoundary -Evidence $Evidence }
        if (-not $portable) { $configBackup = Add-WorkDirectoryOption -ConfigPath $layout.Config -DataRoot $dataRoot }
        if ($Offline) { Add-FirewallBoundary -Layout $layout -Token $token -Rules $firewallRuleList }
        $process = Start-Process -FilePath $layout.Launcher -WorkingDirectory $layout.Root -PassThru
        $deadline = (Get-Date).AddSeconds($WaitSeconds)
        $ready = $false
        $readinessState = $null
        $windowPid = $null
        $appLog = Join-Path $dataRoot "logs\app.log"
        $currentOwned = @([int]$process.Id)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 250
            $process.Refresh()
            if ($process.HasExited) { throw "Packaged launcher exited before readiness with code $($process.ExitCode)." }
            $currentOwned = @(Get-OwnedProcessTree -RootPid $process.Id -ProductRoot $layout.Root)
            Require-Value -Condition ($currentOwned.Count -gt 0) -Message "Packaged launcher process tree disappeared before readiness."
            foreach ($processId in $currentOwned) { [void]$ownedHistory.Add([int]$processId) }
            $startupSnapshots = @(Get-ProcessSnapshot -ProcessIds $currentOwned)
            foreach ($snapshot in $startupSnapshots) {
                $key = "$($snapshot.pid)|$($snapshot.creationDate)"
                if ($seenIncarnations.Add($key)) { $processHistory.Add($snapshot) }
            }
            $processes = @(Get-Process -Id $currentOwned -ErrorAction SilentlyContinue)
            $window = $processes | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
            if ($window) { $windowPid = [int]$window.Id }
            $hasConfig = (Test-Path -LiteralPath (Join-Path $dataRoot "config.txt") -PathType Leaf) -and (Test-Path -LiteralPath (Join-Path $dataRoot "persist") -PathType Leaf)
            $logText = if (Test-Path -LiteralPath $appLog -PathType Leaf) { Get-Content -LiteralPath $appLog -Raw -ErrorAction Stop } else { "" }
            $hasReadyLog = $logText -match 'application ready'
            $hasRepairLog = $logText -match '(?i)(no[- ]engine|engine unavailable|backend unavailable|repair required)'
            # The window becomes ready before EngineManager launches KataGo. Freeze the
            # strict PID baseline only after the expected owned engine exists. This is
            # process-start evidence, not proof of inference or model readiness.
            $hasStartupEngine = Test-OwnedStartupEngine -Layout $layout -Processes $startupSnapshots
            $explicitRepair = $ScenarioId -eq "variant-launch" -and $hasRepairLog
            if ($windowPid -and $hasConfig -and (($hasReadyLog -and $hasStartupEngine) -or $explicitRepair)) {
                $ready = $true
                $readinessState = if ($hasReadyLog -and $hasStartupEngine) { "application-ready" } else { "explicit-repair" }
                break
            }
        }
        if (-not $ready) { throw "Timed out waiting for visible window, application-ready/repair log, owned startup engine, config.txt and persist." }
        # A live session only proves packaged process/readiness identity. An intentionally
        # engine-free editor may be ready without a repair condition; keep the stricter
        # repair/no-engine-state requirement for terminal distribution scenarios.
        if ($layout.Backend -eq "none" -and $ScenarioId -ne "live-session") { Require-Value -Condition ($readinessState -eq "explicit-repair") -Message "No-engine product did not expose the required visible repair/no-engine state." }
        $currentOwned = @(Get-OwnedProcessTree -RootPid $process.Id -ProductRoot $layout.Root)
        foreach ($processId in $currentOwned) { [void]$ownedHistory.Add([int]$processId) }
        foreach ($snapshot in @(Get-ProcessSnapshot -ProcessIds $currentOwned)) {
            $key = "$($snapshot.pid)|$($snapshot.creationDate)"
            if ($seenIncarnations.Add($key)) { $processHistory.Add($snapshot) }
        }
        $activeSnapshots = @(Get-ProcessSnapshot -ProcessIds $currentOwned)
        $launcherSnapshots = @($activeSnapshots | Where-Object { $_.pid -eq [int]$process.Id })
        Require-Value -Condition ($launcherSnapshots.Count -eq 1) -Message "Packaged launcher process identity is unavailable or ambiguous."
        Require-Value -Condition ($launcherSnapshots[0].image -and ([System.IO.Path]::GetFullPath([string]$launcherSnapshots[0].image) -ieq [System.IO.Path]::GetFullPath($layout.Launcher))) -Message "Packaged launcher process image does not match the prepared launcher."
        Require-Value -Condition (-not [string]::IsNullOrWhiteSpace([string]$launcherSnapshots[0].commandLine)) -Message "Packaged launcher command line evidence is unavailable."
        $jvmEvidence = Get-PackagedJvmEvidence -ProcessIds $currentOwned -JvmDll $layout.JvmDll
        $connections = @(Get-ExternalConnections -ProcessIds @($ownedHistory))
        $blockedAttempts = if ($Offline) { @(Get-BlockedConnectionEvents -AfterRecordId ([long]$networkAudit.startRecordId) -ProductRoot $layout.Root) } else { @() }
        if ($Offline) {
            Require-Value -Condition ($connections.Count -eq 0) -Message "Owned process established an external connection while offline."
            Require-Value -Condition ($blockedAttempts.Count -eq 0) -Message "Owned process attempted an external connection while offline."
        }
        $run = [ordered]@{
            schemaVersion = $script:SchemaVersion
            state = "RUNNING"
            scenario = $ScenarioId
            startedAt = Get-UtcTimestamp
            stoppedAt = $null
            candidate = [ordered]@{
                path = $PreparedIdentity.Candidate.Path; sha256 = $PreparedIdentity.Candidate.Hash
                artifactSha256 = [string]$PreparedIdentity.Candidate.Value.artifact.sha256
                provenancePath = [string]$PreparedIdentity.Candidate.Value.provenance.path; provenanceSha256 = [string]$PreparedIdentity.Candidate.Value.provenance.sha256
                targetSha = [string]$PreparedIdentity.Candidate.Value.targetSha; releaseTag = [string]$PreparedIdentity.Candidate.Value.releaseTag
                key = [string]$PreparedIdentity.Candidate.Value.artifact.key; name = [string]$PreparedIdentity.Candidate.Value.artifact.name; class = $candidateClass
            }
            preparedPath = $PreparedIdentity.PreparedPath
            install = if ($candidateClass -eq "installer-product" -and $PreparedIdentity.PSObject.Properties["Prepared"]) { $PreparedIdentity.Prepared.install } else { $null }
            productRoot = $layout.Root
            launcher = [ordered]@{ path = $layout.Launcher; sha256 = $layout.LauncherSha256; pid = [int]$process.Id; windowPid = $windowPid; process = $launcherSnapshots[0] }
            runtime = [ordered]@{ path = $layout.Runtime; sha256 = $layout.RuntimeSha256; version = $layout.RuntimeVersion; architecture = "x86_64"; jvmModule = $jvmEvidence }
            jar = [ordered]@{ path = $layout.Jar; sha256 = $layout.JarSha256 }
            engine = [ordered]@{ path = $layout.Engine; sha256 = $layout.EngineSha256; configPath = $layout.EngineConfig; configSha256 = $layout.EngineConfigSha256; modelPath = $layout.Model; modelSha256 = $layout.ModelSha256 }
            backend = [ordered]@{ expected = $layout.Backend; markerPath = $layout.BackendMarker; markerSha256 = $layout.BackendMarkerSha256; markerValue = $layout.BackendMarkerValue; readinessState = $readinessState }
            components = [ordered]@{ installedManifestPath = $layout.InstalledManifest; installedManifestSha256 = $layout.InstalledManifestSha256; jcefPath = $layout.Jcef; jcefSha256 = $layout.JcefSha256; readBoardPath = $layout.ReadBoard; readBoardSha256 = $layout.ReadBoardSha256 }
            dataRoot = [ordered]@{ path = $dataRoot; selection = if ($portable) { "portable-marker" } else { "explicit-work-dir" }; explicitOverride = (-not $portable) }
            processes = $processHistory.ToArray()
            activeProcesses = @($activeSnapshots)
            ownedPids = @($ownedHistory | ForEach-Object { [int]$_ })
            applicationLog = $appLog
            network = [ordered]@{ offline = $Offline; firewallRules = $firewallRuleList.ToArray(); audit = $networkAudit; blockedAttempts = @($blockedAttempts); externalConnections = $connections }
            sharedDataBefore = $sharedBefore
            configRestore = [ordered]@{ path = if ($configBackup) { $layout.Config } else { $null }; bytesBase64 = $configBackup }
            cleanup = [ordered]@{ complete = $false; remainingOwnedPids = @($ownedHistory | ForEach-Object { [int]$_ }); firewallRulesRemaining = $firewallRuleList.ToArray(); auditPolicyRestored = (-not $Offline); configRestored = (-not [bool]$configBackup); sharedDataUnchanged = $null; uninstallComplete = $null; identityErrors = @(); errors = @() }
        }
        Write-JsonAtomic -Path $OutputRunJson -Value $run
        return $run
    }
    catch {
        $failure = $_
        $cleanupErrors = New-Object System.Collections.Generic.List[string]
        $cleanupPids = @()
        if ($process) {
            try { $cleanupPids = @(Get-OwnedProcessTree -RootPid $process.Id -ProductRoot $layout.Root) } catch { $cleanupErrors.Add("process discovery: $($_.Exception.Message)") }
            foreach ($processId in ($cleanupPids | Sort-Object -Descending)) {
                try { Stop-Process -Id $processId -Force -ErrorAction Stop } catch { $cleanupErrors.Add("process $processId`: $($_.Exception.Message)") }
            }
        }
        $survivors = @()
        if ($cleanupPids.Count -gt 0) {
            $survivors = @(Get-Process -Id $cleanupPids -ErrorAction SilentlyContinue | ForEach-Object { [int]$_.Id })
        }
        try { Remove-FirewallBoundary -Rules $firewallRuleList.ToArray() } catch { $cleanupErrors.Add("firewall cleanup: $($_.Exception.Message)") }
        $firewallRemaining = @()
        try { $firewallRemaining = @(Get-RemainingFirewallRules -Rules $firewallRuleList.ToArray()) } catch { $cleanupErrors.Add("firewall verification: $($_.Exception.Message)") }
        $auditRestored = -not $Offline
        if ($networkAudit) { try { Restore-NetworkAuditBoundary -Audit $networkAudit; $auditRestored = $true } catch { $cleanupErrors.Add("audit policy restore: $($_.Exception.Message)") } }
        $configRestored = -not [bool]$configBackup
        if ($configBackup) { try { Restore-ConfigBytes -ConfigPath $layout.Config -Base64 $configBackup; $configRestored = $true } catch { $cleanupErrors.Add("launcher config restore: $($_.Exception.Message)") } }
        $sharedUnchanged = $false
        try { $sharedUnchanged = (($sharedBefore | ConvertTo-Json -Depth 20 -Compress) -eq ((Get-SharedDataSnapshot) | ConvertTo-Json -Depth 20 -Compress)) } catch { $cleanupErrors.Add("shared data verification: $($_.Exception.Message)") }
        $startupCleanup = [ordered]@{
            complete = ($survivors.Count -eq 0 -and $firewallRemaining.Count -eq 0 -and $auditRestored -and $configRestored -and $sharedUnchanged -and $cleanupErrors.Count -eq 0)
            remainingOwnedPids = @($survivors); firewallRulesRemaining = @($firewallRemaining); auditPolicyRestored = $auditRestored; configRestored = $configRestored
            sharedDataUnchanged = $sharedUnchanged; uninstallComplete = $null; identityErrors = @(); errors = $cleanupErrors.ToArray()
        }
        $failure.Exception.Data["StartupCleanup"] = $startupCleanup
        throw $failure
    }
}

function Get-ProcessCreationIdentity {
    param([object]$Value)
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace([string]$Value)) { return $null }
    try {
        if ($Value -is [DateTimeOffset]) { return ([DateTimeOffset]$Value).UtcDateTime.Ticks }
        if ($Value -is [DateTime]) { return ([DateTime]$Value).ToUniversalTime().Ticks }
        $parsed = [DateTimeOffset]::Parse([string]$Value, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind)
        return $parsed.UtcDateTime.Ticks
    }
    catch { return $null }
}

function Test-ProcessSnapshotMatch {
    param([object]$Expected, [object]$Actual)
    if (-not $Expected -or -not $Actual) { return $false }
    $expectedCreation = Get-ProcessCreationIdentity -Value $Expected.creationDate
    $actualCreation = Get-ProcessCreationIdentity -Value $Actual.creationDate
    return ($null -ne $expectedCreation -and $expectedCreation -eq $actualCreation -and [int]$Expected.pid -eq [int]$Actual.pid -and [string]$Expected.image -and [string]$Actual.image -and [System.IO.Path]::GetFullPath([string]$Expected.image) -ieq [System.IO.Path]::GetFullPath([string]$Actual.image))
}

function Assert-LiveRunIdentity {
    param([object]$Run, [bool]$RequireRunning)
    Require-Value -Condition ($Run.schemaVersion -eq 1) -Message "Unsupported run record schema."
    if ($RequireRunning) { Require-Value -Condition ($Run.state -eq "RUNNING") -Message "Run is not RUNNING." }
    $identity = Assert-CandidateIdentity -Path ([string]$Run.candidate.path) -AllowedClasses @("portable-product", "installer-product", "core-update")
    Require-Value -Condition ($identity.Hash -eq [string]$Run.candidate.sha256) -Message "Run candidate hash drift detected."
    $packagedJvmPath = [System.IO.Path]::GetFullPath((Join-Path ([string]$Run.productRoot) "runtime\bin\server\jvm.dll"))
    Require-Value -Condition ([string]$Run.runtime.jvmModule.modulePath -and [System.IO.Path]::GetFullPath([string]$Run.runtime.jvmModule.modulePath) -ieq $packagedJvmPath) -Message "Run JVM module path is not the packaged JVM path."
    foreach ($item in @(
        @{ path = $Run.launcher.path; hash = $Run.launcher.sha256; label = "launcher" }, @{ path = $Run.runtime.path; hash = $Run.runtime.sha256; label = "runtime" },
        @{ path = $Run.runtime.jvmModule.modulePath; hash = $Run.runtime.jvmModule.moduleSha256; label = "JVM module" }, @{ path = $Run.jar.path; hash = $Run.jar.sha256; label = "shaded JAR" },
        @{ path = $Run.engine.path; hash = $Run.engine.sha256; label = "engine" }, @{ path = $Run.engine.configPath; hash = $Run.engine.configSha256; label = "engine config" },
        @{ path = $Run.engine.modelPath; hash = $Run.engine.modelSha256; label = "model" }, @{ path = $Run.backend.markerPath; hash = $Run.backend.markerSha256; label = "backend marker" },
        @{ path = $Run.components.installedManifestPath; hash = $Run.components.installedManifestSha256; label = "installed manifest" }
    )) { if ($item.path) { Require-Value -Condition ((Get-FileSha256 -Path ([string]$item.path)) -eq [string]$item.hash) -Message "Run $($item.label) hash drift detected." } }
    Require-Value -Condition ((Get-DirectoryTreeSha256 -Root ([string]$Run.components.jcefPath)) -eq [string]$Run.components.jcefSha256) -Message "Run JCEF closure drift detected."
    Require-Value -Condition ((Get-DirectoryTreeSha256 -Root ([string]$Run.components.readBoardPath)) -eq [string]$Run.components.readBoardSha256) -Message "Run ReadBoard closure drift detected."
    if ($RequireRunning) {
        $expected = @($Run.activeProcesses)
        $current = @(Get-ProcessSnapshot -ProcessIds @($expected | ForEach-Object { [int]$_.pid }))
        Require-Value -Condition ($current.Count -eq $expected.Count) -Message "Recorded active process set lost a process."
        foreach ($snapshot in $expected) {
            $matches = @($current | Where-Object { Test-ProcessSnapshotMatch -Expected $snapshot -Actual $_ })
            Require-Value -Condition ($matches.Count -eq 1) -Message "Recorded process incarnation drift detected for PID $($snapshot.pid)."
        }
        $ownedNow = @(Get-OwnedProcessTree -RootPid ([int]$Run.launcher.pid) -ProductRoot ([string]$Run.productRoot))
        $unexpected = @($ownedNow | Where-Object { @($Run.ownedPids) -notcontains $_ })
        Require-Value -Condition ($unexpected.Count -eq 0) -Message "Run process tree gained unrecorded PIDs: $($unexpected -join ', ')."
        $launcherCurrent = @($current | Where-Object { $_.pid -eq [int]$Run.launcher.pid })[0]
        Require-Value -Condition ([string]$launcherCurrent.commandLine -ceq [string]$Run.launcher.process.commandLine) -Message "Recorded launcher command line drift detected."
        $jvmPid = [int]$Run.runtime.jvmModule.pid
        $recordedJvm = @($expected | Where-Object { [int]$_.pid -eq $jvmPid })
        Require-Value -Condition ($jvmPid -gt 0 -and $recordedJvm.Count -eq 1 -and @($Run.ownedPids) -contains $jvmPid) -Message "Recorded JVM host is not a unique Start-owned active process."
        Require-Value -Condition ($ownedNow -contains $jvmPid) -Message "Recorded JVM host is no longer owned by the packaged launcher."
        $jvm = Get-PackagedJvmEvidence -ProcessIds @($jvmPid) -JvmDll ([string]$Run.runtime.jvmModule.modulePath)
        Require-Value -Condition ([string]$jvm.moduleSha256 -eq [string]$Run.runtime.jvmModule.moduleSha256) -Message "Loaded packaged JVM identity drift detected."
        Require-Value -Condition (Test-Path -LiteralPath ([string]$Run.applicationLog) -PathType Leaf) -Message "Application readiness evidence is missing."
        $logText = Get-Content -LiteralPath ([string]$Run.applicationLog) -Raw -ErrorAction Stop
        $readyPattern = if ([string]$Run.backend.readinessState -eq "explicit-repair") { '(?i)(no[- ]engine|engine unavailable|backend unavailable|repair required)' } else { 'application ready' }
        Require-Value -Condition ($logText -match $readyPattern) -Message "Application readiness evidence drift detected."
        Require-Value -Condition ((Test-Path -LiteralPath (Join-Path ([string]$Run.dataRoot.path) "config.txt") -PathType Leaf) -and (Test-Path -LiteralPath (Join-Path ([string]$Run.dataRoot.path) "persist") -PathType Leaf)) -Message "Effective data-root evidence drift detected."
    }
    return $identity
}

function Invoke-Start {
    $evidence = Resolve-FullPath -Path $EvidenceDir -Label "evidence directory" -MustExist
    Require-Value -Condition ($Scenario -eq "live-session") -Message "Start currently accepts only scenario live-session; use Run for terminal one-shot scenarios."
    $prepared = Read-PreparedIdentity -CandidatePath $CandidateJson
    $output = Join-Path $evidence "run.json"
    Require-Value -Condition (-not (Test-Path -LiteralPath $output)) -Message "run.json already exists: $output"
    try { [void](Start-PreparedProduct -PreparedIdentity $prepared -OutputRunJson $output -ScenarioId $Scenario -Evidence $evidence -Offline $false) }
    catch {
        $failure = $_
        $startupCleanup = $failure.Exception.Data["StartupCleanup"]
        if ($prepared.Candidate.Value.artifact.class -eq "installer-product" -and -not $KeepPreparedProduct) {
            try {
                Invoke-Uninstall -InstallEvidence $prepared.Prepared.install -LogPath (Join-Path $evidence "failed-start-uninstall.log")
                if (Test-Path -LiteralPath ([string]$prepared.Prepared.productRoot)) { Remove-Item -LiteralPath ([string]$prepared.Prepared.productRoot) -Recurse -Force -ErrorAction Stop }
                $remainingInstall = @(Get-UninstallEntries | Where-Object { [string]$_.PSPath -ieq [string]$prepared.Prepared.install.RegistryPath })
                Require-Value -Condition ($remainingInstall.Count -eq 0) -Message "Owned prepared installer registration remains."
                if ($startupCleanup) { $startupCleanup.uninstallComplete = $true }
            }
            catch {
                if ($startupCleanup) {
                    $startupCleanup.uninstallComplete = $false
                    $startupCleanup.errors = @($startupCleanup.errors) + "failed-start uninstall: $($_.Exception.Message)"
                    $startupCleanup.complete = $false
                }
                else { throw "$($failure.Exception.Message) Failed-start uninstall failed: $($_.Exception.Message)" }
            }
        }
        if ($startupCleanup) {
            $startupCleanup.complete = ([bool]$startupCleanup.complete -and ($null -eq $startupCleanup.uninstallComplete -or [bool]$startupCleanup.uninstallComplete) -and @($startupCleanup.errors).Count -eq 0)
            Write-JsonAtomic -Path (Join-Path $evidence "startup-cleanup.json") -Value $startupCleanup
            if (-not $startupCleanup.complete) { throw "$($failure.Exception.Message) Startup cleanup is incomplete: $(@($startupCleanup.errors) -join '; ')" }
        }
        throw $failure
    }
    Write-Host "RUNNING $output"
}

function Invoke-Status {
    $path = Resolve-FullPath -Path $RunJson -Label "run.json" -MustExist
    $run = Read-JsonFile -Path $path -Label "run.json"
    [void](Assert-LiveRunIdentity -Run $run -RequireRunning $true)
    $owned = @(Get-OwnedProcessTree -RootPid ([int]$run.launcher.pid) -ProductRoot ([string]$run.productRoot))
    $connections = @(Get-ExternalConnections -ProcessIds $owned)
    if ($run.network.offline) {
        $blocked = @(Get-BlockedConnectionEvents -AfterRecordId ([long]$run.network.audit.startRecordId) -ProductRoot ([string]$run.productRoot))
        Require-Value -Condition ($connections.Count -eq 0) -Message "External connection drift detected."
        Require-Value -Condition ($blocked.Count -eq 0) -Message "External connection attempt detected."
    }
    Write-Host "RUNNING launcherPid=$($run.launcher.pid) ownedPids=$($owned -join ',')"
}

function Stop-OwnedRun {
    param([object]$Run, [string]$Path, [switch]$SkipUninstall)
    Require-Value -Condition ($Run.schemaVersion -eq 1 -and $Run.state -eq "RUNNING") -Message "Run record is not a supported RUNNING record."
    $identityErrors = New-Object System.Collections.Generic.List[string]
    try { [void](Assert-LiveRunIdentity -Run $Run -RequireRunning $false) } catch { $identityErrors.Add($_.Exception.Message) }
    $cleanupErrors = New-Object System.Collections.Generic.List[string]
    $rootPid = [int]$Run.launcher.pid
    $owned = New-Object 'System.Collections.Generic.HashSet[int]'
    foreach ($processId in @($Run.ownedPids)) { [void]$owned.Add([int]$processId) }
    $recordedCurrent = @(Get-ProcessSnapshot -ProcessIds @($owned))
    $rootIncarnationOwned = @($recordedCurrent | Where-Object { Test-ProcessSnapshotMatch -Expected $Run.launcher.process -Actual $_ }).Count -eq 1
    $discovered = @()
    if ($rootIncarnationOwned) {
        try { $discovered = @(Get-OwnedProcessTree -RootPid $rootPid -ProductRoot ([string]$Run.productRoot)) } catch { $cleanupErrors.Add("process discovery: $($_.Exception.Message)") }
        foreach ($processId in $discovered) { [void]$owned.Add([int]$processId) }
    }
    $prefix = [System.IO.Path]::GetFullPath([string]$Run.productRoot).TrimEnd('\') + '\'
    $currentSnapshots = @(Get-ProcessSnapshot -ProcessIds @($owned))
    $ownedSnapshots = New-Object System.Collections.Generic.List[object]
    foreach ($snapshot in $currentSnapshots) {
        $recorded = @($Run.processes | Where-Object { Test-ProcessSnapshotMatch -Expected $_ -Actual $snapshot })
        $underRoot = $snapshot.image -and [System.IO.Path]::GetFullPath([string]$snapshot.image).StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)
        if ($recorded.Count -gt 0 -or ($rootIncarnationOwned -and $discovered -contains [int]$snapshot.pid -and $underRoot)) { $ownedSnapshots.Add($snapshot) }
    }
    $ownedAlive = @($ownedSnapshots | ForEach-Object { [int]$_.pid })
    if ($Run.network.offline) {
        try { $Run.network.externalConnections = @(Get-ExternalConnections -ProcessIds $ownedAlive) } catch { $cleanupErrors.Add("network connection evidence: $($_.Exception.Message)") }
    }
    $rootSnapshot = @($ownedSnapshots | Where-Object { $_.pid -eq $rootPid })
    if ($rootSnapshot.Count -eq 1) {
        $root = Get-Process -Id $rootPid -ErrorAction SilentlyContinue
        if ($root -and $root.MainWindowHandle -ne 0) { try { [void]$root.CloseMainWindow() } catch { $cleanupErrors.Add("launcher close: $($_.Exception.Message)") } }
    }
    if ($ownedAlive.Count -gt 0) {
        $deadline = (Get-Date).AddSeconds($StopTimeoutSeconds)
        while ((Get-Date) -lt $deadline -and (Get-Process -Id $ownedAlive -ErrorAction SilentlyContinue)) { Start-Sleep -Milliseconds 250 }
        $remainingSnapshots = @(Get-ProcessSnapshot -ProcessIds $ownedAlive | Where-Object {
            $actual = $_
            @($ownedSnapshots | Where-Object { Test-ProcessSnapshotMatch -Expected $_ -Actual $actual }).Count -eq 1
        })
        foreach ($snapshot in ($remainingSnapshots | Sort-Object pid -Descending)) {
            try { Stop-Process -Id ([int]$snapshot.pid) -Force -ErrorAction Stop } catch { $cleanupErrors.Add("process $($snapshot.pid) cleanup: $($_.Exception.Message)") }
        }
        Start-Sleep -Milliseconds 500
    }
    $survivors = @(
        Get-ProcessSnapshot -ProcessIds $ownedAlive | Where-Object {
            $actual = $_
            @($ownedSnapshots | Where-Object { Test-ProcessSnapshotMatch -Expected $_ -Actual $actual }).Count -eq 1
        } | ForEach-Object { [int]$_.pid }
    )
    if ($Run.network.offline) {
        try { $Run.network.blockedAttempts = @(Get-BlockedConnectionEvents -AfterRecordId ([long]$Run.network.audit.startRecordId) -ProductRoot ([string]$Run.productRoot)) } catch { $cleanupErrors.Add("network evidence: $($_.Exception.Message)") }
    }
    try { Remove-FirewallBoundary -Rules @($Run.network.firewallRules) } catch { $cleanupErrors.Add("firewall cleanup: $($_.Exception.Message)") }
    $firewallRemaining = @()
    try { $firewallRemaining = @(Get-RemainingFirewallRules -Rules @($Run.network.firewallRules)) } catch { $cleanupErrors.Add("firewall verification: $($_.Exception.Message)") }
    $auditRestored = -not [bool]$Run.network.offline
    if ($Run.network.offline) { try { Restore-NetworkAuditBoundary -Audit $Run.network.audit; $auditRestored = $true } catch { $cleanupErrors.Add("audit policy restore: $($_.Exception.Message)") } }
    $configRestored = -not [bool]$Run.configRestore.bytesBase64
    try { Restore-ConfigBytes -ConfigPath ([string]$Run.configRestore.path) -Base64 ([string]$Run.configRestore.bytesBase64); $configRestored = $true } catch { $cleanupErrors.Add("launcher config restore: $($_.Exception.Message)") }
    $sharedUnchanged = $false
    try { $sharedUnchanged = (($Run.sharedDataBefore | ConvertTo-Json -Depth 20 -Compress) -eq ((Get-SharedDataSnapshot) | ConvertTo-Json -Depth 20 -Compress)) } catch { $cleanupErrors.Add("shared data verification: $($_.Exception.Message)") }
    $uninstallComplete = $null
    if (-not $SkipUninstall -and $Run.candidate.class -eq "installer-product" -and -not $KeepPreparedProduct) {
        try {
            $installEvidence = if ($Run.install) { $Run.install } else { (Read-JsonFile -Path ([string]$Run.preparedPath) -Label "prepared identity").install }
            Invoke-Uninstall -InstallEvidence $installEvidence -LogPath (Join-Path (Split-Path -Parent $Path) "uninstall.log")
            if (Test-Path -LiteralPath ([string]$Run.productRoot)) { Remove-Item -LiteralPath ([string]$Run.productRoot) -Recurse -Force -ErrorAction Stop }
            $stillRegistered = @(Get-UninstallEntries | Where-Object { [string]$_.PSPath -ieq [string]$installEvidence.RegistryPath })
            Require-Value -Condition ($stillRegistered.Count -eq 0) -Message "Owned installer registration remains after uninstall."
            $uninstallComplete = $true
        }
        catch { $uninstallComplete = $false; $cleanupErrors.Add("uninstall: $($_.Exception.Message)") }
    }
    $Run.state = "STOPPED"
    $Run.stoppedAt = Get-UtcTimestamp
    $Run.cleanup.remainingOwnedPids = @($survivors)
    $Run.cleanup.firewallRulesRemaining = @($firewallRemaining)
    $Run.cleanup.auditPolicyRestored = $auditRestored
    $Run.cleanup.configRestored = $configRestored
    $Run.cleanup.sharedDataUnchanged = $sharedUnchanged
    $Run.cleanup.uninstallComplete = $uninstallComplete
    Set-JsonProperty -Object $Run.cleanup -Name "identityErrors" -Value $identityErrors.ToArray()
    Set-JsonProperty -Object $Run.cleanup -Name "errors" -Value $cleanupErrors.ToArray()
    $Run.cleanup.complete = ($survivors.Count -eq 0 -and $firewallRemaining.Count -eq 0 -and $auditRestored -and $configRestored -and $sharedUnchanged -and ($null -eq $uninstallComplete -or $uninstallComplete) -and $cleanupErrors.Count -eq 0)
    Write-JsonAtomic -Path $Path -Value $Run
    Require-Value -Condition $Run.cleanup.complete -Message "Run cleanup is incomplete: $($cleanupErrors -join '; ')"
    Require-Value -Condition ($identityErrors.Count -eq 0) -Message "Run identity verification failed before cleanup: $($identityErrors -join '; ')"
    return $Run
}

function Invoke-Stop {
    $path = Resolve-FullPath -Path $RunJson -Label "run.json" -MustExist
    $run = Read-JsonFile -Path $path -Label "run.json"
    [void](Stop-OwnedRun -Run $run -Path $path)
    Write-Host "STOPPED $path"
}

function Get-TreeHashes {
    param([string]$Root)
    $result = [ordered]@{}
    foreach ($file in (Get-ChildItem -LiteralPath $Root -File -Recurse -Force | Sort-Object FullName)) {
        $relative = $file.FullName.Substring($Root.Length + 1).Replace('\', '/')
        $result[$relative] = [ordered]@{ sizeBytes = [long]$file.Length; sha256 = Get-FileSha256 -Path $file.FullName }
    }
    return $result
}

function Invoke-CoreUpdateScenario {
    param([object]$CandidatePrepared, [object]$PriorPrepared, [string]$Evidence)
    Require-Value -Condition ($CandidatePrepared.Candidate.Value.artifact.class -eq "core-update") -Message "core-update-preserve requires a core-update candidate."
    Require-Value -Condition ($PriorPrepared.Candidate.Value.artifact.class -eq "portable-product") -Message "core-update-preserve requires a prior portable candidate."
    $copyRoot = Join-Path $Evidence ("core update target 验收-" + [guid]::NewGuid().ToString("N"))
    Require-Value -Condition (-not (Test-Path -LiteralPath $copyRoot)) -Message "Core update target already exists: $copyRoot"
    $created = $false
    try {
        Copy-Item -LiteralPath $PriorPrepared.Layout.Root -Destination $copyRoot -Recurse
        $created = $true
    $targetRoot = Join-Path $copyRoot ([System.IO.Path]::GetFileName($PriorPrepared.Layout.Root))
    if (-not (Test-Path -LiteralPath $targetRoot)) { $targetRoot = $copyRoot }
    $sentinelDirs = @("user-data", "runtime", "app\engines", "app\weights", "app\jcef-bundle", "app\readboard")
    foreach ($dir in $sentinelDirs) {
        $path = Resolve-ContainedPath -Root $targetRoot -RelativePath $dir -Label "preserved sentinel directory"
        Require-Value -Condition (Test-Path -LiteralPath $path -PathType Container) -Message "Prior portable resource is missing: $dir"
        [System.IO.File]::WriteAllText((Join-Path $path "acceptance-sentinel.bin"), "sentinel:$dir", $script:Utf8NoBom)
    }
    $before = Get-TreeHashes -Root $targetRoot
    $manifestPath = Resolve-ContainedPath -Root ([string]$CandidatePrepared.Prepared.updateRoot) -RelativePath "lizzieyzy-next-core-update-manifest.json" -Label "core update manifest"
    Require-Value -Condition ((Get-FileSha256 -Path $manifestPath) -eq [string]$CandidatePrepared.Prepared.coreManifestSha256) -Message "Prepared core update manifest drift detected."
    $manifest = Read-JsonFile -Path $manifestPath -Label "core update manifest"
    $declared = @($manifest.files | ForEach-Object { ([string]$_.path).Replace('\', '/') })
    foreach ($file in $manifest.files) {
        $relativePath = ([string]$file.path).Replace('\', '/')
        $preservedPrefixes = @("user-data/", "runtime/", "app/engines/", "app/weights/", "app/jcef-bundle/", "app/readboard/")
        Require-Value -Condition (-not ($preservedPrefixes | Where-Object { $relativePath.StartsWith($_, [StringComparison]::OrdinalIgnoreCase) })) -Message "Core update manifest attempts to replace preserved resource: $relativePath"
        $source = Resolve-ContainedPath -Root ([string]$CandidatePrepared.Prepared.updateRoot) -RelativePath $relativePath -Label "core update source"
        $destination = Resolve-ContainedPath -Root $targetRoot -RelativePath $relativePath -Label "core update destination"
        Require-Value -Condition ((Get-FileSha256 -Path $source) -eq [string]$file.sha256) -Message "Core update source hash drift detected: $relativePath"
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination -Force
    }
    $after = Get-TreeHashes -Root $targetRoot
    $changed = @($after.Keys | Where-Object { -not $before.Contains($_) -or $before[$_].sha256 -ne $after[$_].sha256 })
    $removed = @($before.Keys | Where-Object { -not $after.Contains($_) })
    $missingChanges = @($declared | Where-Object { $changed -notcontains $_ })
    $extraChanges = @($changed | Where-Object { $declared -notcontains $_ })
    Require-Value -Condition ($removed.Count -eq 0) -Message "Core update removed files: $($removed -join ', ')"
    Require-Value -Condition ($missingChanges.Count -eq 0 -and $extraChanges.Count -eq 0) -Message "Core update changed set differs from its manifest; missing=$($missingChanges -join ', ') extra=$($extraChanges -join ', ')"
    foreach ($file in $manifest.files) {
        $path = ([string]$file.path).Replace('\', '/')
        Require-Value -Condition ($after.Contains($path) -and $after[$path].sha256 -eq [string]$file.sha256) -Message "Core update did not apply declared identity: $path"
    }
    foreach ($dir in $sentinelDirs) {
        $path = (Join-Path $dir "acceptance-sentinel.bin").Replace('\', '/')
        Require-Value -Condition ($before[$path].sha256 -eq $after[$path].sha256) -Message "Core update changed preserved sentinel: $path"
    }
    $layout = Resolve-ProductLayout -ProductRoot $targetRoot -Candidate $PriorPrepared.Candidate.Value -Portable $true
    $launchPrepared = [pscustomobject]@{ Candidate = $CandidatePrepared.Candidate; Prepared = $CandidatePrepared.Prepared; PreparedPath = $CandidatePrepared.PreparedPath; Layout = $layout }
    return [pscustomobject]@{ TargetRoot = $targetRoot; Manifest = $manifestPath; Changed = $changed; Preserved = $sentinelDirs; Prepared = $launchPrepared }
    }
    catch {
        $failure = $_
        if ($created -and (Test-Path -LiteralPath $copyRoot)) {
            try { Remove-Item -LiteralPath $copyRoot -Recurse -Force -ErrorAction Stop }
            catch { throw "$($failure.Exception.Message) Core-update rollback failed: $($_.Exception.Message)" }
        }
        throw $failure
    }
}

function Set-JsonProperty {
    param([object]$Object, [string]$Name, [object]$Value)
    $property = $Object.PSObject.Properties[$Name]
    if ($property) {
        $property.Value = $Value
    }
    else {
        $Object | Add-Member -NotePropertyName $Name -NotePropertyValue $Value
    }
}

function Set-UpgradeSentinels {
    param([string]$DataRoot)
    $configPath = Join-Path $DataRoot "config.txt"
    Require-Value -Condition (Test-Path -LiteralPath $configPath -PathType Leaf) -Message "Prior product did not create config.txt."
    $config = Read-JsonFile -Path $configPath -Label "prior product config"
    Require-Value -Condition ($config.ui -and $config.leelaz) -Message "Prior product config lacks ui or leelaz sections."
    $sentinelRoot = Join-Path $DataRoot "upgrade sentinels"
    New-Item -ItemType Directory -Force -Path $sentinelRoot | Out-Null
    $enginePath = Join-Path $sentinelRoot "custom engine.exe"
    $weightPath = Join-Path $sentinelRoot "custom weight.bin.gz"
    $savedSgf = Join-Path $sentinelRoot "saved game 验收.sgf"
    $opaque = Join-Path $sentinelRoot "opaque-user-data.bin"
    [System.IO.File]::WriteAllText($enginePath, "custom-engine-sentinel", $script:Utf8NoBom)
    [System.IO.File]::WriteAllText($weightPath, "custom-weight-sentinel", $script:Utf8NoBom)
    [System.IO.File]::WriteAllText($savedSgf, "(;FF[4]GM[1]SZ[9]C[upgrade sentinel])", $script:Utf8NoBom)
    [System.IO.File]::WriteAllBytes($opaque, [byte[]](0, 255, 17, 34, 51))
    $customCommand = '"{0}" gtp -model "{1}"' -f $enginePath, $weightPath
    $engine = [pscustomobject]@{
        ip = ""; initialCommand = ""; userName = ""; preload = $false
        command = $customCommand; komi = 7.5; password = ""; isDefault = $false
        port = ""; name = "Acceptance Custom Engine"; width = 19; height = 19
        useJavaSSH = $false; useKeyGen = $false; keyGenPath = ""
    }
    $engines = @($config.leelaz.'engine-settings-list') + @($engine)
    Set-JsonProperty -Object $config.leelaz -Name "engine-settings-list" -Value $engines
    Set-JsonProperty -Object $config.leelaz -Name "remote-compute" -Value ([pscustomobject]@{ provider = "custom"; 'custom-remote-code' = "acceptance-remote-profile" })
    Set-JsonProperty -Object $config.ui -Name "analysis-engine-command" -Value $customCommand
    Set-JsonProperty -Object $config.ui -Name "analysis-engine-command-customized" -Value $true
    Set-JsonProperty -Object $config.ui -Name "autoload-default" -Value $false
    Set-JsonProperty -Object $config.ui -Name "autoload-last" -Value $false
    Set-JsonProperty -Object $config.ui -Name "autoload-empty" -Value $true
    Set-JsonProperty -Object $config.ui -Name "show-winrate-graph" -Value $false
    Set-JsonProperty -Object $config.ui -Name "use-language" -Value 1
    [System.IO.File]::WriteAllText($configPath, ($config | ConvertTo-Json -Depth 100) + [Environment]::NewLine, $script:Utf8NoBom)
    return [pscustomobject]@{
        ConfigPath = $configPath
        CustomCommand = $customCommand
        Files = [ordered]@{
            engine = [ordered]@{ path = $enginePath; sha256 = Get-FileSha256 -Path $enginePath }
            weight = [ordered]@{ path = $weightPath; sha256 = Get-FileSha256 -Path $weightPath }
            sgf = [ordered]@{ path = $savedSgf; sha256 = Get-FileSha256 -Path $savedSgf }
            opaque = [ordered]@{ path = $opaque; sha256 = Get-FileSha256 -Path $opaque }
        }
    }
}

function Assert-UpgradeSentinels {
    param([object]$Sentinels)
    foreach ($entry in $Sentinels.Files.GetEnumerator()) {
        Require-Value -Condition (Test-Path -LiteralPath ([string]$entry.Value.path) -PathType Leaf) -Message "Upgrade removed $($entry.Key) sentinel."
        Require-Value -Condition ((Get-FileSha256 -Path ([string]$entry.Value.path)) -eq [string]$entry.Value.sha256) -Message "Upgrade changed $($entry.Key) sentinel bytes."
    }
    $config = Read-JsonFile -Path ([string]$Sentinels.ConfigPath) -Label "upgraded product config"
    $custom = @($config.leelaz.'engine-settings-list' | Where-Object { $_.name -eq "Acceptance Custom Engine" })
    Require-Value -Condition ($custom.Count -eq 1 -and [string]$custom[0].command -eq [string]$Sentinels.CustomCommand) -Message "Upgrade did not preserve the custom engine command and weight path."
    Require-Value -Condition ($config.leelaz.'remote-compute'.provider -eq "custom" -and $config.leelaz.'remote-compute'.'custom-remote-code' -eq "acceptance-remote-profile") -Message "Upgrade did not preserve the remote profile."
    Require-Value -Condition ($config.ui.'autoload-default' -eq $false -and $config.ui.'autoload-last' -eq $false -and $config.ui.'autoload-empty' -eq $true) -Message "Upgrade did not preserve startup/autoload mode."
    Require-Value -Condition ($config.ui.'show-winrate-graph' -eq $false -and [int]$config.ui.'use-language' -eq 1) -Message "Upgrade did not preserve unrelated UI preferences."
}

function Get-ExpectedUpgradeUuid {
    param([string]$ArtifactKey)
    switch ($ArtifactKey) {
        "windows_installer" { return "{C2EF73EC-F99A-4F3D-B950-F52C0186122A}" }
        "windows_opencl_installer" { return "{0EC8B17F-06B0-4F6A-9246-CF61953743CF}" }
        "windows_nvidia_installer" { return "{14A4599E-6D5B-4B86-9895-7748266F0C25}" }
        default { throw "No accepted installer upgrade identity for $ArtifactKey" }
    }
}

function Get-RelatedProductCodes {
    param([string]$UpgradeUuid)
    if (-not ([System.Management.Automation.PSTypeName]'WindowsAcceptance.Msi').Type) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
namespace WindowsAcceptance {
  public static class Msi {
    [DllImport("msi.dll", CharSet = CharSet.Unicode)]
    public static extern uint MsiEnumRelatedProducts(string upgradeCode, uint reserved, uint index, StringBuilder productCode);
  }
}
'@
    }
    $codes = New-Object System.Collections.Generic.List[string]
    for ($index = 0; ; $index++) {
        $buffer = New-Object System.Text.StringBuilder(39)
        $result = [WindowsAcceptance.Msi]::MsiEnumRelatedProducts($UpgradeUuid, 0, [uint32]$index, $buffer)
        if ($result -eq 259) { break }
        Require-Value -Condition ($result -eq 0) -Message "Unable to enumerate Windows Installer upgrade identity $UpgradeUuid; error=$result"
        $codes.Add($buffer.ToString())
    }
    return @($codes)
}

function Get-ReleaseOrdinal {
    param([string]$Release)
    $match = [regex]::Match($Release, '^next-(?<date>\d{4}-\d{2}-\d{2})\.(?<sequence>\d+)$')
    Require-Value -Condition $match.Success -Message "Release tag is not ordered: $Release"
    $date = [DateTime]::ParseExact($match.Groups['date'].Value, "yyyy-MM-dd", [Globalization.CultureInfo]::InvariantCulture)
    return ($date.Ticks + [long]$match.Groups['sequence'].Value)
}

function Invoke-InstallerUpgradeScenario {
    param([object]$CurrentPrepared, [object]$PriorIdentity, [string]$Evidence)
    Require-Value -Condition ($CurrentPrepared.Candidate.Value.artifact.class -eq "installer-product" -and $PriorIdentity.Value.artifact.class -eq "installer-product") -Message "installer-upgrade-preserve requires installer candidates."
    $allowedKeys = @("windows_installer", "windows_opencl_installer", "windows_nvidia_installer")
    $key = [string]$CurrentPrepared.Candidate.Value.artifact.key
    Require-Value -Condition ($allowedKeys -contains $key) -Message "Actual upgrade acceptance covers only CPU, OpenCL and NVIDIA installer identities."
    Require-Value -Condition ([string]$PriorIdentity.Value.artifact.key -eq $key) -Message "Prior and candidate installer product identities differ."
    Require-Value -Condition ([string]$PriorIdentity.Value.artifact.sha256 -ne [string]$CurrentPrepared.Candidate.Value.artifact.sha256 -and [string]$PriorIdentity.Value.targetSha -ne [string]$CurrentPrepared.Candidate.Value.targetSha) -Message "Prior and candidate installer identities must be distinct."
    Require-Value -Condition ((Get-ReleaseOrdinal -Release ([string]$PriorIdentity.Value.releaseTag)) -lt (Get-ReleaseOrdinal -Release ([string]$CurrentPrepared.Candidate.Value.releaseTag))) -Message "Prior installer release must precede the candidate release."
    Assert-Administrator -Operation "Actual installer upgrade acceptance"
    $installRoot = [string]$CurrentPrepared.Prepared.productRoot
    $priorInstall = $null
    $candidateInstall = $null
    $priorRun = $null
    $candidateRun = $null
    $priorRunPath = Join-Path $Evidence "prior-run.json"
    $candidateRunPath = Join-Path $Evidence "candidate-run.json"
    $upgradeUuid = Get-ExpectedUpgradeUuid -ArtifactKey $key
    try {
        Invoke-Uninstall -InstallEvidence $CurrentPrepared.Prepared.install -LogPath (Join-Path $Evidence "remove-prepared-candidate.log")
        if (Test-Path -LiteralPath $installRoot) { Remove-Item -LiteralPath $installRoot -Recurse -Force -ErrorAction Stop }
        $priorInstall = Invoke-Installer -Installer $PriorIdentity.AssetPath -InstallRoot $installRoot -LogPath (Join-Path $Evidence "install-prior.log")
        $priorRelated = @(Get-RelatedProductCodes -UpgradeUuid $upgradeUuid)
        Require-Value -Condition ($priorRelated -contains [string]$priorInstall.ProductCode) -Message "Prior installer product code is not registered under expected upgrade UUID $upgradeUuid."
        $priorLayout = Resolve-ProductLayout -ProductRoot $priorInstall.ProductRoot -Candidate $PriorIdentity.Value -Portable $false
        $priorPrepared = [pscustomobject]@{ Candidate = $PriorIdentity; Prepared = [pscustomobject]@{ install = $priorInstall }; PreparedPath = $CurrentPrepared.PreparedPath; Layout = $priorLayout }
        $priorRun = Start-PreparedProduct -PreparedIdentity $priorPrepared -OutputRunJson $priorRunPath -ScenarioId "installer-upgrade-preserve" -Evidence $Evidence -Offline $true
        [void](Stop-OwnedRun -Run $priorRun -Path $priorRunPath -SkipUninstall)
        $sentinels = Set-UpgradeSentinels -DataRoot ([string]$priorRun.dataRoot.path)
        $candidateInstall = Invoke-Installer -Installer $CurrentPrepared.Candidate.AssetPath -InstallRoot $installRoot -LogPath (Join-Path $Evidence "install-candidate.log") -AllowedExistingInstall $priorInstall
        Require-Value -Condition ([string]$candidateInstall.DisplayVersion -ne [string]$priorInstall.DisplayVersion) -Message "Candidate installer did not change the installed DisplayVersion."
        Require-Value -Condition ([string]$candidateInstall.ProductCode -ne [string]$priorInstall.ProductCode) -Message "Candidate installer did not replace the prior Windows Installer product identity."
        $candidateRelated = @(Get-RelatedProductCodes -UpgradeUuid $upgradeUuid)
        Require-Value -Condition ($candidateRelated -contains [string]$candidateInstall.ProductCode) -Message "Candidate installer product code is not registered under expected upgrade UUID $upgradeUuid."
        $candidateLayout = Resolve-ProductLayout -ProductRoot $candidateInstall.ProductRoot -Candidate $CurrentPrepared.Candidate.Value -Portable $false
        Assert-UpgradeSentinels -Sentinels $sentinels
        $candidatePrepared = [pscustomobject]@{ Candidate = $CurrentPrepared.Candidate; Prepared = [pscustomobject]@{ install = $candidateInstall }; PreparedPath = $CurrentPrepared.PreparedPath; Layout = $candidateLayout }
        $candidateRun = Start-PreparedProduct -PreparedIdentity $candidatePrepared -OutputRunJson $candidateRunPath -ScenarioId "installer-upgrade-preserve" -Evidence $Evidence -Offline $true
        Assert-UpgradeSentinels -Sentinels $sentinels
        return [pscustomobject]@{ Run = $candidateRun; RunPath = $candidateRunPath; Sentinels = $sentinels; InstallEvidence = $candidateInstall; PriorInstallEvidence = $priorInstall; UpgradeUuid = $upgradeUuid; PriorRelatedProducts = $priorRelated; CandidateRelatedProducts = $candidateRelated }
    }
    catch {
        $failure = $_
        $cleanupErrors = New-Object System.Collections.Generic.List[string]
        foreach ($item in @(@{ run = $candidateRun; path = $candidateRunPath }, @{ run = $priorRun; path = $priorRunPath })) {
            if ($item.run -and $item.run.state -eq "RUNNING") {
                try { [void](Stop-OwnedRun -Run $item.run -Path $item.path -SkipUninstall) }
                catch { $cleanupErrors.Add($_.Exception.Message) }
            }
        }
        $ownedInstall = if ($candidateInstall) { $candidateInstall } else { $priorInstall }
        if ($ownedInstall) {
            try { Invoke-Uninstall -InstallEvidence $ownedInstall -LogPath (Join-Path $Evidence "failed-upgrade-cleanup.log") }
            catch { $cleanupErrors.Add($_.Exception.Message) }
        }
        if ($cleanupErrors.Count -gt 0) { throw "$($failure.Exception.Message) Upgrade cleanup failed: $($cleanupErrors -join '; ')" }
        throw $failure
    }
}

function Require-ExactProperties {
    param([object]$Object, [string[]]$Names, [string]$Label)
    Require-Value -Condition ($null -ne $Object) -Message "$Label is missing."
    $actual = @($Object.PSObject.Properties.Name | Sort-Object)
    $expected = @($Names | Sort-Object)
    Require-Value -Condition (($actual -join "|") -ceq ($expected -join "|")) -Message "$Label fields differ; expected=$($expected -join ',') actual=$($actual -join ',')"
}

function Wait-EngineOracle {
    param([string]$Path, [int]$TimeoutSeconds)
    $resolved = [System.IO.Path]::GetFullPath($Path)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $resolved -PathType Leaf) { return $resolved }
        Start-Sleep -Milliseconds 250
    }
    throw "Timed out waiting for the bound engine oracle producer to atomically write: $resolved"
}

function Assert-EngineOracle {
    param([string]$Path, [object]$Run, [string]$RunPath)
    $envelope = Read-JsonFile -Path $Path -Label "Windows engine analysis oracle envelope"
    Require-ExactProperties -Object $envelope -Names @("schemaVersion", "scenario", "status", "binding", "oracle") -Label "engine oracle envelope"
    Require-Value -Condition ($envelope.schemaVersion -eq 1 -and $envelope.scenario -eq "windows-final-product-real-cpu" -and $envelope.status -eq "PASS") -Message "Engine oracle envelope identity did not PASS."
    $binding = $envelope.binding
    Require-ExactProperties -Object $binding -Names @("candidateSha256", "artifactSha256", "runJsonSha256", "launcherSha256", "runtimeSha256", "jvmModuleSha256", "jarSha256", "dataRoot", "launcherPid", "enginePid", "engineCommand", "oracleSha256") -Label "engine oracle binding"
    Require-Value -Condition ([string]$binding.candidateSha256 -eq [string]$Run.candidate.sha256 -and [string]$binding.artifactSha256 -eq [string]$Run.candidate.artifactSha256) -Message "Engine oracle candidate binding differs from this live run."
    Require-Value -Condition ([string]$binding.runJsonSha256 -eq (Get-FileSha256 -Path $RunPath)) -Message "Engine oracle run-record binding differs from this live run."
    Require-Value -Condition ([string]$binding.launcherSha256 -eq [string]$Run.launcher.sha256 -and [string]$binding.runtimeSha256 -eq [string]$Run.runtime.sha256 -and [string]$binding.jvmModuleSha256 -eq [string]$Run.runtime.jvmModule.moduleSha256 -and [string]$binding.jarSha256 -eq [string]$Run.jar.sha256) -Message "Engine oracle packaged runtime binding differs from this live run."
    Require-Value -Condition ([string]$binding.dataRoot -ieq [string]$Run.dataRoot.path -and [long]$binding.launcherPid -eq [long]$Run.launcher.pid) -Message "Engine oracle process/data-root binding differs from this live run."
    $oracle = $envelope.oracle
    Require-Value -Condition ([string]$binding.oracleSha256 -eq (Get-ObjectSha256 -Value $oracle)) -Message "Engine oracle payload hash binding differs."
    Require-ExactProperties -Object $oracle -Names @("schemaVersion", "scenario", "status", "failure", "peer", "source", "platform", "manifest", "assets", "fixture", "node", "rules", "position", "analysis", "phasesMs", "stop", "quit", "cleanup", "evidence") -Label "engine-sgf oracle"
    Require-Value -Condition ($oracle.schemaVersion -eq 1 -and $oracle.scenario -eq "real-cpu-engine" -and $oracle.status -eq "PASS" -and $null -eq $oracle.failure) -Message "Engine-sgf oracle did not PASS."
    Require-ExactProperties -Object $oracle.peer -Names @("kind", "catalogEngineId", "command", "pid", "stdoutReader", "stderrReader") -Label "engine oracle peer"
    Require-Value -Condition ($oracle.peer.kind -eq "real-katago-cpu" -and [long]$oracle.peer.pid -eq [long]$binding.enginePid -and [string]$oracle.peer.command -ceq [string]$binding.engineCommand) -Message "Engine oracle does not prove bound production real CPU ownership."
    $engineSnapshot = @($Run.processes | Where-Object { [long]$_.pid -eq [long]$binding.enginePid -and $_.image -and ([System.IO.Path]::GetFullPath([string]$_.image) -ieq [System.IO.Path]::GetFullPath([string]$Run.engine.path)) })
    Require-Value -Condition ($engineSnapshot.Count -eq 1 -and [string]$engineSnapshot[0].commandLine -match [regex]::Escape([string]$Run.engine.modelPath) -and [string]$engineSnapshot[0].commandLine -match [regex]::Escape([string]$Run.engine.configPath)) -Message "Engine oracle PID is not a captured packaged product engine incarnation."
    Require-Value -Condition ([string]$oracle.source.commit -eq [string]$Run.candidate.targetSha -and $oracle.source.dirty -eq $false -and [string]$oracle.platform.os -match '(?i)windows') -Message "Engine oracle source/platform identity differs from this Windows candidate."
    Require-Value -Condition ([string]$oracle.assets.enginePath -ieq [string]$Run.engine.path -and [string]$oracle.assets.engineSha256 -eq [string]$Run.engine.sha256 -and [string]$oracle.assets.modelPath -ieq [string]$Run.engine.modelPath -and [string]$oracle.assets.modelSha256 -eq [string]$Run.engine.modelSha256 -and [string]$oracle.assets.configPath -ieq [string]$Run.engine.configPath -and [string]$oracle.assets.configSha256 -eq [string]$Run.engine.configSha256) -Message "Engine oracle does not use this product's packaged engine closure."
    Require-Value -Condition ([string]$binding.engineCommand -match [regex]::Escape([string]$Run.engine.path) -and [string]$binding.engineCommand -match [regex]::Escape([string]$Run.engine.modelPath) -and [string]$binding.engineCommand -match [regex]::Escape([string]$Run.engine.configPath)) -Message "Bound engine command does not name the packaged executable/model/config."
    Require-ExactProperties -Object $oracle.fixture -Names @("path", "sha256") -Label "engine oracle fixture"
    Require-Value -Condition ([string]$oracle.fixture.sha256 -eq "ec41cf044ee29b2408b488727db2b6bbe5a2a3acfe5ed74d70fbb6dac2be3a9c") -Message "Engine oracle did not use the frozen D4 fixture."
    Require-ExactProperties -Object $oracle.node -Names @("semanticPath", "kind", "identity") -Label "engine oracle node"
    Require-Value -Condition ([string]$oracle.node.semanticPath -ceq "0/0/0/0/0" -and [string]$oracle.node.kind -ceq "PASS" -and [bool][string]$oracle.node.identity) -Message "Engine oracle frozen semantic node is invalid."
    Require-ExactProperties -Object $oracle.rules -Names @("targetRaw", "targetSummary", "targetRevision", "actual", "status", "fresh") -Label "engine oracle rules"
    Require-ExactProperties -Object $oracle.rules.actual -Names @("friendlyPassOk", "scoring", "ko", "whiteHandicapBonus", "suicide", "tax", "hasButton") -Label "engine oracle actual rules"
    Require-Value -Condition ($oracle.rules.targetRaw -ceq "Chinese" -and $oracle.rules.targetSummary -ceq "CHINESE" -and [long]$oracle.rules.targetRevision -gt 0 -and $oracle.rules.status -ceq "CONFIRMED" -and $oracle.rules.fresh -eq $true) -Message "Engine oracle session rules target is invalid."
    Require-Value -Condition ($oracle.rules.actual.scoring -ceq "AREA" -and $oracle.rules.actual.ko -ceq "SIMPLE" -and $oracle.rules.actual.tax -ceq "NONE" -and $oracle.rules.actual.suicide -eq $false -and $oracle.rules.actual.hasButton -eq $false -and $oracle.rules.actual.friendlyPassOk -eq $true -and $oracle.rules.actual.whiteHandicapBonus -ceq "N") -Message "Engine oracle fresh actual Chinese rules are invalid."
    Require-ExactProperties -Object $oracle.position -Names @("confirmed", "board", "komi", "stones", "empty", "turn", "setupKind", "setupStones", "tailPrevious", "tailCurrent") -Label "engine oracle position"
    Require-Value -Condition ($oracle.position.confirmed -eq $true -and $oracle.position.board -ceq "19x19" -and [double]$oracle.position.komi -eq 6.5 -and $oracle.position.stones -ceq "B:fd;W:ee,ff,hh" -and $oracle.position.empty -ceq "dd" -and $oracle.position.turn -ceq "W" -and $oracle.position.setupKind -ceq "SNAPSHOT" -and $oracle.position.setupStones -ceq "B:fd;W:ee,ff" -and $oracle.position.tailPrevious -ceq "MOVE:W[hh]" -and $oracle.position.tailCurrent -ceq "PASS:B[]") -Message "Engine oracle peer position differs from the frozen SNAPSHOT/MOVE/PASS target."
    Require-Value -Condition ($oracle.analysis.schema -eq "katago-info-v1" -and [long]$oracle.analysis.visits -gt 0 -and [bool][string]$oracle.analysis.move) -Message "Engine oracle has no structurally parsed positive-visit candidate."
    Require-Value -Condition ($oracle.stop.requested -eq $true -and $oracle.stop.quiet -eq $true -and [int]$oracle.stop.quietWindowMs -eq 400 -and [long]$oracle.stop.peerOutputCount -gt 0 -and [long]$oracle.stop.applicationVisits -gt 0) -Message "Engine oracle quiet-stop evidence is incomplete."
    Require-Value -Condition ($oracle.quit.normal -eq $true -and $oracle.cleanup.forced -eq $false -and $oracle.cleanup.process -eq $true -and $oracle.cleanup.readers -eq $true -and $oracle.cleanup.stagedSgf -eq $true) -Message "Engine oracle normal quit/cleanup is incomplete."
    foreach ($name in @("result", "stdout", "stderr", "appLog", "phases")) { Require-Value -Condition (Test-Path -LiteralPath ([string]$oracle.evidence.$name) -PathType Leaf) -Message "Engine oracle evidence is missing: $name" }
    $rawResult = Read-JsonFile -Path ([string]$oracle.evidence.result) -Label "engine oracle result evidence"
    Require-Value -Condition ((Get-ObjectSha256 -Value $rawResult) -eq [string]$binding.oracleSha256) -Message "Engine oracle result evidence differs from the bound payload."
    $staged = @($oracle.evidence.stagedSgfs)
    Require-Value -Condition ($staged.Count -gt 0 -and @($staged | Where-Object { Test-Path -LiteralPath ([string]$_) }).Count -eq 0) -Message "Engine oracle staged SGF cleanup evidence is invalid."
    return $envelope
}

function Add-NullReasons {
    param([object]$Value, [string]$Path, [System.Collections.IDictionary]$Reasons, [string]$Phase)
    if ($null -eq $Value) {
        $Reasons[$Path] = "not observed before terminal phase $Phase"
        return
    }
    if ($Value -is [System.Collections.IDictionary]) {
        foreach ($key in $Value.Keys) { Add-NullReasons -Value $Value[$key] -Path "$Path.$key" -Reasons $Reasons -Phase $Phase }
        return
    }
    if ($Value -is [pscustomobject]) {
        foreach ($property in $Value.PSObject.Properties) { Add-NullReasons -Value $property.Value -Path "$Path.$($property.Name)" -Reasons $Reasons -Phase $Phase }
        return
    }
    if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) {
        $index = 0
        foreach ($item in $Value) { Add-NullReasons -Value $item -Path "$Path[$index]" -Reasons $Reasons -Phase $Phase; $index++ }
    }
}

function New-ExpectedProduct {
    return [ordered]@{
        releaseTag = $null
        backend = $null
        productRoot = $null
        launcher = [ordered]@{ path = $null; sha256 = $null }
        runtime = [ordered]@{ path = $null; sha256 = $null; version = $null }
        jvmModule = [ordered]@{ path = $null; sha256 = $null }
        jar = [ordered]@{ path = $null; sha256 = $null }
        engine = [ordered]@{ path = $null; sha256 = $null; configPath = $null; configSha256 = $null; modelPath = $null; modelSha256 = $null }
        components = [ordered]@{ installedManifestPath = $null; installedManifestSha256 = $null; jcefPath = $null; jcefSha256 = $null; readBoardPath = $null; readBoardSha256 = $null }
        dataRoot = [ordered]@{ selection = $null; explicitOverride = $null }
    }
}

function Set-ExpectedProductFromLayout {
    param([System.Collections.IDictionary]$Expected, [object]$Candidate, [object]$Layout, [bool]$Portable)
    $Expected.releaseTag = [string]$Candidate.releaseTag
    $Expected.backend = [string]$Layout.Backend
    $Expected.productRoot = [string]$Layout.Root
    $Expected.launcher.path = [string]$Layout.Launcher
    $Expected.launcher.sha256 = [string]$Layout.LauncherSha256
    $Expected.runtime.path = [string]$Layout.Runtime
    $Expected.runtime.sha256 = [string]$Layout.RuntimeSha256
    $Expected.runtime.version = [string]$Layout.RuntimeVersion
    $Expected.jvmModule.path = [string]$Layout.JvmDll
    $Expected.jvmModule.sha256 = [string]$Layout.JvmDllSha256
    $Expected.jar.path = [string]$Layout.Jar
    $Expected.jar.sha256 = [string]$Layout.JarSha256
    $Expected.engine.path = [string]$Layout.Engine
    $Expected.engine.sha256 = [string]$Layout.EngineSha256
    $Expected.engine.configPath = [string]$Layout.EngineConfig
    $Expected.engine.configSha256 = [string]$Layout.EngineConfigSha256
    $Expected.engine.modelPath = [string]$Layout.Model
    $Expected.engine.modelSha256 = [string]$Layout.ModelSha256
    $Expected.components.installedManifestPath = [string]$Layout.InstalledManifest
    $Expected.components.installedManifestSha256 = [string]$Layout.InstalledManifestSha256
    $Expected.components.jcefPath = [string]$Layout.Jcef
    $Expected.components.jcefSha256 = [string]$Layout.JcefSha256
    $Expected.components.readBoardPath = [string]$Layout.ReadBoard
    $Expected.components.readBoardSha256 = [string]$Layout.ReadBoardSha256
    $Expected.dataRoot.selection = if ($Portable) { "portable-marker" } else { "explicit-work-dir" }
    $Expected.dataRoot.explicitOverride = (-not $Portable)
}

function New-ObservedModel {
    return [ordered]@{
        candidate = [ordered]@{ path = $null; sha256 = $null; artifactSha256 = $null; provenancePath = $null; provenanceSha256 = $null; targetSha = $null; releaseTag = $null }
        host = [ordered]@{ os = [Environment]::OSVersion.VersionString; architecture = [string]$env:PROCESSOR_ARCHITECTURE; powershell = [string]$PSVersionTable.PSVersion }
        product = [ordered]@{ preparedPath = $null; productRoot = $null; installRegistryPath = $null; installedReleaseTag = $null; backend = $null }
        launcher = [ordered]@{ path = $null; sha256 = $null; pid = $null; image = $null; commandLine = $null }
        runtime = [ordered]@{ path = $null; sha256 = $null; version = $null; architecture = $null; jvmModulePath = $null; jvmModuleSha256 = $null }
        jar = [ordered]@{ path = $null; sha256 = $null }
        engine = [ordered]@{ path = $null; sha256 = $null; configPath = $null; configSha256 = $null; modelPath = $null; modelSha256 = $null }
        components = [ordered]@{ installedManifestSha256 = $null; jcefSha256 = $null; readBoardSha256 = $null }
        dataRoot = [ordered]@{ path = $null; selection = $null; explicitOverride = $null }
        installer = [ordered]@{ displayVersion = $null; productCode = $null; upgradeUuid = $null; priorProductCode = $null; candidateProductCode = $null }
        coreUpdate = [ordered]@{ manifestPath = $null; changedFiles = $null; preservedDirectories = $null }
        network = [ordered]@{ offline = $null; firewallRuleCount = $null; auditEvidence = $null; blockedAttemptCount = $null; externalConnectionCount = $null }
        logs = [ordered]@{ application = $null; runRecord = $null }
        stop = [ordered]@{ state = $null; remainingPids = $null; firewallRulesRemaining = $null; errors = $null }
        outcome = [ordered]@{ distributionStatus = $null; inferenceStatus = $null; summary = $null; evidencePath = $null }
    }
}

function Set-ObservedRun {
    param([System.Collections.IDictionary]$Observed, [object]$Run)
    $Observed.product.productRoot = [string]$Run.productRoot
    $Observed.launcher.path = [string]$Run.launcher.path
    $Observed.launcher.sha256 = [string]$Run.launcher.sha256
    $Observed.launcher.pid = [long]$Run.launcher.pid
    $Observed.launcher.image = [string]$Run.launcher.process.image
    $Observed.launcher.commandLine = [string]$Run.launcher.process.commandLine
    $Observed.runtime.path = [string]$Run.runtime.path
    $Observed.runtime.sha256 = [string]$Run.runtime.sha256
    $Observed.runtime.version = [string]$Run.runtime.version
    $Observed.runtime.architecture = [string]$Run.runtime.architecture
    $Observed.runtime.jvmModulePath = [string]$Run.runtime.jvmModule.modulePath
    $Observed.runtime.jvmModuleSha256 = [string]$Run.runtime.jvmModule.moduleSha256
    $Observed.jar.path = [string]$Run.jar.path
    $Observed.jar.sha256 = [string]$Run.jar.sha256
    $Observed.engine.path = [string]$Run.engine.path
    $Observed.engine.sha256 = [string]$Run.engine.sha256
    $Observed.engine.configPath = [string]$Run.engine.configPath
    $Observed.engine.configSha256 = [string]$Run.engine.configSha256
    $Observed.engine.modelPath = [string]$Run.engine.modelPath
    $Observed.engine.modelSha256 = [string]$Run.engine.modelSha256
    $Observed.product.backend = [string]$Run.backend.expected
    $Observed.product.installedReleaseTag = [string]$Run.candidate.releaseTag
    $Observed.components.installedManifestSha256 = [string]$Run.components.installedManifestSha256
    $Observed.components.jcefSha256 = [string]$Run.components.jcefSha256
    $Observed.components.readBoardSha256 = [string]$Run.components.readBoardSha256
    $Observed.dataRoot.path = [string]$Run.dataRoot.path
    $Observed.dataRoot.selection = [string]$Run.dataRoot.selection
    $Observed.dataRoot.explicitOverride = [bool]$Run.dataRoot.explicitOverride
    $Observed.network.offline = [bool]$Run.network.offline
    $Observed.network.firewallRuleCount = @($Run.network.firewallRules).Count
    $Observed.network.auditEvidence = if ($Run.network.offline) { "Security event 5157 after record $($Run.network.audit.startRecordId)" } else { "NOT_APPLICABLE" }
    $Observed.network.blockedAttemptCount = @($Run.network.blockedAttempts).Count
    $Observed.network.externalConnectionCount = @($Run.network.externalConnections).Count
    $Observed.logs.application = [string]$Run.applicationLog
    if ($Run.state -eq "STOPPED") {
        $Observed.stop.state = [string]$Run.state
        $Observed.stop.remainingPids = @($Run.cleanup.remainingOwnedPids)
        $Observed.stop.firewallRulesRemaining = @($Run.cleanup.firewallRulesRemaining)
        $Observed.stop.errors = @($Run.cleanup.identityErrors) + @($Run.cleanup.errors)
    }
}

function New-AcceptanceRecord {
    param([string]$ScenarioId, [string]$Status, [string]$Phase, [object]$Candidate, [object]$ExpectedProduct, [object]$Observed, [object]$Assertions, [object]$Evidence, [object]$Cleanup, [string]$StartedAt, [object]$Failure, [string]$Reason, [string]$BlockedPhase)
    $observationPaths = @(
        @{ path = "observed.candidate.path"; phase = "identity" },
        @{ path = "observed.candidate.sha256"; phase = "identity" },
        @{ path = "observed.candidate.artifactSha256"; phase = "identity" },
        @{ path = "observed.candidate.provenancePath"; phase = "identity" },
        @{ path = "observed.candidate.provenanceSha256"; phase = "identity" },
        @{ path = "observed.product.preparedPath"; phase = "prepare" },
        @{ path = "observed.product.productRoot"; phase = "prepare" },
        @{ path = "observed.product.backend"; phase = "launch" },
        @{ path = "observed.launcher.path"; phase = "launch" },
        @{ path = "observed.launcher.sha256"; phase = "launch" },
        @{ path = "observed.launcher.pid"; phase = "launch" },
        @{ path = "observed.launcher.image"; phase = "launch" },
        @{ path = "observed.launcher.commandLine"; phase = "launch" },
        @{ path = "observed.runtime.path"; phase = "launch" },
        @{ path = "observed.runtime.sha256"; phase = "launch" },
        @{ path = "observed.runtime.version"; phase = "launch" },
        @{ path = "observed.runtime.architecture"; phase = "launch" },
        @{ path = "observed.runtime.jvmModulePath"; phase = "launch" },
        @{ path = "observed.runtime.jvmModuleSha256"; phase = "launch" },
        @{ path = "observed.jar.path"; phase = "launch" },
        @{ path = "observed.jar.sha256"; phase = "launch" },
        @{ path = "observed.engine.path"; phase = "launch" },
        @{ path = "observed.engine.sha256"; phase = "launch" },
        @{ path = "observed.engine.configSha256"; phase = "launch" },
        @{ path = "observed.engine.modelSha256"; phase = "launch" },
        @{ path = "observed.components.installedManifestSha256"; phase = "launch" },
        @{ path = "observed.components.jcefSha256"; phase = "launch" },
        @{ path = "observed.components.readBoardSha256"; phase = "launch" },
        @{ path = "observed.dataRoot.path"; phase = "launch" },
        @{ path = "observed.dataRoot.selection"; phase = "launch" },
        @{ path = "observed.dataRoot.explicitOverride"; phase = "launch" },
        @{ path = "observed.network.blockedAttemptCount"; phase = "launch" },
        @{ path = "observed.network.externalConnectionCount"; phase = "launch" },
        @{ path = "observed.outcome.distributionStatus"; phase = "verify" },
        @{ path = "observed.outcome.inferenceStatus"; phase = "verify" },
        @{ path = "observed.outcome.summary"; phase = "verify" },
        @{ path = "observed.outcome.evidencePath"; phase = "verify" },
        @{ path = "observed.stop.state"; phase = "cleanup" },
        @{ path = "observed.stop.remainingPids"; phase = "cleanup" },
        @{ path = "observed.stop.firewallRulesRemaining"; phase = "cleanup" },
        @{ path = "observed.stop.errors"; phase = "cleanup" }
    )
    $assertionPaths = @(
        @{ path = "assertions.identity"; phase = "identity" },
        @{ path = "assertions.prepared"; phase = "prepare" },
        @{ path = "assertions.launched"; phase = "launch" },
        @{ path = "assertions.verified"; phase = "verify" },
        @{ path = "assertions.cleaned"; phase = "cleanup" }
    )
    $evidencePaths = @(
        @{ path = "evidence.identity"; phase = "identity" },
        @{ path = "evidence.prepare"; phase = "prepare" },
        @{ path = "evidence.launch"; phase = "launch" },
        @{ path = "evidence.verification"; phase = "verify" },
        @{ path = "evidence.cleanup"; phase = "cleanup" }
    )
    $notObserved = [ordered]@{}
    Add-NullReasons -Value $Observed -Path "observed" -Reasons $notObserved -Phase $Phase
    Add-NullReasons -Value $Assertions -Path "assertions" -Reasons $notObserved -Phase $Phase
    Add-NullReasons -Value $Evidence -Path "evidence" -Reasons $notObserved -Phase $Phase
    return [ordered]@{
        schemaVersion = 1
        scenarioId = $ScenarioId
        status = $Status
        phase = $Phase
        startedAt = $StartedAt
        finishedAt = Get-UtcTimestamp
        expected = [ordered]@{
            targetSha = [string]$Candidate.targetSha
            platform = "windows"
            architecture = "x86_64"
            artifact = [ordered]@{ key = [string]$Candidate.artifact.key; name = [string]$Candidate.artifact.name; class = [string]$Candidate.artifact.class }
            scenario = $ScenarioId
            product = $ExpectedProduct
            requiredOutcomes = [ordered]@{ phases = @("identity", "prepare", "launch", "verify", "cleanup"); observations = $observationPaths; assertions = $assertionPaths; evidence = $evidencePaths }
        }
        observed = $Observed
        notObserved = $notObserved
        assertions = $Assertions
        evidence = $Evidence
        cleanup = [ordered]@{ complete = [bool]$Cleanup.complete; remainingOwnedResources = @($Cleanup.remainingOwnedResources) }
        blockedPhase = if ($Status -eq "BLOCKED") { $BlockedPhase } else { $null }
        reason = if ($Status -eq "BLOCKED") { $Reason } else { $null }
        failure = if ($Status -eq "FAIL") { $Failure } else { $null }
    }
}

function Get-RequestedCandidate {
    if ($CandidateJson -and (Test-Path -LiteralPath $CandidateJson -PathType Leaf)) { return Read-JsonFile -Path $CandidateJson -Label "candidate.json" }
    Require-Value -Condition ($TargetSha -match '^[0-9a-f]{40}$' -and $ExpectedArtifactKey -and $ExpectedArtifactName -and $ExpectedArtifactClass) -Message "A missing candidate requires TargetSha and ExpectedArtifactKey/Name/Class so BLOCKED evidence has exact requested identity."
    return [pscustomobject]@{ targetSha = $TargetSha; artifact = [pscustomobject]@{ key = $ExpectedArtifactKey; name = $ExpectedArtifactName; class = $ExpectedArtifactClass } }
}

function Invoke-Run {
    Require-Value -Condition ($script:ScenarioIds -contains $Scenario -and $Scenario -ne "live-session") -Message "Run requires a terminal one-shot scenario."
    $evidence = Resolve-FullPath -Path $EvidenceDir -Label "evidence directory" -MustExist
    $startedAt = Get-UtcTimestamp
    $recordPath = Join-Path $evidence "acceptance.json"
    $runPath = Join-Path $evidence "run.json"
    Require-Value -Condition (-not (Test-Path -LiteralPath $recordPath)) -Message "acceptance.json already exists: $recordPath"
    $requested = Get-RequestedCandidate
    $observed = New-ObservedModel
    $expectedProduct = New-ExpectedProduct
    $assertions = [ordered]@{ identity = $null; prepared = $null; launched = $null; verified = $null; cleaned = $null }
    $evidenceValues = [ordered]@{ identity = $null; prepare = $null; launch = $null; verification = $null; cleanup = $null }
    $cleanup = [ordered]@{ complete = $false; remainingOwnedResources = @() }
    $status = "FAIL"
    $phase = "identity"
    $failure = $null
    $blockReason = $null
    $blockedPhase = $null
    $prepared = $null
    $run = $null
    $coreOutcome = $null
    $candidateTransferred = [bool]($CandidateJson -and (Test-Path -LiteralPath $CandidateJson -PathType Leaf))
    $installerOwnershipExpected = ($candidateTransferred -and [string]$requested.artifact.class -eq "installer-product")
    $preparedInstallerOwnership = $null
    $startupCleanup = $null
    $startupCleanupPath = $null
    try {
        if (-not ($CandidateJson -and (Test-Path -LiteralPath $CandidateJson -PathType Leaf))) { Throw-Blocked -Phase "identity" -Reason "The requested candidate.json has not been transferred to this Windows host." }
        if ($installerOwnershipExpected) { $preparedInstallerOwnership = Read-PreparedInstallerOwnership -CandidatePath $CandidateJson -EvidenceRoot $evidence }
        $prepared = Read-PreparedIdentity -CandidatePath $CandidateJson
        $requested = $prepared.Candidate.Value
        $observed.candidate.path = $prepared.Candidate.Path
        $observed.candidate.sha256 = $prepared.Candidate.Hash
        $observed.candidate.artifactSha256 = [string]$requested.artifact.sha256
        $observed.candidate.provenancePath = [string]$requested.provenance.path
        $observed.candidate.provenanceSha256 = [string]$requested.provenance.sha256
        $observed.candidate.targetSha = [string]$requested.targetSha
        $observed.candidate.releaseTag = [string]$requested.releaseTag
        $assertions.identity = $true
        $evidenceValues.identity = $prepared.Candidate.Path
        $phase = "prepare"
        $observed.product.preparedPath = $prepared.PreparedPath
        $observed.product.productRoot = if ($prepared.Layout) { [string]$prepared.Layout.Root } else { [string]$prepared.Prepared.updateRoot }
        $observed.product.installRegistryPath = if ($prepared.Prepared.install) { [string]$prepared.Prepared.install.RegistryPath } else { $null }
        if ($prepared.Layout) {
            Set-ExpectedProductFromLayout -Expected $expectedProduct -Candidate $requested -Layout $prepared.Layout -Portable ($requested.artifact.class -eq "portable-product")
        }
        if ($prepared.Prepared.install) {
            $observed.installer.displayVersion = [string]$prepared.Prepared.install.DisplayVersion
            $observed.installer.productCode = [string]$prepared.Prepared.install.ProductCode
        }
        $assertions.prepared = $true
        $evidenceValues.prepare = $prepared.PreparedPath
        Require-Value -Condition (-not (Test-Path -LiteralPath $runPath)) -Message "run.json already exists: $runPath"

        if ($Scenario -ne "core-update-preserve") {
            if (-not (Test-IsAdministrator)) { Throw-Blocked -Phase "launch" -Reason "An elevated Windows administrator session is required for the owned firewall/audit boundary and installer cleanup." }
            if (-not (Get-Command auditpol.exe -ErrorAction SilentlyContinue)) { Throw-Blocked -Phase "launch" -Reason "Windows auditpol.exe is unavailable, so offline connection-attempt evidence cannot be established." }
            try { [void](Get-WinEvent -ListLog "Security" -ErrorAction Stop) } catch { Throw-Blocked -Phase "launch" -Reason "The Windows Security event log is unavailable for Filtering Platform deny evidence." }
        }
        if ($Scenario -in @("core-update-preserve", "installer-upgrade-preserve") -and (-not $PriorCandidateJson -or -not (Test-Path -LiteralPath $PriorCandidateJson -PathType Leaf))) {
            Throw-Blocked -Phase "launch" -Reason "$Scenario requires the pinned prior candidate to be transferred to this Windows host."
        }
        if ($Scenario -in @("portable-offline-first-run", "installer-offline-first-run")) {
            Require-Value -Condition ([bool]$EngineOracleFile) -Message "$Scenario requires a bound engine-sgf oracle output path."
            $oracleOutput = [System.IO.Path]::GetFullPath($EngineOracleFile)
            Require-Value -Condition (Test-Path -LiteralPath (Split-Path -Parent $oracleOutput) -PathType Container) -Message "Engine oracle output parent does not exist."
            Require-Value -Condition (-not (Test-Path -LiteralPath $oracleOutput)) -Message "Engine oracle output path must be new to prevent replay: $oracleOutput"
        }

        $phase = "launch"
        if ($Scenario -eq "core-update-preserve") {
            $prior = Read-PreparedIdentity -CandidatePath $PriorCandidateJson
            $coreOutcome = Invoke-CoreUpdateScenario -CandidatePrepared $prepared -PriorPrepared $prior -Evidence $evidence
            Set-ExpectedProductFromLayout -Expected $expectedProduct -Candidate $requested -Layout $coreOutcome.Prepared.Layout -Portable $true
            $run = Start-PreparedProduct -PreparedIdentity $coreOutcome.Prepared -OutputRunJson $runPath -ScenarioId $Scenario -Evidence $evidence -Offline $false
            Set-ObservedRun -Observed $observed -Run $run
            $observed.product.productRoot = $coreOutcome.TargetRoot
            $observed.coreUpdate.manifestPath = [string]$coreOutcome.Manifest
            $observed.coreUpdate.changedFiles = @($coreOutcome.Changed)
            $observed.coreUpdate.preservedDirectories = @($coreOutcome.Preserved)
            $assertions.launched = $true
            $evidenceValues.launch = $runPath
            $phase = "verify"
            $observed.outcome.distributionStatus = "PASS"
            $observed.outcome.inferenceStatus = "NOT_REQUIRED"
            $observed.outcome.summary = "Core update changed exactly $(@($coreOutcome.Changed).Count) manifest-owned files and launched the candidate release."
            $observed.outcome.evidencePath = $coreOutcome.Manifest
            $evidenceValues.verification = $coreOutcome.Manifest
            $assertions.verified = $true
        }
        elseif ($Scenario -eq "installer-upgrade-preserve") {
            $prior = Assert-CandidateIdentity -Path $PriorCandidateJson -AllowedClasses @("installer-product")
            $upgrade = Invoke-InstallerUpgradeScenario -CurrentPrepared $prepared -PriorIdentity $prior -Evidence $evidence
            $run = $upgrade.Run
            $runPath = $upgrade.RunPath
            Set-ObservedRun -Observed $observed -Run $run
            $observed.product.installRegistryPath = [string]$upgrade.InstallEvidence.RegistryPath
            $observed.installer.displayVersion = [string]$upgrade.InstallEvidence.DisplayVersion
            $observed.installer.productCode = [string]$upgrade.InstallEvidence.ProductCode
            $observed.installer.upgradeUuid = [string]$upgrade.UpgradeUuid
            $observed.installer.priorProductCode = [string]$upgrade.PriorInstallEvidence.ProductCode
            $observed.installer.candidateProductCode = [string]$upgrade.InstallEvidence.ProductCode
            $assertions.launched = $true
            $evidenceValues.launch = $runPath
            $phase = "verify"
            $observed.outcome.distributionStatus = "PASS"
            $observed.outcome.inferenceStatus = "NOT_REQUIRED"
            Assert-UpgradeSentinels -Sentinels $upgrade.Sentinels
            $observed.outcome.summary = "Prior $($prior.Value.releaseTag) upgraded to $($prepared.Candidate.Value.releaseTag) with Windows upgrade UUID $($upgrade.UpgradeUuid) and all sentinels preserved."
            $observed.outcome.evidencePath = $upgrade.Sentinels.ConfigPath
            $evidenceValues.verification = $upgrade.Sentinels.ConfigPath
            $assertions.verified = $true
        }
        else {
            $expectedClass = if ($Scenario -eq "installer-offline-first-run") { "installer-product" } else { "portable-product" }
            if ($Scenario -eq "variant-launch") { $expectedClass = [string]$prepared.Candidate.Value.artifact.class }
            Require-Value -Condition ([string]$prepared.Candidate.Value.artifact.class -eq $expectedClass) -Message "$Scenario received the wrong candidate class."
            $run = Start-PreparedProduct -PreparedIdentity $prepared -OutputRunJson $runPath -ScenarioId $Scenario -Evidence $evidence -Offline $true
            Set-ObservedRun -Observed $observed -Run $run
            $assertions.launched = $true
            $evidenceValues.launch = $runPath
            $phase = "verify"
            if ($Scenario -in @("portable-offline-first-run", "installer-offline-first-run")) {
                $oraclePath = Wait-EngineOracle -Path $EngineOracleFile -TimeoutSeconds $WaitSeconds
                $oracle = Assert-EngineOracle -Path $oraclePath -Run $run -RunPath $runPath
                $observed.outcome.distributionStatus = "PASS"
                $observed.outcome.inferenceStatus = "PASS"
                $observed.outcome.summary = "Bound complete engine-sgf real CPU oracle PASS."
                $observed.outcome.evidencePath = $oraclePath
                $evidenceValues.verification = $oraclePath
            }
            else {
                $observed.outcome.distributionStatus = "PASS"
                $observed.outcome.inferenceStatus = "BLOCKED"
                $observed.outcome.summary = "Distribution $($run.backend.readinessState); inference BLOCKED for backend $($run.backend.expected)."
                $observed.outcome.evidencePath = $run.applicationLog
                $evidenceValues.verification = $run.applicationLog
            }
            $assertions.verified = $true
        }

        $phase = "cleanup"
        $stopped = Stop-OwnedRun -Run $run -Path $runPath
        Set-ObservedRun -Observed $observed -Run $stopped
        Require-Value -Condition (@($stopped.network.blockedAttempts).Count -eq 0) -Message "Owned process attempted an external connection during the offline scenario."
        Require-Value -Condition (@($stopped.network.externalConnections).Count -eq 0) -Message "Owned process retained an external connection during the offline scenario."
        $cleanup.remainingOwnedResources = @(Get-CleanupRemainingResources -Cleanup $stopped.cleanup)
        $cleanup.complete = ($cleanup.remainingOwnedResources.Count -eq 0)
        if ($coreOutcome -and -not $KeepPreparedProduct) { Remove-Item -LiteralPath $coreOutcome.TargetRoot -Recurse -Force }
        $assertions.cleaned = [bool]$cleanup.complete
        $evidenceValues.cleanup = $runPath
        Require-Value -Condition $cleanup.complete -Message "Owned product cleanup did not complete."
        $status = "PASS"
    }
    catch {
        $caught = $_
        $summary = $caught.Exception.Message
        if ($caught.Exception.Data["StartupCleanup"]) {
            $startupCleanup = $caught.Exception.Data["StartupCleanup"]
            $startupCleanupPath = Join-Path $evidence "startup-cleanup.json"
            $cleanup.remainingOwnedResources = @(Get-CleanupRemainingResources -Cleanup $startupCleanup)
            $cleanup.complete = ($cleanup.remainingOwnedResources.Count -eq 0)
        }
        if ($run -and $run.state -eq "RUNNING") {
            try { $stopped = Stop-OwnedRun -Run $run -Path $runPath }
            catch { $summary = "$summary Cleanup: $($_.Exception.Message)"; $stopped = if ($run.state -eq "STOPPED") { $run } else { $null } }
            if ($stopped) {
                $cleanup.remainingOwnedResources = @(Get-CleanupRemainingResources -Cleanup $stopped.cleanup)
                $cleanup.complete = ($cleanup.remainingOwnedResources.Count -eq 0)
                $assertions.cleaned = [bool]$stopped.cleanup.complete
                $evidenceValues.cleanup = $runPath
                Set-ObservedRun -Observed $observed -Run $stopped
            }
            else { $cleanup.complete = $false; $assertions.cleaned = $false }
        }
        elseif ($run -and $run.state -eq "STOPPED") {
            $cleanup.remainingOwnedResources = @(Get-CleanupRemainingResources -Cleanup $run.cleanup)
            $cleanup.complete = ($cleanup.remainingOwnedResources.Count -eq 0)
            $assertions.cleaned = [bool]$run.cleanup.complete
            $evidenceValues.cleanup = $runPath
            Set-ObservedRun -Observed $observed -Run $run
        }
        $needsPreparedUninstall = $installerOwnershipExpected -and $Scenario -ne "installer-upgrade-preserve" -and -not $KeepPreparedProduct -and (-not $run -or $run.cleanup.uninstallComplete -ne $true)
        if ($needsPreparedUninstall -and $preparedInstallerOwnership) {
            try {
                Remove-PreparedInstallerOwnership -Ownership $preparedInstallerOwnership -Evidence $evidence -LogName "failed-run-uninstall.log"
                if ($startupCleanup) { $startupCleanup.uninstallComplete = $true }
                if (-not $startupCleanup -and -not $run) { $cleanup.complete = $true; $cleanup.remainingOwnedResources = @() }
            }
            catch {
                if ($startupCleanup) { $startupCleanup.uninstallComplete = $false; $startupCleanup.errors = @($startupCleanup.errors) + "prepared installer: $($_.Exception.Message)" }
                else { $cleanup.complete = $false; $cleanup.remainingOwnedResources += "prepared installer: $($_.Exception.Message)" }
            }
        }
        elseif ($needsPreparedUninstall) {
            $cleanup.complete = $false
            $cleanup.remainingOwnedResources += "prepared installer ownership unavailable"
        }
        elseif (-not $prepared -and -not $run -and -not $installerOwnershipExpected) { $cleanup.complete = $true; $cleanup.remainingOwnedResources = @() }
        elseif ($prepared -and $prepared.Candidate.Value.artifact.class -ne "installer-product" -and -not $run -and -not $startupCleanup) { $cleanup.complete = $true; $cleanup.remainingOwnedResources = @() }
        $coreCleanupTarget = if ($coreOutcome) { [string]$coreOutcome.TargetRoot } else { $null }
        if ($coreCleanupTarget -and -not $KeepPreparedProduct -and (Test-Path -LiteralPath $coreCleanupTarget)) {
            try { Remove-Item -LiteralPath $coreCleanupTarget -Recurse -Force -ErrorAction Stop }
            catch { $cleanup.complete = $false; $cleanup.remainingOwnedResources += $coreCleanupTarget; $assertions.cleaned = $false }
        }
        if ($startupCleanup) {
            $startupRemaining = @(Get-CleanupRemainingResources -Cleanup $startupCleanup)
            $startupCleanup.complete = ($startupRemaining.Count -eq 0)
            Write-JsonAtomic -Path $startupCleanupPath -Value $startupCleanup
            $cleanup.complete = [bool]$startupCleanup.complete
            $cleanup.remainingOwnedResources = $startupRemaining
        }
        if ($caught.Exception.Data["WindowsAcceptanceBlocked"]) {
            $status = "BLOCKED"
            $blockedPhase = [string]$caught.Exception.Data["BlockedPhase"]
            $blockReason = $summary
        }
        else {
            $status = "FAIL"
            if ($phase -eq "identity") { $assertions.identity = $false }
            elseif ($phase -eq "prepare") { $assertions.prepared = $false }
            elseif ($phase -eq "launch") { $assertions.launched = $false }
            elseif ($phase -eq "verify") { $assertions.verified = $false }
            elseif ($phase -eq "cleanup") { $assertions.cleaned = $false }
            $diagnostics = @($recordPath)
            if ($prepared) { $diagnostics += [string]$prepared.PreparedPath }
            if (Test-Path -LiteralPath $runPath) { $diagnostics += $runPath }
            if ($startupCleanupPath -and (Test-Path -LiteralPath $startupCleanupPath -PathType Leaf)) { $diagnostics += $startupCleanupPath }
            $failure = [ordered]@{ kind = if ($summary -match '(?i)timed out') { "timeout" } elseif ($summary -match '(?i)(did not|differs|mismatch|unexpected|requires|drift)') { "assertion" } else { "error" }; summary = $summary; diagnostics = @($diagnostics) }
        }
    }
    if ($phase -ne "cleanup") {
        $assertions.cleaned = $null
        $evidenceValues.cleanup = $null
        $observed.stop.state = $null
        $observed.stop.remainingPids = $null
        $observed.stop.firewallRulesRemaining = $null
        $observed.stop.errors = $null
    }
    if (Test-Path -LiteralPath $runPath -PathType Leaf) { $observed.logs.runRecord = $runPath }
    $record = New-AcceptanceRecord -ScenarioId $Scenario -Status $status -Phase $phase -Candidate $requested -ExpectedProduct $expectedProduct -Observed $observed -Assertions $assertions -Evidence $evidenceValues -Cleanup $cleanup -StartedAt $startedAt -Failure $failure -Reason $blockReason -BlockedPhase $blockedPhase
    Write-JsonAtomic -Path $recordPath -Value $record
    if ($status -eq "BLOCKED") { throw "Windows product acceptance BLOCKED: $blockReason Evidence: $recordPath" }
    if ($status -eq "FAIL") { throw "Windows product acceptance failed: $($failure.summary). Evidence: $recordPath" }
    Write-Host "PASS $Scenario $recordPath"
}

if ($MyInvocation.InvocationName -ne '.') {
    switch ($Command) {
        "Prepare" { Invoke-Prepare }
        "Start" { Invoke-Start }
        "Status" { Invoke-Status }
        "Stop" { Invoke-Stop }
        "Run" { Invoke-Run }
    }
}
