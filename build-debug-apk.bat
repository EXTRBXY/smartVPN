@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo Building debug APK (auto debug signing, no keystore)...
call gradlew.bat :androidApp:assembleDebug
if errorlevel 1 (
    echo Build failed.
    exit /b 1
)

echo.
echo Done. Install this file on the phone:
echo   %~dp0androidApp\build\outputs\apk\debug\androidApp-debug.apk
exit /b 0
