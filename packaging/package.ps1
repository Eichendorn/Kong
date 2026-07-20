<#
.SYNOPSIS
    Build a self-contained Windows installer (.msi) for Kong.

.DESCRIPTION
    Produces dist\Kong-<version>.msi — a native installer that bundles a trimmed
    Java runtime, so end users need nothing pre-installed. It:
      1. builds the fat jar with Maven (target\kong.jar),
      2. runs jpackage to wrap it, the bundled runtime, and a Start-Menu
         shortcut into an MSI.

    Run this on a WINDOWS machine (jpackage cannot cross-compile). See
    docs\INSTALL.md for the full prerequisites and distribution steps.

.PARAMETER Version
    Override the installer version. Defaults to the <version> in pom.xml.

.PARAMETER SkipBuild
    Skip the Maven build and reuse the existing target\kong.jar.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File packaging\package.ps1
#>
[CmdletBinding()]
param(
    [string]$Version,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

# --- Locate the project root (this script lives in <root>\packaging) ----------
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
Write-Host "Project root: $Root"

# --- Require a JDK 21+ with jpackage -----------------------------------------
function Resolve-Tool($name) {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\$name.exe"
        if (Test-Path $candidate) { return $candidate }
    }
    $cmd = Get-Command "$name.exe" -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "$name not found. Install a JDK 21+ and set JAVA_HOME (jpackage ships with it)."
}
$jpackage = Resolve-Tool 'jpackage'
Write-Host "Using jpackage: $jpackage"

# --- Resolve the version from pom.xml if not given ---------------------------
if (-not $Version) {
    $pom = [xml](Get-Content (Join-Path $Root 'pom.xml'))
    $Version = $pom.project.version
}
if (-not $Version) { throw "Could not determine version." }
Write-Host "Packaging Kong $Version"

# --- Build the fat jar --------------------------------------------------------
if (-not $SkipBuild) {
    $mvn = Get-Command 'mvn.cmd','mvn' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $mvn) { throw "Maven (mvn) not found on PATH." }
    Write-Host "Building jar with Maven..."
    & $mvn.Source -q -f (Join-Path $Root 'pom.xml') clean package
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }
}
$jar = Join-Path $Root 'target\kong.jar'
if (-not (Test-Path $jar)) { throw "Expected $jar — build first or drop -SkipBuild." }

# --- Package with jpackage ----------------------------------------------------
$dist = Join-Path $Root 'dist'
if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
New-Item -ItemType Directory -Path $dist | Out-Null

# A generous-but-trimmed module set: java.se covers the standard API surface
# (desktop/xml/sql/naming/…); the jdk.* extras cover TLS, sun.misc.Unsafe (used
# by Jetty), zip filesystems, and locale data. If a future dependency needs more,
# add the module here.
$modules = 'java.se,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs,jdk.management,jdk.localedata'

$icon = Join-Path $PSScriptRoot 'kong.ico'

$jpArgs = @(
    '--type', 'msi',
    '--name', 'Kong',
    '--app-version', $Version,
    '--vendor', 'FNBA',
    '--description', 'Kong - Jira task manager',
    '--input', (Join-Path $Root 'target'),
    '--main-jar', 'kong.jar',
    '--main-class', 'com.fnba.kong.App',
    '--add-modules', $modules,
    '--java-options', '-Xmx512m',
    '--dest', $dist,
    '--win-menu',
    '--win-menu-group', 'Kong',
    '--win-shortcut',
    '--win-dir-chooser',
    '--win-per-user-install'
)
if (Test-Path $icon) { $jpArgs += @('--icon', $icon) }

Write-Host "Running jpackage (this bundles a JRE and may take a minute)..."
& $jpackage @jpArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed (is the WiX Toolset installed and on PATH?)." }

$msi = Get-ChildItem $dist -Filter '*.msi' | Select-Object -First 1
if (-not $msi) { throw "jpackage reported success but no .msi was produced." }

# Normalise the name to Kong-<version>.msi for a predictable download filename.
$target = Join-Path $dist "Kong-$Version.msi"
if ($msi.FullName -ne $target) { Move-Item $msi.FullName $target -Force }
Write-Host ""
Write-Host "Built: $target" -ForegroundColor Green
