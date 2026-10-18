# Жизненный цикл VPN-сессии

## Участники

`UI/плитка/автоматизация` → `VpnSessionController` → `AndroidSystemVpn` → `NothingVpnService` → `libbox CommandServer` → ядро sing-box → `VpnService.Builder`.

Источник истины о том, что туннель работает, — `TunnelStatus` в `TunnelStatusRepository` (`StateFlow` в памяти процесса), а не `VpnSessionState` контроллера. Пишет его только `NothingVpnService`; контроллер, плитка, UI и автоматизация подписываются.

| `TunnelStatus` | Когда |
|---|---|
| `Stopped(sessionId?, error?)` | начальное значение процесса; после остановки или неудачного старта (`error` — код причины: `vpn_revoked`, код ошибки старта) |
| `Starting(sessionId)` | сервис принял `ACTION_CONNECT` |
| `Running(sessionId, startedAtMillis)` | ядро запущено, TUN поднят |
| `Stopping` | идёт остановка |

Состояние намеренно не сохраняется на диск: сервис живёт в том же процессе, и после смерти процесса туннель гарантированно не работает. Старый флаг `nothing_vpn_running_flag` удаляется при старте. `sessionId` связывает статус с попыткой запуска, чтобы `AndroidSystemVpn.startVpn` не принял чужой результат.

## Подключение

```
UI: vm.connect()                       (connectionRequested = true)
 └─ VpnSessionController.connect() → connectSuspend()            [Mutex]
     ├─ ProfileRepository: активный профиль + TunnelPolicy
     ├─ проверка секретов, policy.validate()
     ├─ SingBoxConfigCompiler.compile → JSON
     ├─ AndroidSessionConfigWriter.writeSessionConfig → путь (noBackupFilesDir)
     ├─ AndroidSystemVpn.startVpn(policy, path)                  [Main, таймаут 30 с]
     │    ├─ sessionId = newSessionId()
     │    ├─ startForegroundService(ACTION_CONNECT, sessionId, path, policyJson)
     │    └─ ждёт Running(sessionId) / Stopped(sessionId, error)
     │         NothingVpnService.onStartCommand
     │          ├─ startForeground(connectedDevice); publish Starting(sessionId)
     │          └─ [IO, lifecycleMutex] connectSuspend
     │              ├─ teardown() старого ядра
     │              ├─ Libbox.setup (однократно)
     │              ├─ прочитать JSON, УДАЛИТЬ файл
     │              ├─ CommandServer.start(); DefaultNetworkMonitor.start()
     │              ├─ startOrReloadService(json, OverrideOptions)
     │              │     └─ ядро → openTun() → Builder.establish() → fd
     │              ├─ проверка NeedWIFIState ↔ ACCESS_FINE_LOCATION
     │              └─ publish Running(sessionId, now)   (ошибка → Stopped(sessionId, код))
     ├─ AndroidSystemVpn.smokeCheck                              [IO, таймаут 7 с]
     │    urlTest("proxy") + GET gstatic generate_204 (без keep-alive, до 4 попыток
     │    при сетевой ошибке: сразу после Running первый сокет получает RST) + TunnelStatus.Running
     └─ state = Connected (с перепроверкой, что туннель всё ещё Running)
```

Разрешение VPN (`VpnService.prepare`) запрашивает только Activity (`onPrepareVpnAndConnect`); плитка и автоматизация при его отсутствии отказываются (тост / запись в журнал).

### Повторы
- `singleConnectAttempt=true` (по умолчанию; ключ в JSON — `failClosed`): 1 попытка.
- `singleConnectAttempt=false`: до 3 попыток, состояние `Recovering(attempt, 3)`, паузы 1 с, 2 с. Между попытками вызывается `stopVpn()`.
- В UI переключатель «Восстанавливать соединение» (`setAutoReconnect`) также записывает `singleConnectAttempt = !enabled` (связь двух настроек осознанная и живёт только в `SettingsViewModel`).

### Таймауты
| Операция | Таймаут | Код ошибки |
|---|---|---|
| Ожидание результата старта сервиса | 30 с | `vpn_connect_timeout` |
| Ожидание остановки | 10 с | `vpn_stop_timeout` |
| Smoke-проверка | 7 с (HTTP: connect/read по 3 с) | `smoke_timeout`, `smoke_proxy_failed` |
| TCP-проверки профиля/DNS | 4 с | — |

