# Pull the app's crash log off the connected device.
# Usage: .\scripts\pull-crash-log.ps1
# Requires: USB debugging enabled, ADB on PATH, device authorised.

$ErrorActionPreference = "Stop"

$package = "com.example.receipttracker"
$remotePath = "/data/data/$package/files/logs/app.log"
$localPath = Join-Path $env:TEMP "crash-app.log"

Write-Host "Checking for connected device..." -ForegroundColor Cyan
$adbDevices = & adb devices 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "adb failed. Is the Android SDK platform-tools on PATH?" -ForegroundColor Red
    exit 1
}

$deviceLines = $adbDevices | Select-String -Pattern "device$" | ForEach-Object { $_.Line }
if ($deviceLines.Count -eq 0) {
    Write-Host "No device connected. Plug in / start an emulator, then retry." -ForegroundColor Red
    exit 1
}

Write-Host "Found device: $($deviceLines[0])" -ForegroundColor Green

# Try logcat first - the AndroidRuntime FATAL prints the full stack trace.
Write-Host "Reading AndroidRuntime FATAL stack from logcat..." -ForegroundColor Cyan
& adb logcat -d -b crash 2>$null | Tee-Object -FilePath "$env:TEMP\crash-logcat.txt" | Out-Null
$fatal = & adb logcat -d -b crash 2>$null | Select-String -Pattern "AndroidRuntime|FATAL EXCEPTION" -SimpleMatch:$false
if ($fatal -and $fatal.Count -gt 0) {
    Write-Host "Crash stack from logcat:" -ForegroundColor Red
    $fatal | ForEach-Object { Write-Host "  $_" }
} else {
    Write-Host "No AndroidRuntime FATAL in logcat. Trying app log file..." -ForegroundColor Yellow
}

# `run-as` only works on debug builds. Try it first; fall back to `cat` which
# requires root but prints the same bytes.
Write-Host "Reading $remotePath via run-as..." -ForegroundColor Cyan
$body = & adb exec-out run-as $package cat $remotePath 2>$null
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrEmpty($body)) {
    Write-Host "run-as failed (non-debug build or permission issue). Trying plain cat..." -ForegroundColor Yellow
    $body = & adb exec-out cat $remotePath 2>$null
}

if ([string]::IsNullOrEmpty($body)) {
    Write-Host "Could not read the log file. The app may not have crashed yet." -ForegroundColor Red
    Write-Host "Open the app, let it crash, then re-run this script." -ForegroundColor Yellow
    exit 2
}

[System.IO.File]::WriteAllText($localPath, $body)
Write-Host "Wrote $localPath" -ForegroundColor Green
Write-Host "Last 80 lines:" -ForegroundColor Cyan
Get-Content $localPath -Tail 80 | ForEach-Object { Write-Host "  $_" }
