@echo off
REM Installs the latest KU WBB APK onto a USB-connected Android phone via adb.
REM
REM Android's Advanced Protection mode blocks browser/sideload APK installs but
REM allows installs from a computer over ADB, so this is the supported path.
REM
REM One-time phone setup:
REM   1. Settings > About phone > tap "Build number" 7 times (enables Developer options)
REM   2. Settings > System > Developer options > enable "USB debugging"
REM   3. Plug the phone into this computer and accept the "Allow USB debugging?" prompt
REM
REM Requires adb (Android platform-tools):
REM https://developer.android.com/tools/releases/platform-tools
REM Unzip it and either add the folder to PATH or drop this script in that folder.

where adb >nul 2>nul
if errorlevel 1 (
  echo adb not found. Download Android platform-tools from:
  echo   https://developer.android.com/tools/releases/platform-tools
  echo Unzip it and run this script from inside that folder, or add it to PATH.
  exit /b 1
)

echo Downloading latest KU WBB APK...
curl -fsSL -o "%TEMP%\ku-wbb.apk" https://github.com/inspectorgad/ku-wbb/releases/latest/download/app-debug.apk
if errorlevel 1 exit /b 1

echo Waiting for a connected device (accept the USB debugging prompt on the phone)...
adb wait-for-device

echo Installing...
adb install -r "%TEMP%\ku-wbb.apk"
if errorlevel 1 exit /b 1

echo Done - KU WBB is installed. You can unplug the phone.
