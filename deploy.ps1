# Deploys the built plugin jar into the EDT dropins folder.
# May require an elevated (administrator) shell because EDT lives in Program Files.
param(
    [string]$EdtHome = "C:\Program Files\1C\1CE\components\1c-edt-2025.2.6+4-x86_64"
)
$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$dist = Join-Path $root "dist"
$jar = Get-ChildItem $dist -Filter "com.polischuk.edt.prl.server_*.jar" | Select-Object -First 1
if (-not $jar) { throw "No built jar in $dist - run build.ps1 first" }

$dropins = Join-Path $EdtHome "dropins"
if (-not (Test-Path $dropins)) { New-Item -ItemType Directory -Force $dropins | Out-Null }

# remove older versions; a version loaded by a running EDT is locked - deploy alongside,
# Equinox resolves the singleton bundle to the highest version on restart
Get-ChildItem $dropins -Filter "com.polischuk.edt.prl.server_*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -ne $jar.Name } |
    ForEach-Object {
        try { Remove-Item $_.FullName -Force -ErrorAction Stop; Write-Host "Removed old $($_.Name)" }
        catch { Write-Host "WARN: $($_.Name) is locked by running EDT - remove it after restart" }
    }

Copy-Item $jar.FullName $dropins -Force
Write-Host "Deployed $($jar.Name) to $dropins"
Write-Host "Restart 1C:EDT (with -clean once, if the plugin does not show up)."
