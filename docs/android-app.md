# Модуль `androidApp`

Исходники: `androidApp/src/main/kotlin/com/nothing/mobilevpn/` (пакет `com.nothing.mobilecore`). Ресурсы: `androidApp/src/main/res`.

## Манифест (`AndroidManifest.xml`)

| Элемент | Значение |
|---|---|
| Разрешения | `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `ACCESS_FINE/COARSE_LOCATION` (SSID Wi-Fi и `NeedWIFIState` ядра, как в SFA), `CHANGE_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `CAMERA` (QR; `uses-feature camera required=false`), `QUERY_ALL_PACKAGES` (список всех приложений для split tunneling; осознанно для личного распространения) |
| `<application>` | `NothingVpnApp`, `allowBackup=false`, `networkSecurityConfig` (cleartext запрещён), правила backup/extraction пустые |
| `MainActivity` | launcher + `QS_TILE_PREFERENCES` (долгое нажатие на плитку открывает приложение), `adjustResize` |
| `NothingVpnService` | `exported=false`, `BIND_VPN_SERVICE`, `foregroundServiceType=connectedDevice`, action `android.net.VpnService` |
| `BootCompletedReceiver` | `exported=true`, `BOOT_COMPLETED` и `MY_PACKAGE_REPLACED` |
| `TunnelWatchdogJobService` | `exported=false`, `BIND_JOB_SERVICE` (сторож туннеля, см. [vpn-lifecycle.md](vpn-lifecycle.md)) |
| `VpnQuickTileService` | `exported=true`, `BIND_QUICK_SETTINGS_TILE`, `QS_TILE` |

Строки: русские (основные) — `res/values/strings.xml`, английские — `res/values-en/strings.xml`; тема — `ui/NothingVpnTheme.kt` (циан `#28C6D9`, динамические цвета Material 3 на Android 12+). Все тексты интерфейса — в `strings.xml` (в коде `stringResource` в composable и `context.getString` в колбэках); захардкоженных русских строк в Kotlin нет. Набор ключей в обоих файлах одинаковый; новая строка добавляется в оба. Язык приложения выбирается в системных настройках (Android 13+): `generateLocaleConfig` + `res/resources.properties` (`unqualifiedResLocale=ru`).

## Пакет `vpn/` — системный VPN

### `NothingVpnService : VpnService, MobilePlatformInterface`
Ядро Android-реализации. Поля: `commandServer` (libbox), `tunPfd`, `sessionConfigPath`, `lastPolicy`, `lifecycleMutex`, `scope(IO)`.

Intent-протокол (`companion`): `ACTION_CONNECT` (`EXTRA_SESSION_ID`, `EXTRA_CONFIG_PATH`, `EXTRA_POLICY_JSON`), `ACTION_DISCONNECT` (`EXTRA_USER_DISCONNECT`).

