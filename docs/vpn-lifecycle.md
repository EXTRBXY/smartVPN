# Жизненный цикл VPN-сессии

## Участники

`UI/плитка/автоматизация` → `VpnSessionController` → `AndroidSystemVpn` → `NothingVpnService` → `libbox CommandServer` → ядро sing-box → `VpnService.Builder`.

Источник истины о том, что туннель работает, — `VpnRunningState` (флаг в SharedPreferences + broadcast), а не `VpnSessionState` контроллера.

## Подключение

```
UI: vm.connect()                       (connectionRequested = true)
 └─ VpnSessionController.connect() → connectSuspend()            [Mutex]
     ├─ ProfileRepository: активный профиль + TunnelPolicy
     ├─ проверка секретов, policy.validate()
     ├─ SingBoxConfigCompiler.compile → JSON
     ├─ AndroidSessionConfigWriter.writeSessionConfig → путь (noBackupFilesDir)
     ├─ AndroidSystemVpn.startVpn(policy, path)                  [Main, таймаут 30 с]
     │    ├─ registerReceiver(VPN_CONNECT_RESULT, permission, NOT_EXPORTED)
     │    └─ startForegroundService(ACTION_CONNECT, path, policyJson)
     │         NothingVpnService.onStartCommand
     │          ├─ startForeground(connectedDevice)
     │          └─ [IO, lifecycleMutex] connectSuspend
     │              ├─ teardown() старого ядра; running=false
     │              ├─ Libbox.setup (однократно)
     │              ├─ прочитать JSON, УДАЛИТЬ файл
     │              ├─ CommandServer.start(); DefaultNetworkMonitor.start()
     │              ├─ startOrReloadService(json, OverrideOptions)
     │              │     └─ ядро → openTun() → Builder.establish() → fd
     │              ├─ проверка NeedWIFIState ↔ ACCESS_FINE_LOCATION
     │              └─ VpnRunningState.publish(true) + broadcast VPN_CONNECT_RESULT(ok)
     ├─ AndroidSystemVpn.smokeCheck                              [IO, таймаут 7 с]
     │    urlTest("proxy") + GET gstatic generate_204 + running-флаг
     └─ state = Connected
```

Разрешение VPN (`VpnService.prepare`) запрашивает только Activity (`onPrepareVpnAndConnect`); плитка и автоматизация при его отсутствии отказываются (тост / запись в журнал).

### Повторы
- `failClosed=true` (по умолчанию): 1 попытка.
- `failClosed=false`: до 3 попыток, состояние `Recovering(attempt, 3)`, паузы 1 с, 2 с. Между попытками вызывается `stopVpn()`.
- В UI переключатель «Восстанавливать соединение» (`setAutoReconnect`) также записывает `failClosed = !enabled`.

### Таймауты
| Операция | Таймаут | Код ошибки |
|---|---|---|
| Ожидание результата старта сервиса | 30 с | `vpn_connect_timeout` |
| Ожидание остановки | 10 с | `vpn_stop_timeout` |
| Smoke-проверка | 7 с (HTTP: connect/read по 3 с) | `smoke_timeout`, `smoke_proxy_failed` |
| TCP-проверки профиля/DNS | 4 с | — |

## Отключение

1. `vm.disconnect()` / плитка: `connectionRequested = false`.
2. `disconnectSuspend`: отмена `connectJob`, `Disconnecting`, `AndroidSystemVpn.stopVpnAndAwait`: если флаг running уже `false` — просто шлёт `ACTION_DISCONNECT`; иначе регистрирует ресивер `VPN_RUNNING_STATE` и ждёт `running=false`.
3. Сервис: `requestStop` → `teardown()` (закрыть TUN, остановить монитор сети, `closeService/close`) → `publish(false)` → `stopForeground` → `stopSelf`.
4. Кнопка «Отключить» в уведомлении шлёт `ACTION_DISCONNECT` с `EXTRA_USER_DISCONNECT=true` (сбрасывает `connectionRequested`).

`onRevoke` (пользователь/другое приложение отозвало VPN) → тот же путь остановки.

## Синхронизация состояния UI с реальностью

- `MainActivity.onStart`: `VpnRunningState.syncFromDisk` и `applyExternalTunnelRunning(isRunning)`; на время жизни Activity — ресивер `VPN_RUNNING_STATE`.
- `applyExternalTunnelRunning(true)` → `Connected`, очищает ошибку. `(false)` → `Disconnected`, кроме промежуточных состояний (см. [shared-module.md](shared-module.md)).
- Плитка слушает тот же broadcast и перерисовывается; `VpnRunningState.publish` дополнительно вызывает `TileService.requestListeningState`.

## Автоматизация (`VpnAutomationCoordinator`)

Все функции выключены по умолчанию (`AppPreferences`).

| Триггер | Условие подключения |
|---|---|
| Загрузка / обновление пакета (`BootCompletedReceiver`) | `autoStartAfterBoot` → `connectionRequested=true` → `maybeConnect(fromBoot)` |
| Появление сети (`NetworkCallback.onAvailable`, задержка 700 мс) | `autoReconnect && connectionRequested`, либо «недоверенный Wi-Fi» |
| Недоверенный Wi-Fi | `connectOnUntrustedWifi`, текущий SSID известен (нужен `ACCESS_FINE_LOCATION`) и не в `trustedWifiNames`; для доверенного SSID — не подключаться |
| Health-монитор (раз в 60 с) | `periodicHealthCheck` и VPN запущен: `smokeCheck`; после 2 подряд сбоев при `autoReconnect && connectionRequested` → `disconnectSuspend` → пауза 1 с → `maybeConnect` |

`maybeConnect` под своим `Mutex`; пропускает, если VPN уже запущен, нужно разрешение VPN, либо контроллер в `Connecting/Recovering/Connected`. Все решения пишутся в `ConnectionEventLog`.

## Что значит «Kill Switch»

Приложение **не** реализует системный Kill Switch. `failClosed` — только политика ретраев. Строгая блокировка включается пользователем в Android («Always-on VPN» + «Block connections without VPN»); экран настроек даёт переход и инструкцию.

## Известные особенности
- `VpnBoxHandler.serviceReload()` читает `sessionConfigPath`, который после старта всегда `null` (файл удалён до запуска ядра) — reload по запросу ядра фактически не выполняется. Отражено в [tech-debt.md](tech-debt.md).
- Сервис возвращает `START_STICKY` для CONNECT, но при воссоздании системой `intent == null` → `START_NOT_STICKY`; автоматическое восстановление после убийства процесса обеспечивает только автоматизация (если включена).
