<#
.SYNOPSIS
    Copies the home screen layout of Samsung One UI Home into MaxLauncher.

.DESCRIPTION
    One UI Home does not share its layout with other apps, so this script reads it from the
    screen over adb: it opens One UI Home, goes through every page (tapping the page indicator),
    opens every folder, reads the dock, and builds a layout file. Then it opens MaxLauncher's
    import screen, where nothing changes until you confirm.

    Do not touch the phone while the script runs (a minute or two). The phone must be unlocked.

.PARAMETER Serial
    adb serial of the phone. Optional when exactly one device is connected.

.PARAMETER DryRun
    Only read the layout and save it (tools\oneui-layout.json); do not open MaxLauncher.

.EXAMPLE
    ./tools/import-oneui.ps1
#>
param(
    [string]$Serial,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$launcher = 'com.sec.android.app.launcher'
$work = Join-Path ([System.IO.Path]::GetTempPath()) 'maxlauncher-import'
New-Item -ItemType Directory -Force $work | Out-Null

# --- adb and device ---
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adbExe = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adbExe)) { throw "adb not found at '$adbExe'." }
$devices = @(& $adbExe devices | Select-Object -Skip 1 | Where-Object { $_ -match "`tdevice$" } | ForEach-Object { ($_ -split "`t")[0] })
if (-not $Serial) {
    if ($devices.Count -ne 1) { throw "Connect exactly one device or pass -Serial (connected: $($devices -join ', '))." }
    $Serial = $devices[0]
}
function Adb { & $adbExe -s $Serial @args }

if ((Adb shell dumpsys window) -match 'isKeyguardShowing=true') { throw 'Unlock the phone first.' }

# --- reading the screen ---
$dumpCount = 0
function Read-Screen {
    $script:dumpCount++
    Adb shell uiautomator dump /sdcard/maxlauncher-import.xml | Out-Null
    $local = Join-Path $work "screen$($script:dumpCount).xml"
    Adb pull /sdcard/maxlauncher-import.xml $local | Out-Null
    [xml](Get-Content -Raw -Encoding UTF8 $local)
}