- `onStartCommand(CONNECT)`: читает sessionId, путь и политику, `startForeground(NOTIFICATION_ID=1001, type=CONNECTED_DEVICE)`, публикует `Starting(sessionId)`, в `scope` под мьютексом вызывает `connectSuspend`; при исключении — полная остановка с `Stopped(sessionId, код)`. Возвращает `START_STICKY` для CONNECT, `START_NOT_STICKY` иначе; `intent == null` (перезапуск системой после смерти процесса) и action `android.net.VpnService` (Always-on) — `VpnAutomationCoordinator.restoreTunnel`, который поднимает туннель из хранилища, иначе `stopSelf` (см. [vpn-lifecycle.md](vpn-lifecycle.md)).
- `onStartCommand(DISCONNECT)`: при `EXTRA_USER_DISCONNECT` сбрасывает `AppPreferences.connectionRequested`, останавливает сервис.
- `connectSuspend`: `teardown()` предыдущего ядра → чистка session-файлов → `LibboxAndroidBootstrap.ensureSetup()` → чтение JSON из файла → **удаление файла** → `CommandServer(handler, this).start()` → `DefaultNetworkMonitor.start()` → `commandServer.startOrReloadService(json, OverrideOptions)` → проверка `needWIFIState` против `ACCESS_FINE_LOCATION` → `Running(sessionId, now)`.
- `buildOverrideOptions(policy)`: `autoRedirect=false`; для `PER_APP_ALLOWED` — `includePackage = allowed + собственный пакет`; для `PER_APP_EXCLUDED` — `excludePackage = allowed`; для `FULL_TUN` фильтра нет (собственный трафик диагностики тоже идёт через туннель, сокеты ядра защищены `protect(fd)`).
- `openTun(TunOptions)` — реализация `PlatformInterface.openTun`: `VpnService.Builder` (MTU, адреса v4/v6, DNS, маршруты — `addRoute/excludeRoute(IpPrefix)` на API 33+, иначе `inet*RouteRange`; `addAllowedApplication/addDisallowedApplication`; опционально системный HTTP-прокси на API 29+), `establish()` → fd.
- `teardown()`: удаляет session-файл, закрывает `tunPfd`, `DefaultNetworkMonitor.stop()`, `commandServer.closeService()/close()`.
- `onRevoke`: остановка с `error = vpn_revoked`. `onDestroy`: teardown и `Stopped`, если ещё не опубликован. `VpnBoxHandler.serviceStop` (запрос ядра) — полная остановка, кроме вызова во время нашего же `teardown`.
- `sendNotification`: уведомления ядра (канал по `identifier`, `IMPORTANCE_HIGH`); открываемый URL — только после `isSafeNotificationUrl`.
- `VpnBoxHandler : CommandServerHandler`: `serviceStop`, `serviceReload` (перезапуск ядра с конфигом сессии из памяти; при ошибке — остановка с причиной), `getSystemProxyStatus`, `setSystemProxyEnabled`, `writeDebugMessage` (только в debug, через `LogRedaction`).

### Контракты и состояние
- `TunnelStatusRepository` (в `NothingVpnGraph.tunnelStatus`) — `StateFlow<TunnelStatus>` в памяти процесса, `newSessionId()`, `publish()` (обновляет плитку через `requestListeningState`). При создании удаляет устаревший `nothing_vpn_running_flag`. Подробно — [vpn-lifecycle.md](vpn-lifecycle.md).

## Пакет `libbox/` — мост к ядру

| Класс | Роль |
|---|---|
| `LibboxAndroidBootstrap` | Хранит `Application`, однократный `Libbox.setup(basePath=filesDir, workingPath=filesDir/runtime, tempPath=cacheDir, fixAndroidStack, logMaxLines=3000, debug=BuildConfig.DEBUG)`, `setLocale` |
| `MobilePlatformInterface` | `interface` с реализациями по умолчанию для `PlatformInterface`: `usePlatformAutoDetectInterfaceControl=true`, `findConnectionOwner` (uid → пакеты), `getInterfaces`, `readWIFIState`, `localDNSTransport = LocalResolver`, мониторинг интерфейса → `DefaultNetworkMonitor`, `tailscaleHostname`. Возможности, которым в SFA нужен root (оболочка/SSH/SFTP, мост, таблица соседей), объявлены неподдерживаемыми (`usePlatformShell/usePlatformBridge = false`, остальное бросает или no-op). Системные сертификаты ядро с 1.14 берёт само (`systemCertificates` из API убран). `openTun` и `autoDetectInterfaceControl` переопределяет сервис. Содержит вложенный `StringArray` (итератор для libbox) |
| `DefaultNetworkListener` | Actor вокруг `ConnectivityManager` NetworkCallback (версия API определяет способ регистрации) — хранит «текущую» сеть |
| `DefaultNetworkMonitor` | Подписка на `DefaultNetworkListener`, вычисляет имя/индекс интерфейса (до 10 попыток по 100 мс) и вызывает `InterfaceUpdateListener.updateDefaultInterface` |
| `LocalResolver` | `LocalDNSTransport`: DNS через `android.net.DnsResolver` на текущей сети (raw-режим на API 29+) |
| `TunRouteExt` | `RoutePrefix.toAndroidIpPrefix()` |

## Пакет `platform/` — адаптеры и вспомогательные сервисы

