<#
.SYNOPSIS
    Re-signs a release APK with the release key and the key rotation proof (lineage).

.DESCRIPTION
    The first builds were signed with the debug key of this PC. Releases are now signed with
    a separate key; Android accepts them as updates of the debug-signed installs because the
    APK carries the lineage "debug key -> release key" (APK signature scheme v3, Android 9+).
    The lineage still lets debug builds install over release ones (rollback is allowed).

    Everything lives in %USERPROFILE%\.android-release (back it up, without it the apps
    cannot be updated any more):
      maxbl4-release.jks        release key (alias in signing.properties)
      debug.keystore            the old (debug) key, needed to sign with the lineage
      debug-to-release.lineage  made by `apksigner rotate`
      signing.properties        paths and the password; Gradle reads it too

    Without that folder the APK is left as Gradle signed it.

.PARAMETER Apk
    The APK to re-sign in place.
#>
param(
    [Parameter(Mandatory)][string]$Apk
)

$ErrorActionPreference = 'Stop'
$propsFile = Join-Path $env:USERPROFILE '.android-release\signing.properties'
if (-not (Test-Path $propsFile)) {
    Write-Host "No release key ($propsFile): the APK stays signed as Gradle built it." -ForegroundColor Yellow
    return
}
$props = @{}
foreach ($line in Get-Content $propsFile) {
    if ($line -match '^\s*([^#=]+?)\s*=\s*(.*)$') { $props[$Matches[1]] = $Matches[2] }
}

$jbr = 'C:\Program Files\Android\Android Studio\jbr'
if (Test-Path "$jbr\bin\java.exe") { $env:JAVA_HOME = $jbr }
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$apksigner = Get-ChildItem "$sdk\build-tools\*\apksigner.bat" | Sort-Object { [version]$_.Directory.Name } | Select-Object -Last 1
if (-not $apksigner) { throw "apksigner not found under '$sdk\build-tools'." }

$signed = "$Apk.signed"
$env:MAXBL4_RELEASE_PASS = $props['password']
try {
    # --rotation-min-sdk-version 31: the new key applies on every supported Android version
    # (minSdk 31); by default apksigner would rotate only from Android 13.
    & $apksigner.FullName sign `
        --ks $props['oldStoreFile'] --ks-key-alias androiddebugkey --ks-pass pass:android `
        --next-signer --ks $props['storeFile'] --ks-key-alias $props['keyAlias'] --ks-pass env:MAXBL4_RELEASE_PASS `
        --lineage $props['lineageFile'] --rotation-min-sdk-version 31 --v4-signing-enabled false `
        --out $signed $Apk
    if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed (exit $LASTEXITCODE)." }
} finally {
    Remove-Item Env:\MAXBL4_RELEASE_PASS
}
Move-Item -Force $signed $Apk
Write-Host 'Signed with the release key (rotated from the debug key).' -ForegroundColor Cyan