## Отключение

1. `vm.disconnect()` / плитка: `connectionRequested = false`.
2. `disconnectSuspend`: отмена `connectJob`, `Disconnecting`, `AndroidSystemVpn.stopVpnAndAwait`: если туннель уже `Stopped` — сразу успех (сервис не запускается: из фона на Android 8+ это исключение); иначе шлёт `ACTION_DISCONNECT` и ждёт `Stopped`.
3. Сервис: `requestStop` → `Stopping` → `teardown()` (закрыть TUN, остановить монитор сети, `closeService/close`) → `Stopped(error)` → `stopForeground` → `stopSelf`.
4. Кнопка «Отключить» в уведомлении шлёт `ACTION_DISCONNECT` с `EXTRA_USER_DISCONNECT=true` (сбрасывает `connectionRequested`).

`onRevoke` (пользователь/другое приложение отозвало VPN) → тот же путь остановки с `error = vpn_revoked` (пользователь увидит причину). `serviceStop` от ядра — тоже полная остановка сервиса (раньше закрывался только TUN, и статус оставался «работает»).

## Синхронизация состояния UI с реальностью

- `VpnSessionController` с момента создания подписан на `tunnelStatus` (не зависит от того, открыт ли экран): `Stopped` при `Connected` → `Disconnected` (+ `lastError`, если у остановки есть причина); `Running` при `Disconnected/Error` → `Connected`. Во время `Connecting/Recovering/Disconnecting` события игнорируются — ими управляет сам контроллер.
- `connectSuspend` считает «уже подключено» только если и контроллер в `Connected`, и туннель `Running`.
- UI читает `controller.state` и `tunnelStatus` напрямую (без `stateIn` с начальным `Disconnected`).
- Плитка, пока видна, подписана на `controller.state` + `tunnelStatus` + «нажатие обрабатывается» и рисует `TileModel.from(...)`. Команды плитки выполняются в `NothingVpnApp.applicationScope`, чтобы не обрываться при отвязке плитки. Повторные нажатия во время подключения/отключения игнорируются. `TunnelStatusRepository.publish` вызывает `TileService.requestListeningState`.

## Автоматизация (`VpnAutomationCoordinator`)

Все функции выключены по умолчанию (`AppPreferences`).

| Триггер | Условие подключения |
|---|---|
| Загрузка / обновление пакета (`BootCompletedReceiver`) | `autoStartAfterBoot` → `connectionRequested=true` → `maybeConnect(BOOT)` |
| Сервис воссоздан после смерти процесса (`onStartCommand`, `intent == null`) | `connectionRequested` (без `autoReconnect`), доверенный Wi-Fi не мешает |
| Сторож `TunnelWatchdogJobService` (JobScheduler, раз в 15 мин) | процесс поднят заново и туннель в нём ещё не запускался (`Stopped` без `sessionId`); дальше как при перезапуске сервиса (`PROCESS_RESTART`) |
| Always-on VPN (`onStartCommand`, action `android.net.VpnService`) | всегда; ставит `connectionRequested=true`, доверенный Wi-Fi не мешает |
| Появление сети (`NetworkCallback.onAvailable`, задержка 700 мс) | `autoReconnect && connectionRequested`, либо «недоверенный Wi-Fi» |
| Недоверенный Wi-Fi | `connectOnUntrustedWifi`, текущий SSID известен (нужен `ACCESS_FINE_LOCATION`) и не в `trustedWifiNames`; для доверенного SSID — не подключаться |
| Health-монитор (раз в 60 с) | `periodicHealthCheck` и VPN запущен: `smokeCheck`; после 2 подряд сбоев при `autoReconnect && connectionRequested` → `disconnectSuspend` → пауза 1 с → `maybeConnect` |

`maybeConnect(reason, trigger)` под своим `Mutex` собирает входные данные и зовёт чистую функцию `decideAutoConnect` (`platform/AutomationDecisions.kt`, покрыта `AutomationDecisionsTest`; повод — `AutoConnectTrigger`: `AUTOMATIC`, `BOOT`, `PROCESS_RESTART`, `ALWAYS_ON`): пропуск, если доверенный Wi-Fi, подключение не запрошено, туннель `Running`/`Starting` или контроллер занят, нет разрешения VPN; флаг «VPN нужен» для недоверенного Wi-Fi ставится и при пропуске. Порог перезапуска по health-check — `shouldRestartAfterHealthFailures`. Решения и ошибки (кодом `VpnError`) пишутся в `ConnectionEventLog`.