| Класс | Назначение |
|---|---|
| `NothingVpnGraph` | Ручной DI-контейнер (см. [architecture.md](architecture.md)) |
| `AndroidSystemVpn` | `SystemVpnPort`, `tunnelStatus` из `TunnelStatusRepository`. Старт: `startForegroundService(ACTION_CONNECT, sessionId)`, ожидание `Running/Stopped` этой сессии, таймаут 30 с. Стоп: если уже `Stopped` — ничего; иначе `ACTION_DISCONNECT` и ожидание `Stopped`, таймаут 10 с. `smokeCheck`: `LibboxProxyProbe.test("proxy")` (urlTest группы `proxy`) + HTTPS `GET https://www.gstatic.com/generate_204` (ожидается 204; без keep-alive; при `IOException` до 4 попыток с паузой 300/600/1200 мс — сразу после старта первый сокет приложения через TUN может получить RST) + `TunnelStatus.Running`; общий таймаут 7 с. Неудачные попытки подключения пишутся в журнал с цепочкой причин |
| `LibboxProxyProbe` | Одноразовый `CommandClient` с no-op обработчиком для `urlTest(group)` |
| `TunnelStatsMonitor` | Цикл: пока `TunnelStatus.Running`, подключается `CommandClient` с `CommandStatus` раз в секунду и публикует `StateFlow<TunnelStats>` (скорости, итоги, число соединений) |
| `ProfileRepositoryAndroid` | `ProfileRepositoryPort` поверх `storage/VaultStore` (профили с секретами, активный профиль, политика) |
| `storage/*` | Зашифрованное хранилище: `KeystoreVaultCipher`, `VaultFile`, `StoredVault`, `VaultStore`, загрузка и двухфазная миграция `VaultBootstrap`, чтение старых данных `AndroidLegacySource`/`LegacyDecoder`/`ProfileSecretIntegrity`. См. [data-and-storage.md](data-and-storage.md) |
| `AppPreferences` | Раздел `settings` зашифрованного `StoredVault`: флаги автоматизации, доверенные Wi-Fi, наборы маршрутизации (`RoutingRuleSet`), `connectionRequested`; запись через фоновую очередь |
| `VpnAutomationCoordinator` | Автоподключение и health-монитор (см. [vpn-lifecycle.md](vpn-lifecycle.md)) |
| `BootCompletedReceiver` | → `automation.onBootCompleted()` через `goAsync()` |
| `TunnelWatchdog` + `TunnelWatchdogJobService` | Periodic persisted job (15 мин), пока туннель `Running` и `connectionRequested`; в новом процессе → `automation.restoreTunnel(PROCESS_RESTART)`. Решения — `TunnelWatchdogDecisions.kt` |
| `AndroidSessionConfigWriter` + `SessionConfigFileLifecycle` | Запись `sing-box-session-<ts>.json` (через `.tmp` + rename) в `noBackupFilesDir`; политика очистки: ≤2 файла, возраст ≤5 мин (6 ч для явной очистки), `keepPath` неприкосновенен |
| `AndroidSingBoxCoreInfo` | `Libbox.version()` |
| `ConnectionEventLog` | Кольцевой журнал событий (80 записей, 240 символов, редакция) в SharedPreferences `nothing_vpn_diagnostics` |
| `SafeAndroidLog` | `Log.e` с редакцией; в release не пишет сообщение исключения |

## Пакет `quicksettings/`
- `VpnQuickTileService` — плитка вкл/выкл. Пока видна, подписана на `controller.state` + `tunnelStatus` и рисует `TileModel.from(...)` (чистая функция, покрыта `TileModelTest`). Клик: `Connected` → `requestDisconnect` + `disconnectSuspend`; `Disconnected/Error` → проверка разрешения VPN (нет — тост и открытие `MainActivity`), `requestConnect` + `connectSuspend` (ошибка → тост через `UserErrorText`); в переходных состояниях клик игнорируется. Команды идут в `NothingVpnApp.applicationScope`. Подзаголовок на Q+ показывает время работы в минутах.
- `VpnQuickTileRefresh.requestUpdate` — `TileService.requestListeningState`.

## Пакет `ui/` и `MainActivity.kt`

`MainActivity` (~90 строк) — только то, что требует Activity: тема, `AppNavGraph`, QR-сканер (`zxing-android-embedded`), запрос разрешения VPN, `FLAG_SECURE` (не в debug), запрос `POST_NOTIFICATIONS`.

Структура `ui/`:

