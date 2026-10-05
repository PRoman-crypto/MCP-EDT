# Builds a p2 update site for the MCP:PRL plugin WITHOUT Maven/Tycho:
# generates feature.xml, packs feature jar, lays out site/, then runs the
# headless p2 FeaturesAndBundlesPublisher that ships inside EDT itself.
# Output: .\site  (install in EDT via Help > Install New Software > Local)
param(
    [string]$EdtHome = "C:\Program Files\1C\1CE\components\1c-edt-2025.2.6+4-x86_64"
)
$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$bundleId = "com.polischuk.edt.prl.server"
$featureId = "com.polischuk.edt.prl.feature"

# version from the built jar in dist
$jar = Get-ChildItem (Join-Path $root "dist") -Filter ($bundleId + "_*.jar") | Select-Object -First 1
if (-not $jar) { throw "No built plugin jar in dist - run build.ps1 first" }
$version = ($jar.BaseName -split "_")[-1]

$work = Join-Path $env:TEMP ("prl-site-" + [guid]::NewGuid().ToString("N").Substring(0,8))
$siteSrc = Join-Path $work "src"
New-Item -ItemType Directory -Force (Join-Path $siteSrc "plugins"), (Join-Path $siteSrc "features") | Out-Null

# feature.xml
$featureXml = @"
<?xml version="1.0" encoding="UTF-8"?>
<feature id="$featureId" label="MCP:PRL Server" version="$version" provider-name="Polischuk">
   <description>MCP (Model Context Protocol) HTTP server inside 1C:EDT for AI clients (Claude Code, Cursor, Windsurf...).</description>
   <copyright>Polischuk</copyright>
   <license url="">All rights reserved.</license>
   <plugin id="$bundleId" download-size="0" install-size="0" version="$version" unpack="false"/>
</feature>
"@
$featureDir = Join-Path $work "feature"
New-Item -ItemType Directory -Force $featureDir | Out-Null
Set-Content -Path (Join-Path $featureDir "feature.xml") -Value $featureXml -Encoding UTF8

$javac = (Get-Command javac -ErrorAction SilentlyContinue).Source
$jarTool = Join-Path (Split-Path $javac) "jar.exe"
$featureJar = Join-Path $siteSrc ("features\" + $featureId + "_" + $version + ".jar")
& $jarTool --create --file $featureJar -C $featureDir feature.xml
if ($LASTEXITCODE -ne 0) { throw "feature jar failed" }

Copy-Item $jar.FullName (Join-Path $siteSrc "plugins")

# headless p2 publisher from EDT (separate -data, does not touch the running IDE)
$site = Join-Path $root "site"
if (Test-Path $site) { Remove-Item $site -Recurse -Force }
$launcher = (Get-ChildItem (Join-Path $EdtHome "plugins") -Filter "org.eclipse.equinox.launcher_*.jar" | Select-Object -First 1).FullName
$javaExe = Join-Path (Split-Path $javac) "java.exe"
& $javaExe -jar $launcher -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher `
    -nosplash -consoleLog -data (Join-Path $work "ws") `
    -metadataRepository ("file:///" + ($site -replace "\\", "/")) `
    -artifactRepository ("file:///" + ($site -replace "\\", "/")) `
    -source $siteSrc -publishArtifacts -compress `
    -repositoryName "MCP:PRL Server Update Site"
if ($LASTEXITCODE -ne 0) { throw "p2 publisher failed with exit code $LASTEXITCODE" }

Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "Update site published: $site (version $version)"
Write-Host "Install in EDT: Help > Install New Software > Add > Local > $site"
