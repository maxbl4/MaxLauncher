<#
.SYNOPSIS
    Builds the release APK and publishes it on https://apps.braaap.ru.

.DESCRIPTION
    Runs `gradlew :app:assembleRelease`, then uploads the APK and an info.json (version,
    date, commit, size, SHA-256) to /var/www/apps.braaap.ru/<app>/ on the server; the page
    there shows them. The files are replaced atomically, so a download never gets half a
    file. Only committed code is published: with uncommitted changes the last commit is
    built in a temporary git worktree. The site itself is set up by
    braaap-migration\apps.braaap.ru\setup.ps1.

.PARAMETER SkipBuild
    Publish the APK that is already built (marked -dirty if the working tree has changes).

.EXAMPLE
    ./publish.ps1
#>
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$App = 'MaxLauncher'
$Slug = 'maxlauncher'
$Server = 'root@braaap.ru'
$RemoteDir = "/var/www/apps.braaap.ru/$Slug"
$SshOpt = '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=20'
$projectDir = $PSScriptRoot
$commit = (& git -C $projectDir rev-parse --short HEAD).Trim()
$dirty = [bool](& git -C $projectDir status --porcelain)

# --- Build ---
# Only committed code is published: with uncommitted changes the last commit is built in a
# temporary worktree and the working tree is left alone.
$buildDir = $projectDir
$worktree = $null
if (-not $SkipBuild) {
    $jbr = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path "$jbr\bin\java.exe") { $env:JAVA_HOME = $jbr }
    if ($dirty) {
        Write-Host "Uncommitted changes are not published; building commit $commit in a temporary worktree." -ForegroundColor Yellow
        $worktree = Join-Path ([IO.Path]::GetTempPath()) "$Slug-publish-$commit"
        if (Test-Path $worktree) { & git -C $projectDir worktree remove --force $worktree }
        & git -C $projectDir worktree add --detach $worktree HEAD
        if ($LASTEXITCODE -ne 0) { throw "git worktree add failed (exit $LASTEXITCODE)." }
        Copy-Item (Join-Path $projectDir 'local.properties') $worktree -ErrorAction SilentlyContinue
        $buildDir = $worktree
        $dirty = $false
    }
    Write-Host "Building release APK..." -ForegroundColor Cyan
    & "$buildDir\gradlew.bat" -p $buildDir ':app:assembleRelease' --console=plain
    $buildExit = $LASTEXITCODE
    if ($buildExit -ne 0) {
        if ($worktree) { & git -C $projectDir worktree remove --force $worktree }
        throw "Gradle build failed (exit $buildExit)."
    }
} elseif ($dirty) {
    Write-Host 'Warning: publishing the APK built from a working tree with uncommitted changes.' -ForegroundColor Yellow
    $commit += '-dirty'
}
$apk = Join-Path $buildDir 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { throw "APK not found at '$apk'." }
& "$projectDir\sign-release.ps1" -Apk $apk

# --- Version from the APK itself ---
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$aapt2 = Get-ChildItem "$sdk\build-tools\*\aapt2.exe" | Sort-Object { [version]$_.Directory.Name } | Select-Object -Last 1
if (-not $aapt2) { throw "aapt2 not found under '$sdk\build-tools'." }
$badging = (& $aapt2.FullName dump badging $apk) -join "`n"
if ($badging -notmatch "versionCode='(\d+)' versionName='([^']*)'") { throw 'Cannot read the version from the APK.' }
$versionCode = [int]$Matches[1]
$versionName = $Matches[2]

$info = [ordered]@{
    name        = $App
    file        = "$App.apk"
    versionName = $versionName
    versionCode = $versionCode
    commit      = $commit
    date        = (Get-Date).ToString('o')
    size        = (Get-Item $apk).Length
    sha256      = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant()
}
$infoFile = Join-Path ([IO.Path]::GetTempPath()) "$Slug-info.json"
# UTF-8 without BOM, LF: the page parses it with fetch().json()
[IO.File]::WriteAllText($infoFile, ($info | ConvertTo-Json) + "`n", (New-Object Text.UTF8Encoding $false))

# --- Upload: to temporary names first, then rename in place ---
Write-Host "Uploading $App $versionName ($commit)..." -ForegroundColor Cyan
try {
    & ssh -n @SshOpt $Server "mkdir -p $RemoteDir"
    if ($LASTEXITCODE -ne 0) { throw "ssh failed (exit $LASTEXITCODE)." }
    & scp @SshOpt $apk "${Server}:$RemoteDir/$App.apk.part"
    if ($LASTEXITCODE -ne 0) { throw "scp failed (exit $LASTEXITCODE)." }
    & scp @SshOpt $infoFile "${Server}:$RemoteDir/info.json.part"
    if ($LASTEXITCODE -ne 0) { throw "scp failed (exit $LASTEXITCODE)." }
    & ssh -n @SshOpt $Server "cd $RemoteDir && chmod 644 $App.apk.part info.json.part && mv -f $App.apk.part $App.apk && mv -f info.json.part info.json"
    if ($LASTEXITCODE -ne 0) { throw "Publishing on the server failed (exit $LASTEXITCODE)." }
} finally {
    Remove-Item $infoFile -ErrorAction SilentlyContinue
    if ($worktree) { & git -C $projectDir worktree remove --force $worktree }
}

Write-Host "Published: https://apps.braaap.ru/$Slug/$App.apk ($versionName, $([math]::Round($info.size / 1MB, 1)) MB)" -ForegroundColor Green
