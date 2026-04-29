# Сборка libbox.aar со всеми ABI (без -debug) из дерева sing-box и копия в androidApp/libs.
# Требования: Go, gomobile (SagerNet), JDK 17 (JAVA_HOME), ANDROID_HOME, NDK 28.x в SDK.
# В Windows в cmd/internal/build_shared/sdk.go должен быть корректный разделитель PATH для NDK.
param(
    [string]$SingBoxRoot = $env:SINGBOX_ROOT
)

$ErrorActionPreference = "Stop"
$mobileVpnRoot = Split-Path $PSScriptRoot -Parent
if (-not $SingBoxRoot) {
    $parentOfApp = Split-Path $mobileVpnRoot -Parent
    $SingBoxRoot = Join-Path $parentOfApp "_deps" "sing-box"
}
if (-not (Test-Path (Join-Path $SingBoxRoot "go.mod"))) {
    Write-Error "Не найден sing-box в '$SingBoxRoot'. Задайте -SingBoxRoot или переменную SINGBOX_ROOT."
}

$dest = Join-Path $mobileVpnRoot "androidApp" "libs" "libbox.aar"
if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android" "Sdk"
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
    go run ./cmd/internal/build_libbox -target android
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
