# Pantry Passport - create the upload key (first time only), build, and sign the Play Store bundle.
# Run it from PowerShell:
#   powershell -ExecutionPolicy Bypass -File "D:\AI\PantryPassport\android\make-signed-bundle.ps1"
# You will be asked for a password. Type it yourself; it is never saved by this script.

$ErrorActionPreference = "Stop"
$Project  = $PSScriptRoot
$Version  = "1.0.0"
$KeyDir   = Join-Path $env:USERPROFILE "Documents\Keys"
$KeyFile  = Join-Path $KeyDir "pantrypassport-upload.jks"
$Alias    = "upload"
$Unsigned = Join-Path $Project "app\build\outputs\bundle\release\app-release.aab"
$Signed   = Join-Path $env:USERPROFILE "Desktop\PantryPassport-$Version-signed.aab"

# Java 21 (Android Studio's own Java 25 is too new for this project's Gradle)
$Jdk = @("$env:USERPROFILE\.jdks\jbr-21.0.11", "C:\Program Files\Microsoft\jdk-21.0.13.9-hotspot") |
       Where-Object { Test-Path "$_\bin\keytool.exe" } | Select-Object -First 1
if (-not $Jdk) { throw "Java 21 not found." }
$env:JAVA_HOME = $Jdk
$keytool   = "$Jdk\bin\keytool.exe"
$jarsigner = "$Jdk\bin\jarsigner.exe"

Write-Host ""
Write-Host "=== Step 1 of 3: Upload key ===" -ForegroundColor Cyan
if (Test-Path $KeyFile) {
    Write-Host "Using your existing key: $KeyFile"
} else {
    New-Item -ItemType Directory -Force -Path $KeyDir | Out-Null
    Write-Host "Creating a new key at $KeyFile"
    Write-Host "Choose a password (at least 6 characters). You'll type it twice. Nothing shows while you type - that's normal." -ForegroundColor Yellow
    & $keytool -genkeypair -v -keystore $KeyFile -storetype PKCS12 -alias $Alias `
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Pantry Passport, O=Pantry Passport"
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $KeyFile)) { throw "Key was not created." }
    Write-Host "Key created. WRITE DOWN THE PASSWORD and back up this file (e.g. a USB drive)." -ForegroundColor Green
    Write-Host "Without both you can never publish an update to Pantry Passport." -ForegroundColor Green
}

Write-Host ""
Write-Host "=== Step 2 of 3: Build the release bundle (1-3 minutes) ===" -ForegroundColor Cyan
Push-Location $Project
& .\gradlew.bat bundleRelease --console=plain -q
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0 -or -not (Test-Path $Unsigned)) { throw "Build failed. Copy the red text above and send it to Claude." }
Write-Host "Built: $Unsigned"

Write-Host ""
Write-Host "=== Step 3 of 3: Sign it ===" -ForegroundColor Cyan
Write-Host "Type the key password when asked." -ForegroundColor Yellow
if (Test-Path $Signed) { Remove-Item $Signed -Force }
& $jarsigner -keystore $KeyFile -sigalg SHA256withRSA -digestalg SHA-256 -signedjar $Signed $Unsigned $Alias
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $Signed)) { throw "Signing failed (wrong password?). Run the script again." }

& $jarsigner -verify $Signed | Select-Object -Last 1
$mb = [math]::Round((Get-Item $Signed).Length / 1MB, 1)
Write-Host ""
Write-Host "DONE: $Signed ($mb MB)" -ForegroundColor Green
Write-Host "Tell Claude 'bundle ready' and it will upload it to Play Console." -ForegroundColor Green
explorer.exe /select,"$Signed"
Read-Host "Press Enter to close"

