<#
.SYNOPSIS
    Builds, installs, and launches Apus on a connected Android device or emulator.
.DESCRIPTION
    Run directly: .\runapp.ps1
    Or import function: . .\runapp.ps1 ; runapp
#>

function runapp {
    [CmdletBinding()]
    param()

    $pkg      = "com.apusapp"
    $activity = "$pkg/.MainActivity"
    $apk      = ".\app\build\outputs\apk\debug\app-debug.apk"

    # Android SDK platform-tools detection
    $possibleAdbPaths = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools",
        "$env:ANDROID_HOME\platform-tools",
        "$env:ANDROID_SDK_ROOT\platform-tools"
    )

    foreach ($path in $possibleAdbPaths) {
        if ($path -and (Test-Path $path -ErrorAction SilentlyContinue) -and ($env:PATH -notlike "*$path*")) {
            $env:PATH = "$path;$env:PATH"
            break
        }
    }

    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        Write-Host "[ERROR] adb nahi mila! Kripya Android SDK platform-tools install karein ya PATH mein add karein." -ForegroundColor Red
        return
    }

    if (-not (Test-Path ".\gradlew.bat" -ErrorAction SilentlyContinue)) {
        Write-Host "[ERROR] gradlew.bat nahi mila! Kripya Apus project ke root folder mein jaake script run karein." -ForegroundColor Red
        return
    }

    # Check connected devices
    $deviceList = adb devices | Where-Object { $_ -match "\tdevice$" }
    if (-not $deviceList) {
        Write-Host "[WARNING] Koi Android device ya emulator connected nahi hai." -ForegroundColor Yellow
        Write-Host "USB Debugging on karke device connect karein ya emulator start karein, phir dubara run karein." -ForegroundColor Yellow
        return
    }

    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host "  Building Apus (Debug APK)...           " -ForegroundColor Cyan
    Write-Host "==========================================" -ForegroundColor Cyan

    .\gradlew.bat assembleDebug
    if ($LASTEXITCODE -ne 0) {
        Write-Host "`n[ERROR] Build fail ho gaya!" -ForegroundColor Red
        return
    }

    if (-not (Test-Path $apk -ErrorAction SilentlyContinue)) {
        Write-Host "`n[ERROR] APK file nahi mili: $apk" -ForegroundColor Red
        return
    }

    Write-Host "`n==========================================" -ForegroundColor Cyan
    Write-Host "  Installing Apus on Device...            " -ForegroundColor Cyan
    Write-Host "==========================================" -ForegroundColor Cyan

    adb install -r $apk
    if ($LASTEXITCODE -ne 0) {
        Write-Host "`n[ERROR] Installation fail ho gaya!" -ForegroundColor Red
        return
    }

    Write-Host "`n==========================================" -ForegroundColor Cyan
    Write-Host "  Launching Apus...                       " -ForegroundColor Cyan
    Write-Host "==========================================" -ForegroundColor Cyan

    # Launch using explicit MainActivity
    $startResult = adb shell am start -n $activity 2>&1
    if ($LASTEXITCODE -ne 0) {
        # Fallback to launcher intent monkey
        adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
    }

    Write-Host "`n[SUCCESS] Apus successfully launch ho gaya hai! 🎉`n" -ForegroundColor Green
}

# Alias for convenience: runapus
Set-Alias -Name runapus -Value runapp -ErrorAction SilentlyContinue

# Execute automatically when run directly as a script (e.g., .\runapp.ps1)
runapp
