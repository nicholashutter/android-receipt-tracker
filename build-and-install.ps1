# build-and-install.ps1
# One-click build & install for Receipt Tracker.
# Run from VS Code (Ctrl+Shift+B) or from PowerShell:
#   powershell -ExecutionPolicy Bypass -File .\build-and-install.ps1
#
# What it does:
#   1. Sets JAVA_HOME / ANDROID_SDK_ROOT for this shell.
#   2. Bootstraps gradle-wrapper.jar if it isn't there yet (one-time fetch of
#      Gradle 8.4 from services.gradle.org, then a `gradle wrapper` to lay
#      down the proper wrapper jar).
#   3. ./gradlew assembleDebug
#   4. Picks the first connected `adb` device, installs the APK with -r,
#      and launches com.example.receipttracker/.ui.MainActivity.

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'Continue'

# --- paths (edit here if your install moves) -----------------------------------
$ProjectRoot   = Split-Path -Parent $MyInvocation.MyCommand.Path
$JavaHome      = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
$AndroidSdk    = 'C:\Users\nicho\AppData\Local\Android\Sdk'
$GradleVersion = '8.4'
$AppId         = 'com.example.receipttracker'
$LauncherAct   = '.ui.MainActivity'
# ------------------------------------------------------------------------------

$env:JAVA_HOME         = $JavaHome
$env:ANDROID_SDK_ROOT  = $AndroidSdk
$env:ANDROID_HOME      = $AndroidSdk
# platform-tools and tools on PATH for this script only
$env:Path = "$AndroidSdk\platform-tools;$AndroidSdk\cmdline-tools\latest\bin;$JavaHome\bin;$env:Path"

Set-Location $ProjectRoot

function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "    $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "    $msg" -ForegroundColor Yellow }
function Fail($msg) { Write-Host "XX  $msg" -ForegroundColor Red; exit 1 }

# --- 0. sanity checks --------------------------------------------------------
if (-not (Test-Path $JavaHome)) { Fail "JAVA_HOME not found at $JavaHome" }
if (-not (Test-Path $AndroidSdk)) { Fail "Android SDK not found at $AndroidSdk" }
$adb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { Fail "adb.exe not found under $AndroidSdk\platform-tools" }
$gradlew = Join-Path $ProjectRoot 'gradlew.bat'
$wrapperJar = Join-Path $ProjectRoot 'gradle\wrapper\gradle-wrapper.jar'

# --- 1. one-time wrapper bootstrap -------------------------------------------
if (-not (Test-Path $wrapperJar)) {
    Step "gradle-wrapper.jar missing - bootstrapping Gradle $GradleVersion"
    $tmpRoot = Join-Path $env:TEMP "rt-gradle-bootstrap"
    if (Test-Path $tmpRoot) { [System.IO.Directory]::Delete($tmpRoot, $true) }
    New-Item -ItemType Directory -Path $tmpRoot | Out-Null
    $zip = Join-Path $tmpRoot "gradle-$GradleVersion-bin.zip"
    $url = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
    Write-Host "    downloading $url"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $tmpRoot)
    $gradleHome = Get-ChildItem $tmpRoot -Directory | Where-Object { $_.Name -like "gradle-$GradleVersion" } | Select-Object -First 1
    if (-not $gradleHome) { Fail "Could not find extracted gradle distribution under $tmpRoot" }
    & (Join-Path $gradleHome.FullName 'bin\gradle.bat') wrapper --gradle-version $GradleVersion | Out-Null
    if (-not (Test-Path $wrapperJar)) { Fail "gradle wrapper did not produce $wrapperJar" }
    Ok "wrapper bootstrapped"
    # Temp cleanup is best-effort. The downloaded gradle distro is large and
    # a lingering gradle-instrumentation-agent jar may still be locked by the
    # gradle wrapper we just spawned; we don't want a cleanup failure to abort
    # the whole script. The next run will just overwrite the same temp dir.
    try {
        [System.IO.Directory]::Delete($tmpRoot, $true)
    } catch {
        Warn "could not delete $tmpRoot (in use by another process); will overwrite next run"
    }
} else {
    Ok "wrapper already present"
}

# --- 2. assembleDebug --------------------------------------------------------
Step "./gradlew assembleDebug"
& $gradlew assembleDebug --no-daemon
if ($LASTEXITCODE -ne 0) { Fail "gradle build failed (exit $LASTEXITCODE)" }
$apk = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) { Fail "APK not found at $apk" }
Ok "APK: $apk"

# --- 3. pick a device --------------------------------------------------------
Step "adb devices"
$adbOut = & $adb devices
$devices = @()
foreach ($line in $adbOut) {
    if ($line -match '^\S+\s+device\s*$') {
        $devices += ($line -split '\s+')[0]
    }
}
if ($devices.Count -eq 0) {
    Write-Host $adbOut
    Fail "No connected adb device. Plug in a phone with USB debugging, or start the AVD."
}
$device = $devices[0]
Ok "Using device: $device"

# --- 4. install --------------------------------------------------------------
Step "adb -s $device install -r $apk"
& $adb -s $device install -r $apk
if ($LASTEXITCODE -ne 0) { Fail "adb install failed (exit $LASTEXITCODE)" }
Ok "Installed"

# --- 5. launch ---------------------------------------------------------------
Step "adb shell am start -n $AppId/$LauncherAct"
& $adb -s $device shell am start -n "$AppId/$LauncherAct" | Out-Null
Ok "Launched"

Write-Host ""
Write-Host "Done. App should be on screen." -ForegroundColor Green
