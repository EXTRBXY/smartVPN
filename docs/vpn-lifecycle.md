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
| Загрузка / обновление пакета (`BootCompletedReceiver`) | `autoStartAfterBoot` → `connectionRequested=true` → `maybeConnect(fromBoot)` |
| Появление сети (`NetworkCallback.onAvailable`, задержка 700 мс) | `autoReconnect && connectionRequested`, либо «недоверенный Wi-Fi» |
| Недоверенный Wi-Fi | `connectOnUntrustedWifi`, текущий SSID известен (нужен `ACCESS_FINE_LOCATION`) и не в `trustedWifiNames`; для доверенного SSID — не подключаться |
| Health-монитор (раз в 60 с) | `periodicHealthCheck` и VPN запущен: `smokeCheck`; после 2 подряд сбоев при `autoReconnect && connectionRequested` → `disconnectSuspend` → пауза 1 с → `maybeConnect` |

`maybeConnect` под своим `Mutex`; пропускает, если туннель `Running`/`Starting`, нужно разрешение VPN, либо контроллер в `Connecting/Recovering/Connected`. Все решения пишутся в `ConnectionEventLog`.

## Что значит «Kill Switch»

Приложение **не** реализует системный Kill Switch. `singleConnectAttempt` — только политика повторов подключения. Строгая блокировка включается пользователем в Android («Always-on VPN» + «Block connections without VPN»); экран настроек даёт переход и инструкцию.

## Известные особенности
- `VpnBoxHandler.serviceReload()` читает `sessionConfigPath`, который после старта всегда `null` (файл удалён до запуска ядра) — reload по запросу ядра фактически не выполняется. Отражено в [tech-debt.md](tech-debt.md).
- Сервис возвращает `START_STICKY` для CONNECT. При воссоздании системой (`intent == null`) конфига с секретами уже нет, поэтому сервис сразу останавливается; восстановление делает автоматизация (стартует в `NothingVpnApp.onCreate`), если `autoReconnect` и `connectionRequested`.
