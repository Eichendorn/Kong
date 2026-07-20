<#
.SYNOPSIS
    Copy a Kong installer to an internal network/file share so coworkers can
    download it without needing GitHub access.

.DESCRIPTION
    Two sources are supported:
      * -Msi <path>  : copy a specific local .msi (e.g. one you just built with
                       package.ps1 into dist\).
      * (default)    : download the latest GitHub Release asset with the GitHub
                       CLI (gh) and copy that. Requires `gh auth login` once.

    The file is copied as Kong-<version>.msi and a stable Kong-latest.msi pointer
    is refreshed so you can always link people to the same "latest" path.

.PARAMETER Share
    Destination folder (default: V:\Encompass\Kong). May be a mapped drive or a
    UNC path such as \\server\Encompass\Kong.

.PARAMETER Msi
    Optional local .msi to publish instead of downloading from GitHub.

.PARAMETER Repo
    GitHub repo to pull the latest release from (default: cg-fnba/Kong).

.EXAMPLE
    # Publish the installer you just built to the default share:
    packaging\publish-to-share.ps1 -Msi dist\Kong-1.1.22.msi

.EXAMPLE
    # Pull the latest GitHub release and publish it to the default share:
    packaging\publish-to-share.ps1
#>
[CmdletBinding()]
param(
    [string]$Share = 'V:\Encompass\Kong',
    [string]$Msi,
    [string]$Repo = 'cg-fnba/Kong'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $Share)) {
    throw "Share path not reachable: $Share (check the path and your permissions)."
}

# --- Obtain the .msi ----------------------------------------------------------
$tempDownload = $null
if ($Msi) {
    if (-not (Test-Path $Msi)) { throw "Installer not found: $Msi" }
    $source = (Resolve-Path $Msi).Path
} else {
    $gh = Get-Command 'gh.exe','gh' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $gh) { throw "GitHub CLI (gh) not found. Install it, or pass -Msi <path>." }
    $tempDownload = Join-Path $env:TEMP ("kong-release-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tempDownload | Out-Null
    Write-Host "Downloading latest release asset from $Repo ..."
    & $gh.Source release download --repo $Repo --pattern 'Kong-*.msi' --dir $tempDownload
    if ($LASTEXITCODE -ne 0) { throw "gh release download failed." }
    $source = (Get-ChildItem $tempDownload -Filter 'Kong-*.msi' | Select-Object -First 1).FullName
    if (-not $source) { throw "No Kong-*.msi asset found in the latest release." }
}

$fileName = Split-Path $source -Leaf
$destVersioned = Join-Path $Share $fileName
$destLatest    = Join-Path $Share 'Kong-latest.msi'

# --- Copy: the versioned file, plus a stable "latest" pointer ----------------
Write-Host "Copying $fileName -> $Share"
Copy-Item $source $destVersioned -Force
Copy-Item $source $destLatest -Force

if ($tempDownload) { Remove-Item $tempDownload -Recurse -Force }

Write-Host ""
Write-Host "Published:" -ForegroundColor Green
Write-Host "  $destVersioned"
Write-Host "  $destLatest  (stable 'latest' link for coworkers)"