## Что значит «Kill Switch»

Приложение **не** реализует системный Kill Switch. `singleConnectAttempt` — только политика повторов подключения. Строгая блокировка включается пользователем в Android («Always-on VPN» + «Block connections without VPN»); экран настроек даёт переход и инструкцию.

## Известные особенности
- `VpnBoxHandler.serviceReload()` перезапускает ядро конфигом сессии, который хранится только в памяти сервиса (`sessionConfig`, очищается в teardown): файл с секретами удалён до старта ядра. Ошибка reload останавливает сервис с причиной.
- Сервис возвращает `START_STICKY` для CONNECT. Старт не от приложения разбирает чистая `classifyServiceStart` (`vpn/ServiceStartDecisions.kt`, `ServiceStartDecisionsTest`):
  - `intent == null` — система воссоздала сервис после смерти процесса (`PROCESS_RESTART`);
  - action `VpnService.SERVICE_INTERFACE` — запуск как Always-on VPN (загрузка, обновление пакета, включение в настройках) (`ALWAYS_ON`).

  В обоих случаях конфига с секретами уже нет (он удаляется до старта ядра, в `Intent` секретов нет), поэтому сервис зовёт `VpnAutomationCoordinator.restoreTunnel(trigger)` в `applicationScope`: тот через `decideAutoConnect` решает, нужно ли подключение, и вызывает обычный `VpnSessionController.connectSuspend()` — конфиг заново собирается из зашифрованного хранилища, сервису приходит штатный `ACTION_CONNECT`. Если подключение пропущено или не удалось, сервис останавливается (`stopSelfIfIdle`). Запуск foreground-сервиса из фона здесь разрешён, потому что у приложения выдано разрешение VPN (`OP_ACTIVATE_VPN`).
- **Сторож туннеля** (`platform/TunnelWatchdog.kt`). ColorOS/realme после краша процесса (`am crash`) не перезапускает `START_STICKY`-сервис (`dumpsys activity services`: `app=null`, `crashCount=1`, `nextRestartTime=--`) и не стартует Always-on — код приложения не выполняется. Поэтому, пока туннель `Running` и `connectionRequested=true`, координатор держит periodic persisted job (`JobInfo.setPeriodic(15 мин)`, `NETWORK_TYPE_ANY`, `setPersisted(true)` — переживает перезагрузку, нужен `RECEIVE_BOOT_COMPLETED`). Решение о планировании — чистая `decideWatchdogSchedule` (`TunnelWatchdogDecisions.kt`, `TunnelWatchdogDecisionsTest`):
  - `connectionRequested=false` (пользователь отключил: экран, плитка, уведомление) → задача снимается;
  - `Running` → ставится (если ещё не стоит; повторно не перепланируется);
  - прочие состояния (`Starting`, `Stopping`, `Stopped` при переподключении или после ошибки) → без изменений.

  Подписка на `connectionRequested` (прямо из `VaultStore`) и `TunnelStatus` начинается только после `awaitReady()`: до загрузки хранилища флаг читался бы как false и снимал сторожа в только что поднятом процессе. Когда задача срабатывает, JobScheduler при необходимости сам запускает процесс; `TunnelWatchdogJobService` проверяет `shouldWatchdogRestore` (туннель в этом процессе ещё не запускался) и зовёт `restoreTunnel(PROCESS_RESTART)` — тот же путь, что и у воссозданного сервиса: решение `decideAutoConnect` (нужен `connectionRequested`, разрешение VPN), конфиг из хранилища, секретов в задаче нет. В живом процессе срабатывание — пустая проверка. Ограничения: время срабатывания выбирает система (до ~15 мин, в Doze — позже); «Принудительно остановить» и свайп с force-stop на некоторых прошивках снимают и задачи — тогда туннель вернётся только при следующем запуске приложения.
- Диагностика 2026-10-18 (realme, Android 16): «VPN service: restarted without command» после «tunnel ready» были не убийствами из-за батареи — `exit-info` показывал `PACKAGE UPDATED` (переустановка), а следом система стартовала сервис как Always-on (`callingPackage = android`), который раньше игнорировался. Причину смерти процесса смотреть в `adb shell dumpsys activity exit-info com.nothing.mobilecore`.
