# Сборка libbox.aar со всеми ABI (без -debug) из дерева sing-box и копия в androidApp/libs.
# Требования: Go, gomobile (SagerNet), JDK 17 (JAVA_HOME), ANDROID_HOME, NDK 28.x в SDK.
# Совместим с Windows PowerShell 5.1 (Join-Path только с двумя аргументами).
# В Windows в cmd/internal/build_shared/sdk.go должен быть корректный разделитель PATH для NDK.
# По умолчанию только arm64 (как сейчас в репозитории): все ABI увеличили бы APK примерно вчетверо.
# Все ABI: -Platform android
param(
    [string]$SingBoxRoot = $env:SINGBOX_ROOT,
    [string]$Platform = "android/arm64"
)

$ErrorActionPreference = "Stop"
$mobileVpnRoot = Split-Path $PSScriptRoot -Parent
if (-not $SingBoxRoot) {
    $parentOfApp = Split-Path $mobileVpnRoot -Parent
    $SingBoxRoot = Join-Path (Join-Path $parentOfApp "_deps") "sing-box"
}
if (-not (Test-Path (Join-Path $SingBoxRoot "go.mod"))) {
    Write-Error "Не найден sing-box в '$SingBoxRoot'. Задайте -SingBoxRoot или переменную SINGBOX_ROOT."
}

$dest = Join-Path (Join-Path (Join-Path $mobileVpnRoot "androidApp") "libs") "libbox.aar"
if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = Join-Path (Join-Path $env:LOCALAPPDATA "Android") "Sdk"
}
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem "C:\Program Files\Microsoft\jdk-*" -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not $env:JAVA_HOME) {
    Write-Error "Задайте JAVA_HOME (OpenJDK 17)."
}

Push-Location $SingBoxRoot
try {
    # go пишет прогресс в stderr; в Windows PowerShell 5.1 при "Stop" это стало бы фатальной
    # ошибкой. Успех определяем по коду выхода.
    # Остатки прерванной сборки ломают gomobile ("go_libboxmain.go: The file exists").
    if (Test-Path "build") { Remove-Item -Recurse -Force "build" }
    $ErrorActionPreference = "Continue"
    go run ./cmd/internal/build_libbox -target android -platform $Platform 2>&1 | ForEach-Object { "$_" }
    $goExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($goExit -ne 0) { Write-Error "build_libbox завершился с кодом $goExit" }
}
finally {
    Pop-Location
}

$built = Join-Path $SingBoxRoot "libbox.aar"
if (-not (Test-Path $built)) {
    Write-Error "Не появился $built"
}
New-Item -ItemType Directory -Path (Split-Path $dest -Parent) -Force | Out-Null
Copy-Item -Path $built -Destination $dest -Force
Write-Host "Скопировано: $dest"
