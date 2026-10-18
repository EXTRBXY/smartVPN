@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo Building localRelease APK ^(not debuggable, R8, debug keystore for local install^)...
call "%~dp0gradlew.bat" :androidApp:nonDebugApk
if errorlevel 1 (
    echo Build failed.
    exit /b 1
)

echo.
echo Done. Install this file on the phone:
echo   %~dp0androidApp\build\outputs\apk\localRelease\androidApp-localRelease.apk
exit /b 0
