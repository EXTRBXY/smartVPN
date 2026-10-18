# Сборка, тесты, окружение

## Требования
- Windows (основная среда разработки), PowerShell; JDK 17 (toolchain-resolver `foojay` подключён в `settings.gradle.kts`), Android SDK (Platform 37.0, `sdkmanager "platforms;android-37.0"`), Android Studio — последняя стабильная.
- `androidApp/libs/libbox.aar` — без него `preBuild` падает задачей `checkLibboxAar` с подсказкой.
- Для iOS-таргетов `shared` нужен macOS; на Windows они пропускаются (`kotlin.native.ignoreDisabledTargets=true`).

## Версии и параметры (`gradle.properties`, `build.gradle.kts`)
AGP 9.2.1 · Kotlin 2.2.10 (+compose и serialization плагины) · compileSdk 37 · targetSdk 35 · minSdk 26 (в `gradle.properties`) · JVM 17 · `org.gradle.jvmargs=-Xmx3g` · `versionCode`/`versionName` — в `androidApp/build.gradle.kts`.

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
- По умолчанию собирается только `android/arm64` (`-Platform android` — все ABI, APK вырастет примерно вчетверо). Перед сборкой каталог `build/` в дереве sing-box очищается: остатки прерванной сборки ломают gomobile.
- Не запускайте сборку через `cmd /c`: cmd.exe добавляет в окружение переменные вида `=D:=D:\...`, на которых gomobile падает.
- Скрипт работает в Windows PowerShell 5.1 (pwsh не нужен); прогресс `go` в stderr не считается ошибкой — успех определяется по коду выхода.
- AAR собирается из того же дерева, что и SFA; совместимость API (`CommandServer`, `PlatformInterface`, `TunOptions`, …) зависит от версии ядра — при обновлении ядра сверять `experimental/libbox/ffi.json`.

Текущий `libbox.aar` (~29 МБ, sing-box `v1.14.3`, arm64) лежит в репозитории. При переходе с 1.14.0-alpha на 1.14.3 изменился Android-API: в `PlatformInterface` добавились методы для оболочки/SSH/SFTP, моста, соседей, `registerMyInterface`, `tailscaleHostname`, `cancelNotification`, убран `systemCertificates`; в `CommandServerHandler` — `connectSSHAgent`; `TunOptions.getDNSServerAddress` стал списком. Функции, которым в официальном клиенте нужен root, у нас объявлены неподдерживаемыми.

## CI (`.github/workflows/ci.yml`)
Триггеры: PR, push в `main`/`master`.
- Job `build` (ubuntu, JDK 17 temurin): `:shared:check` → `:androidApp:assembleDebug` → `:androidApp:testDebugUnitTest` → `:androidApp:lintDebug` (HTML-отчёт выкладывается артефактом).
- Job `instrumented-tests`: эмулятор API 34 x86_64 (pixel_6), `:androidApp:connectedDebugAndroidTest`.
- Замечание: README рекомендует локально `:shared:testAndroidHostTest`, CI — `:shared:check` (включает его). Сборке нужна платформа SDK 37; на раннере её доставляет AGP/SDK-менеджер — если CI упадёт на отсутствии `android-37.0`, добавить шаг установки платформы.

## Тесты

| Набор | Расположение | Кол-во/что |
|---|---|---|
| shared (commonTest) | `shared/src/commonTest` | контроллер сессии (10 сценариев), компилятор, валидация policy, парсер/форматтер, редакция |
| androidApp unit | `androidApp/src/test` | хранилище и миграции (`VaultTest`), целостность секретов, session-файлы, плитка, DNS и маршрутизация (ViewModel/черновик), решения автоматизации, перезапуска сервиса и сторожа |
| androidApp instrumented | `androidApp/src/androidTest` | начальный статус туннеля, константы контрактов, замена секрета |

Не покрыто тестами: `NothingVpnService`, `libbox/*`, UI, платформенная часть `VpnAutomationCoordinator` (решения покрыты `AutomationDecisionsTest`), ViewModel экранов (кроме `DnsViewModelTest` и черновика маршрутизации `RoutingDraftTest`), `KeystoreVaultCipher` и `AndroidLegacySource` (требуют устройства; логика хранилища и миграции покрыта `VaultTest` с фейками).

## Ручная проверка перед релизом
Чек-лист тестовой эксплуатации и как сообщать о проблемах — [testing-checklist.md](testing-checklist.md).

Подключение и отключение; смена Wi-Fi ↔ мобильная сеть; отключение из уведомления и плитки; QR-импорт; per-app режимы; DNS-исключения; поведение системной блокировки без VPN; автозапуск после перезагрузки (если включён); Always-on (туннель поднимается после перезагрузки и обновления приложения); отозвать VPN из системных настроек; английский язык интерфейса.

Инструментальные тесты запускайте на устройстве без ценных данных: test runner может удалить APK приложения (и его данные) по завершении.

## Прочее
- `.cursorignore` исключает крупные артефакты из индекса Cursor.