function Get-Bounds($node) {
    $m = [regex]::Match($node.bounds, '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
    [pscustomobject]@{
        L = [int]$m.Groups[1].Value; T = [int]$m.Groups[2].Value
        R = [int]$m.Groups[3].Value; B = [int]$m.Groups[4].Value
    }
}

function Tap($bounds) {
    Adb shell input tap ([int](($bounds.L + $bounds.R) / 2)) ([int](($bounds.T + $bounds.B) / 2)) | Out-Null
}

function Id($name) { "${launcher}:id/$name" }

# Icon label: the TextView inside the icon; its content-desc may carry extra words.
function Get-Label($iconNode) {
    $text = $iconNode.SelectSingleNode(".//node[@resource-id='$(Id 'icon')']")
    if ($text -and $text.text) { return $text.text.Trim() }
    return ($iconNode.'content-desc' -replace ',\s*Папка$', '' -replace ',\s*Folder$', '').Trim()
}

function Get-Icons($container) {
    @($container.SelectNodes(".//node[@resource-id='$(Id 'icon_container')' or @resource-id='$(Id 'folder_icon_container')']")) |
        Sort-Object { (Get-Bounds $_).T }, { (Get-Bounds $_).L }
}

# Opens a folder (tapping its icon), reads its title and apps, closes it.
function Read-Folder($folderNode) {
    Tap (Get-Bounds $folderNode)
    Start-Sleep -Milliseconds 1200
    $doc = Read-Screen
    $title = $doc.SelectSingleNode("//node[@resource-id='$(Id 'folder_title')']").text
    $apps = [System.Collections.Generic.List[string]]::new()
    $pages = 1
    # Folders with many apps have pages ("Страница 1 из 2").
    foreach ($n in $doc.SelectNodes('//node[@content-desc]')) {
        if ($n.'content-desc' -match '^(?:Страница|Page)\s+\d+\D+(\d+)') { $pages = [Math]::Max($pages, [int]$Matches[1]) }
    }
    for ($p = 0; $p -lt $pages; $p++) {
        if ($p -gt 0) {
            Adb shell input swipe 900 1150 180 1150 250 | Out-Null
            Start-Sleep -Milliseconds 900
            $doc = Read-Screen
        }
        foreach ($icon in Get-Icons $doc) {
            $label = Get-Label $icon
            if ($label -and -not $apps.Contains($label)) { $apps.Add($label) }
        }
    }
    Adb shell input keyevent KEYCODE_BACK | Out-Null
    Start-Sleep -Milliseconds 800
    if (-not $title) { $title = Get-Label $folderNode }
    [pscustomobject]@{ Title = $title.Trim(); Apps = $apps }
}

function Read-Icon($node, $grid, $cell) {
    $b = Get-Bounds $node
    $item = [ordered]@{
        type  = 'app'
        label = Get-Label $node
        x     = [int][Math]::Floor(($b.L - $grid.L + $cell.W / 2) / $cell.W)
        y     = [int][Math]::Floor(($b.T - $grid.T + $cell.H / 2) / $cell.H)
    }
    if ($node.'resource-id' -eq (Id 'folder_icon_container')) {
        $folder = Read-Folder $node
        $item.type = 'folder'
        $item.label = $folder.Title
        $item.apps = $folder.Apps
    }
    $item
}

# --- One UI Home ---
Write-Host 'Opening One UI Home...' -ForegroundColor Cyan
Adb shell am start -n "$launcher/.activities.LauncherActivity" | Out-Null
Start-Sleep -Seconds 2
Adb shell input keyevent KEYCODE_BACK | Out-Null   # closes an open folder or the apps screen
Start-Sleep -Milliseconds 800

$doc = Read-Screen
$dots = @($doc.SelectNodes("//node[@resource-id='$(Id 'workspace_page_indicator')']//node[@content-desc!='']")) |
    Sort-Object { (Get-Bounds $_).L }
$pageCount = [Math]::Max(1, $dots.Count)
$homePage = 0
for ($i = 0; $i -lt $dots.Count; $i++) { if ($dots[$i].'content-desc' -match 'умолчанию|default') { $homePage = $i } }
Write-Host "Pages: $pageCount, home page: $($homePage + 1)"

$pages = [System.Collections.Generic.List[object]]::new()
$cell = $null
$grid = $null
for ($i = 0; $i -lt $pageCount; $i++) {
    if ($pageCount -gt 1) {
        Tap (Get-Bounds $dots[$i])
        Start-Sleep -Milliseconds 1200
        $doc = Read-Screen
    }
    $gridNode = @($doc.SelectNodes("//node[@resource-id='$(Id 'wsCellLayout')']")) |
        Where-Object { $b = Get-Bounds $_; $b.L -ge 0 -and $b.L -lt 540 } | Select-Object -First 1
    if (-not $gridNode) { throw "Page $($i + 1): the home screen grid was not found. Is One UI Home on screen?" }
    $grid = Get-Bounds $gridNode
    $icons = @(Get-Icons $gridNode)
    if ($icons.Count -gt 0 -and -not $cell) {
        $widths = $icons | ForEach-Object { $b = Get-Bounds $_; $b.R - $b.L } | Sort-Object
        $heights = $icons | ForEach-Object { $b = Get-Bounds $_; $b.B - $b.T } | Sort-Object
        $cell = [pscustomobject]@{ W = $widths[0]; H = $heights[0] }
    }
    $pageCell = if ($cell) { $cell } else { [pscustomobject]@{ W = ($grid.R - $grid.L) / 4; H = ($grid.B - $grid.T) / 6 } }

    $items = [System.Collections.Generic.List[object]]::new()
    foreach ($w in @($gridNode.SelectNodes(".//node[@class='android.appwidget.AppWidgetHostView']"))) {
        $b = Get-Bounds $w
        $items.Add([ordered]@{
            type  = 'widget'
            label = $w.'content-desc'.Trim()
            x     = [int][Math]::Floor(($b.L - $grid.L + $pageCell.W / 2) / $pageCell.W)
            y     = [int][Math]::Floor(($b.T - $grid.T + $pageCell.H / 2) / $pageCell.H)
            spanX = [Math]::Max(1, [int][Math]::Round(($b.R - $b.L) / $pageCell.W))
            spanY = [Math]::Max(1, [int][Math]::Round(($b.B - $b.T) / $pageCell.H))
        })
    }
    foreach ($icon in $icons) { $items.Add((Read-Icon $icon $grid $pageCell)) }
    Write-Host ("  page {0}: {1} items" -f ($i + 1), $items.Count)
    $pages.Add([ordered]@{ items = $items })
}
if (-not $cell) { $cell = [pscustomobject]@{ W = ($grid.R - $grid.L) / 4; H = ($grid.B - $grid.T) / 6 } }

# Dock (read from the last screen; it is the same on every page).
$dock = [System.Collections.Generic.List[object]]::new()
$doc = Read-Screen
$hotseat = $doc.SelectSingleNode("//node[@resource-id='$(Id 'hotseat_layout')']")
if ($hotseat) {
    foreach ($icon in @(Get-Icons $hotseat | Sort-Object { (Get-Bounds $_).L })) {
        $dock.Add((Read-Icon $icon (Get-Bounds $hotseat) $cell))
    }
}
Write-Host "Dock: $($dock.Count) icons"

$layout = [ordered]@{
    columns  = [int][Math]::Round(($grid.R - $grid.L) / $cell.W)
    rows     = [int][Math]::Round(($grid.B - $grid.T) / $cell.H)
    homePage = $homePage
    pages    = $pages
    dock     = $dock
}
$json = $layout | ConvertTo-Json -Depth 8 -Compress
$jsonFile = Join-Path $PSScriptRoot 'oneui-layout.json'
[System.IO.File]::WriteAllText($jsonFile, $json, [System.Text.UTF8Encoding]::new($false))
Write-Host "Grid $($layout.columns) x $($layout.rows); layout saved to $jsonFile" -ForegroundColor Green

Adb shell input keyevent KEYCODE_HOME | Out-Null
if ($DryRun) { return }

$data = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($json))
Adb shell am start -n com.maxbl4.maxlauncher/.ImportActivity --es data $data | Out-Null
Write-Host 'Confirm the import on the phone.' -ForegroundColor Green