| Пакет | Содержимое |
|---|---|
| `ui/navigation` | `AppNavGraph` — `NavHost`, маршруты, создание ViewModel экранов |
| `ui/home` | `HomeScreen.kt` (`HomeRoute`, `TunnelStatsCard`, форматтеры, `stateLabel`, `currentNetworkLabel`), `HomeViewModel` |
| `ui/profiles` | `ImportScreen` + `ImportViewModel` (ручной импорт и импорт из QR), `ProfileEditScreen` + `ProfileEditViewModel` |
| `ui/apps` | `RoutingScreen` + `RoutingViewModel` (режим и выбор приложений в одном месте), `AppSelection.kt` (общий блок: поиск, фильтры, массовый выбор, список), `AppPicker.kt` (`AppPickerRow`, `AppPackageIcon`, LRU-кэш иконок) |
| `ui/dns` | `DnsScreen`, `DnsAppsScreen`, общий `DnsViewModel` |
| `ui/settings` | `SettingsScreen` + `SettingsViewModel` |
| `ui/diagnostics` | `DiagnosticsScreen` + `DiagnosticsViewModel` |
| `ui/` | `AppViewModelFactory` (все ViewModel из `NothingVpnGraph`), `UserErrorText`, `NothingVpnTheme` |

| Маршрут | Composable / ViewModel | Назначение |
|---|---|---|
| `home` | `HomeRoute` / `HomeViewModel` | Кнопка подключения, статистика, режим маршрутизации, список профилей (выбор, задержка, избранное, копия, поделиться, редактирование), «самый быстрый», импорт, QR, переходы |
| `import` | `ImportRoute` / `ImportViewModel` | Ручной импорт `vless://` |
| `profile/edit/{profileId}` | `ProfileEditRoute` / `ProfileEditViewModel` | Редактирование профиля + проверка endpoint |
| `routing` | `RoutingRoute` / `RoutingViewModel` | Режим (весь трафик / только выбранные / все, кроме выбранных) и приложения для него; у каждого режима свой список, переключение ничего не стирает; сохраняемые наборы; после сохранения при активном VPN — кнопка «Переподключить» |
| `dns` | `DnsRoute` / `DnsViewModel` | System DNS / DoH, пресеты (Cloudflare, Google, Quad9, AdGuard), локальные домены, проверка доступности |
| `dns/apps` | `DnsAppsRoute` / `DnsViewModel` | Приложения с системным DNS |
| `settings` | `SettingsRoute` / `SettingsViewModel` | Автозапуск, восстановление, недоверенный Wi-Fi, доверенные сети, health-check, переход в системные VPN-настройки (Kill Switch) |
| `diagnostics` | `DiagnosticsRoute` / `DiagnosticsViewModel` | Состояние, версия, внешний IP (по кнопке), журнал, копирование/отправка отчёта |

ViewModel не делают сетевых запросов и не трогают `Context`: проверки задержки и внешнего IP идут через `ConnectivityProbePort` (`platform/AndroidConnectivityProbe`), SSID — через `WifiInfoProvider`, журнал — через `EventLogStore`. `HomeViewModel` перечитывает активный профиль при любом изменении списка профилей (его меняют импорт, QR и удаление на других экранах). Список установленных приложений — `platform/InstalledAppsRepository` (кэш на время процесса, обновляется при открытии экрана), общий для маршрутизации и DNS.

Главный экран: карточка состояния (активный профиль и сеть; статистика — только при подключении), карточка «Маршрутизация» (режим и число приложений; предупреждение, если в режиме «только выбранные» список пуст), профили с меню действий и кнопкой «+» (ссылка / QR). «Настройки» и «Диагностика» — иконки в верхней панели, «Настройки DNS» — в «Настройках».

`UserErrorText` — исчерпывающий перевод `VpnError` в строки `strings.xml` (см. [glossary.md](glossary.md)); классификация исключений — в `shared` (`toVpnError`).

## Тесты androidApp
- `src/test` (JVM): `storage/VaultTest`, `storage/ProfileSecretIntegrityTest`, `SessionConfigFileLifecycleTest`, `TileModelTest`, `DnsViewModelTest`, `AutomationDecisionsTest`, `TunnelWatchdogDecisionsTest`.
- `src/androidTest`: `VpnLifecycleInstrumentationTest` (начальный статус туннеля, удаление старого running-флага, константы action, `stopVpnAndAwait` в остановленном состоянии, замена секрета при upsert).

## Сборка/ProGuard
`proguard-rules.pro` сохраняет `io.nekohasekai.libbox.**`, native-методы, сериализаторы и `shared.domain/application`. `packaging.jniLibs.useLegacyPackaging = true`.
