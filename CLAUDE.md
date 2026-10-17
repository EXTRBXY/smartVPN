# CLAUDE.md

Android-first VPN-клиент (Kotlin) на ядре **sing-box** (встроенный `libbox.aar`). Shared-слой на KMP, UI на Jetpack Compose. Полная документация — в [docs/](docs/README.md); здесь только то, что нужно знать перед любой правкой.

Язык проекта: комментарии, UI-строки и README — на русском. Отвечай пользователю по-русски, идентификаторы и коммиты — как в окружающем коде.

## Карта проекта

| Путь | Что там |
|---|---|
| `shared/` | KMP-модуль (`commonMain`): domain, application (порты + `VpnSessionController`), infrastructure (компилятор sing-box JSON, VLESS-парсер, редакция логов). Без Android-типов. |
| `androidApp/` | Android-приложение: `vpn/` (VpnService), `libbox/` (мост к ядру), `platform/` (адаптеры портов, хранилище, автоматизация), `quicksettings/` (плитка), `ui/` + `MainActivity.kt` (Compose). |
| `androidApp/libs/libbox.aar` | Нативное ядро (gomobile). Собирается `scripts/build-libbox-aar.ps1`. |
| `.cursor/rules/*.mdc` | Исходные инженерные правила проекта — **обязательны к соблюдению**, см. ниже. |
| `docs/` | Документация (архитектура, жизненный цикл, безопасность, сборка, техдолг). |
| `docs/history/` | Экспорт старого чата Cursor (выбор фреймворка). Только историческая справка. |
| `third_party/sing-box/LICENSE` | Лицензия sing-box (GPL), распространяется вместе с `libbox.aar`. |

## Критично: имена пакетов и каталогов не совпадают

Каталоги исходников — `com/nothing/mobilevpn/...`, но **`package` и `applicationId` — `com.nothing.mobilecore`** (shared: `com.nothing.mobilecore.shared`, namespace библиотеки `com.nothing.mobilecore.shared`). Это не ошибка поиска: так сейчас в коде и в `gradle.properties` (`app.namespace`, `app.applicationId`). Action-строки `Intent` сервиса тоже `com.nothing.mobilecore.*`. Не «чини» это попутно — смена applicationId ломает данные установленных копий; делай отдельной задачей с миграцией.

## Команды

Windows / PowerShell, JDK 17, из корня:

```powershell
.\gradlew.bat :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug   # полная локальная проверка (как в README)
.\gradlew.bat :shared:check                    # то, что гоняет CI для shared
.\gradlew.bat :androidApp:assembleDebug        # APK: androidApp/build/outputs/apk/debug/androidApp-debug.apk
.\gradlew.bat :androidApp:nonDebugApk          # localRelease: R8, не debuggable, подпись debug-ключом
.\gradlew.bat :androidApp:connectedDebugAndroidTest   # нужен эмулятор/устройство
```

Один тест: `.\gradlew.bat :shared:testAndroidHostTest --tests "*SingBoxConfigCompilerTest"`.
Сборка падает на `checkLibboxAar`, если нет `androidApp/libs/libbox.aar`. iOS-таргеты в `shared` объявлены, но `iosApp` нет; без macOS они игнорируются (`kotlin.native.ignoreDisabledTargets=true`).

Инструментальные тесты могут удалить установленный APK вместе с данными — не запускай на устройстве с единственной копией профилей.

## Архитектурные правила (из `.cursor/rules`, не нарушать)

- Ядро не переписываем и не дублируем: приложение **компилирует policy → sing-box JSON** и запускает libbox. Никаких `ProcessBuilder`/CLI-бинарника в процессе.
- Слои: `domain` → `application` → `infrastructure` → `platform` → `ui`; зависимости только внутрь. UI не содержит VPN/системного кода, только состояние и команды.
- В `shared` нельзя тянуть Android SDK типы. Платформа подключается через порты (`SystemVpnPort`, `ProfileRepositoryPort`, `SecureStorePort`, `SessionConfigWriter`, `SingBoxCoreInfoPort`).
- Важные сетевые параметры (DNS/route/TUN) задаются явно в policy, а не «магией» в UI. Отклонение от документации sing-box — только с обоснованием и описанием риска.
- Сохранение настроек всегда проходит `TunnelPolicy.validate()` и возвращает явную ошибку пользователю.
- UI минималистичный, короткие однотипные подписи.

## Инварианты безопасности (проверяй при любых правках затрагиваемых мест)

1. **Секреты** (`uuid`, `realityPublicKey`, `realityShortId`) хранятся только в `SecureStoreAndroid` (EncryptedSharedPreferences). В списке профилей они обнуляются (`stripSecrets`). Не клади их в логи, `Intent`, `SharedPreferences` в открытом виде.
2. **Логи** — только через `LogRedaction` / `SafeAndroidLog` / `ConnectionEventLog`. Пользователю уходят только `VpnError` (фиксированные тексты из `strings.xml`), а не текст исключений.
3. **Состояние туннеля** передаётся внутри процесса через `TunnelStatusRepository` (`StateFlow`), внутренних broadcast нет. Если всё же понадобится broadcast — `setPackage` + signature-permission + `RECEIVER_NOT_EXPORTED`.
4. **Session config** (`sing-box-session-*.json` и `.json.tmp`) лежит в `noBackupFilesDir`, читается сервисом и **удаляется до старта ядра**; чистка не должна трогать `keepPath` и его `.tmp`-пару.
5. `workingPath` libbox — внутреннее хранилище (`filesDir/runtime`).
6. URL из уведомлений ядра открываются только после `isSafeNotificationUrl` (https, не localhost/private/link-local).
7. `release` не подписывается debug-ключом (только `localRelease`); `allowBackup=false`; `FLAG_SECURE` в не-debug; cleartext запрещён.

## Подводные камни

- Истина о туннеле — `TunnelStatus` в `TunnelStatusRepository` (только память процесса, пишет только `NothingVpnService`). `VpnSessionState` контроллера — состояние UI-стороны, выравнивается по `TunnelStatus` подпиской внутри контроллера. Не сохраняй «running» на диск: после смерти процесса туннель гарантированно мёртв, а сохранённый флаг уже давал ложное «Вкл» на плитке. Промежуточный `Stopped` при reconnect — ожидаемый, не ошибка.
- Ошибки — `enum VpnError` (`shared/domain/VpnError.kt`), бросаются через `ensure { }` / `vpnFail()`, любое исключение превращается в ошибку только через `toVpnError()`. Новый код = значение в enum + ветка в `ui/UserErrorText.kt` (исчерпывающий `when`, иначе не соберётся) + строка в `strings.xml`. Не сравнивай `exception.message` со строками.
- Доступ к профилям/политике ждёт `ProfileStorageMigration.awaitProfileStorageReady()` — новые методы репозитория должны делать то же.
- `failClosed=true` означает «одна попытка без ретраев», а не Android Kill Switch. Настройка `autoReconnect` в UI инвертирует это поле.
- `MainActivity.kt` (~1800 строк) содержит почти все экраны; `AppViewModel` — единственный ViewModel, общий для всех маршрутов.
- Проверки «задержки» — это TCP connect, а не проверка VLESS/Reality/DNS.
- Плитка (`VpnQuickTileService`) рисуется чистой функцией `TileModel.from(...)` от общего состояния — новые состояния добавляй туда и в `TileModelTest`.

## Перед завершением задачи

Прогони `:shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`. При изменениях в сервисе/вещании/хранилище — по возможности руками на устройстве: подключение, смена сети, отключение из уведомления и плитки, QR-импорт. Если меняешь поведение, обнови соответствующий файл в `docs/`.
