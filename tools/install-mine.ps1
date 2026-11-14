<#
Builds the debug app and installs it on one phone, then loads the owner's Bluetooth key into it,
so the personal setup never has to be typed. The key stays in .secrets\ (never committed).

  .\tools\install-mine.ps1 -Device <phone-ip>:<port>
  .\tools\install-mine.ps1 -Device <phone-ip>:<port> -GrillAddress AA:BB:CC:DD:EE:FF -SkipBuild

-Device is required on purpose: other phones and TVs on ADB must never get it by mistake.
An update keeps the app's data (login, key, thermometer), so the key step only matters on a fresh install.
#>
param(
    [Parameter(Mandatory = $true)][string]$Device,
    [string]$GrillAddress,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'

if (-not $SkipBuild) {
    Push-Location $root
    try { & .\gradlew.bat assembleDebug -q; if ($LASTEXITCODE -ne 0) { throw 'build failed' } } finally { Pop-Location }
}
& $adb -s $Device install -r (Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk')
if ($LASTEXITCODE -ne 0) { throw 'install failed' }

$keyFile = Join-Path $root '.secrets\key_bundle.txt'
if (Test-Path $keyFile) {
    $key = (Get-Content $keyFile -Raw).Trim()
    $extra = @('--es', 'key_bundle', $key)
    if ($GrillAddress) { $extra += @('--es', 'grill_address', $GrillAddress) }
    & $adb -s $Device shell am start -n com.example.mygrill/.MainActivity @extra | Out-Null
    'Installed and loaded the Bluetooth key.'
} else {
    'Installed. No .secrets\key_bundle.txt here, so no key was loaded.'
}
