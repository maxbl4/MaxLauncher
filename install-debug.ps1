<#
.SYNOPSIS
    Build the debug APK, install it on a connected device and make it the default launcher.

.DESCRIPTION
    Sets JAVA_HOME to Android Studio's bundled JBR, runs `gradlew :app:assembleDebug`,
    installs the APK with adb, tries to set MaxLauncher as the default home app and
    presses Home. If the device refuses the shell command (some MIUI/HyperOS builds do),
    the "Default home app" settings screen is opened instead.

.PARAMETER Serial
    adb serial of the target device. Optional when exactly one device/emulator is connected.

.PARAMETER NoDefault
    Install only; do not change the default launcher.

.PARAMETER Release
    Build and install the optimized release build (much smoother UI than debug).

.EXAMPLE
    ./install-debug.ps1

.EXAMPLE
    ./install-debug.ps1 -Release
#>
param(
    [string]$Serial,
    [switch]$NoDefault,
    [switch]$Release
)

$ErrorActionPreference = 'Stop'
$projectDir = $PSScriptRoot
$homeActivity = 'com.maxbl4.maxlauncher/.MainActivity'

# --- JDK: use Android Studio's JBR (system Java is often too old for AGP) ---
$jbr = 'C:\Program Files\Android\Android Studio\jbr'
if (Test-Path "$jbr\bin\java.exe") {
    $env:JAVA_HOME = $jbr
} elseif (-not $env:JAVA_HOME) {
    throw "JAVA_HOME is not set and Android Studio JBR was not found at '$jbr'."
}

# --- Locate adb ---
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "adb not found at '$adb'. Set ANDROID_SDK_ROOT or install platform-tools." }

# --- Resolve target device ---
$devices = @(& $adb devices |
    Select-Object -Skip 1 |
    Where-Object { $_ -match "`tdevice$" } |
    ForEach-Object { ($_ -split "`t")[0] })

if ($devices.Count -eq 0) {
    throw "No authorized devices connected. Check the USB cable and that USB debugging is enabled/authorized."
}
if ($Serial) {
    if ($devices -notcontains $Serial) {
        throw "Device '$Serial' is not connected. Connected: $($devices -join ', ')."
    }
} elseif ($devices.Count -eq 1) {
    $Serial = $devices[0]
} else {
    throw "Multiple devices connected: $($devices -join ', '). Re-run with -Serial <serial>."
}

# --- Build ---
$variant = if ($Release) { 'release' } else { 'debug' }
Write-Host "Building $variant APK (JAVA_HOME=$env:JAVA_HOME)..." -ForegroundColor Cyan
& "$projectDir\gradlew.bat" ":app:assemble$((Get-Culture).TextInfo.ToTitleCase($variant))" --console=plain
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit $LASTEXITCODE)." }

$apk = Join-Path $projectDir "app\build\outputs\apk\$variant\app-$variant.apk"
if (-not (Test-Path $apk)) { throw "APK not found at '$apk'." }

# --- Install ---
Write-Host "Installing to $Serial ..." -ForegroundColor Cyan
& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed (exit $LASTEXITCODE)." }

# --- Default launcher ---
if (-not $NoDefault) {
    $result = & $adb -s $Serial shell cmd package set-home-activity $homeActivity 2>&1
    if ($LASTEXITCODE -eq 0 -and "$result" -match 'Success') {
        & $adb -s $Serial shell input keyevent KEYCODE_HOME
        Write-Host "MaxLauncher is now the default launcher." -ForegroundColor Green
    } else {
        Write-Host "Could not set the default launcher from adb ($result)." -ForegroundColor Yellow
        Write-Host "Opening the 'Default home app' screen on the device; pick MaxLauncher there." -ForegroundColor Yellow
        & $adb -s $Serial shell am start -a android.settings.HOME_SETTINGS | Out-Null
    }
}

Write-Host "Done: installed MaxLauncher on $Serial." -ForegroundColor Green
