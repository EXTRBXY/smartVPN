# Сборка, тесты, окружение

## Требования
- Windows (основная среда разработки), PowerShell; JDK 17 (toolchain-resolver `foojay` подключён в `settings.gradle.kts`), Android SDK (compileSdk 35), Android Studio — последняя стабильная.
- `androidApp/libs/libbox.aar` — без него `preBuild` падает задачей `checkLibboxAar` с подсказкой.
- Для iOS-таргетов `shared` нужен macOS; на Windows они пропускаются (`kotlin.native.ignoreDisabledTargets=true`).

## Версии и параметры (`gradle.properties`, `build.gradle.kts`)
AGP 9.2.1 · Kotlin 2.2.10 (+compose и serialization плагины) · compileSdk/targetSdk 35 · minSdk 26 · JVM 17 · `org.gradle.jvmargs=-Xmx3g` · `versionCode=1`, `versionName=0.1.0` (захардкожены в `androidApp/build.gradle.kts`).

## Команды

```powershell
.\gradlew.bat :androidApp:assembleDebug                 # debug APK
.\gradlew.bat :androidApp:assembleLocalRelease          # или :androidApp:nonDebugApk — R8, подпись debug-ключом
.\gradlew.bat :shared:testAndroidHostTest               # unit-тесты shared (JVM)
.\gradlew.bat :shared:check                             # как в CI
.\gradlew.bat :androidApp:testDebugUnitTest             # unit-тесты Android-модуля
.\gradlew.bat :androidApp:lintDebug
.\gradlew.bat :androidApp:connectedDebugAndroidTest     # инструментальные, нужен девайс/эмулятор
```

Скрипты-обёртки: `build-debug-apk.bat`, `build-release-apk.bat` (корень). Выходные APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`, `.../localRelease/`.

## Release-подпись
Переменные окружения: `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. Если заданы все четыре — создаётся `signingConfigs.release`; иначе `release` собирается неподписанным. Keystore в репозиторий не коммитить (`.gitignore`: `*.jks`, `*.keystore`).

## Сборка libbox.aar
`scripts/build-libbox-aar.ps1`:
- Требует Go, gomobile (SagerNet), JDK 17 (`JAVA_HOME`), `ANDROID_HOME`, NDK 28.x; клон sing-box в `<родитель mobileVPN>/_deps/sing-box` (или `-SingBoxRoot` / `$env:SINGBOX_ROOT`).
- Выполняет `go run ./cmd/internal/build_libbox -target android` в дереве sing-box и копирует `libbox.aar` в `androidApp/libs/`.
- На Windows в `cmd/internal/build_shared/sdk.go` нужен корректный разделитель PATH для NDK (указано в комментарии скрипта).
- AAR собирается из того же дерева, что и SFA; совместимость API (`CommandServer`, `PlatformInterface`, `TunOptions`, …) зависит от версии ядра — при обновлении ядра сверять `experimental/libbox/ffi.json`.

Текущий `libbox.aar` (~40 МБ) лежит в репозитории.

## CI (`.github/workflows/ci.yml`)
Триггеры: PR, push в `main`/`master`.
- Job `build` (ubuntu, JDK 17 temurin): `:shared:check` → `:androidApp:assembleDebug` → `:androidApp:testDebugUnitTest`.
- Job `instrumented-tests`: эмулятор API 34 x86_64 (pixel_6), `:androidApp:connectedDebugAndroidTest`.
- Замечание: README рекомендует локально `:shared:testAndroidHostTest`, CI — `:shared:check`; lint в CI не запускается.

## Тесты

| Набор | Расположение | Кол-во/что |
|---|---|---|
| shared (commonTest) | `shared/src/commonTest` | контроллер сессии (10 сценариев), компилятор, валидация policy, парсер/форматтер, редакция |
| androidApp unit | `androidApp/src/test` | целостность секретов, жизненный цикл session-файлов |
| androidApp instrumented | `androidApp/src/androidTest` | жизненный цикл VPN-флага, константы контрактов, замена секрета |

Не покрыто тестами: `NothingVpnService`, `libbox/*`, UI, `VpnAutomationCoordinator`, `AppViewModel`, `ProfileRepositoryAndroid` (кроме инструментального сценария), `ProfileStorageMigration`.

## Ручная проверка перед релизом
Подключение и отключение; смена Wi-Fi ↔ мобильная сеть; отключение из уведомления и плитки; QR-импорт; per-app режимы; DNS-исключения; поведение системной блокировки без VPN; автозапуск после перезагрузки (если включён); отозвать VPN из системных настроек.

Инструментальные тесты запускайте на устройстве без ценных данных: test runner может удалить APK приложения (и его данные) по завершении.

## Прочее
- `.cursorignore` исключает крупные артефакты из индекса Cursor.
- Артефакты `.kotlin/metadata/*.klib` попали в git (отсутствуют в `.gitignore`) — см. [tech-debt.md](tech-debt.md).
