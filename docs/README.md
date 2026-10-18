# Документация Nothing Mobile VPN

Android-first VPN-клиент для протокола **VLESS** (TLS / Reality; транспорты tcp / ws / grpc) на ядре **sing-box** (сейчас стабильная `v1.14.3`, см. [sing-box-config.md](sing-box-config.md)), встроенном как `libbox.aar`. Shared-логика — Kotlin Multiplatform, UI — Jetpack Compose. iOS пока только заложен в архитектуре.

Документ описывает состояние кода версии 0.3.0 (октябрь 2026). Ориентир для агентов — [../CLAUDE.md](../CLAUDE.md).

## Оглавление

| Файл | О чём |
|---|---|
| [architecture.md](architecture.md) | Модули, слои, порты, граф зависимостей, модель процессов и потоков |
| [shared-module.md](shared-module.md) | Модуль `shared`: domain, `VpnSessionController`, компилятор конфига, парсер ссылок |
| [android-app.md](android-app.md) | Модуль `androidApp`: все пакеты и классы, UI-маршруты, ресурсы, манифест |
| [vpn-lifecycle.md](vpn-lifecycle.md) | Подключение, отключение, восстановление, автоматизация, плитка, sequence-диаграммы |
| [sing-box-config.md](sing-box-config.md) | Какой JSON генерируется и как policy превращается в `VpnService.Builder` |
| [data-and-storage.md](data-and-storage.md) | Где и что хранится, шифрование, миграция, session-файлы |
| [security.md](security.md) | Модель угроз и реализованные меры |
| [build-and-test.md](build-and-test.md) | Сборка, libbox.aar, подпись, CI, тесты, ручная проверка |
| [testing-checklist.md](testing-checklist.md) | Чек-лист тестовой эксплуатации и как сообщать о проблемах |
| [tech-debt.md](tech-debt.md) | Наблюдения для предстоящего рефакторинга (с приоритетами) |
| [glossary.md](glossary.md) | Термины и коды ошибок |

## Быстрый обзор

```
┌────────────────────────── androidApp ──────────────────────────┐
│ ui/<экран> + MainActivity ──► ViewModel экрана                 │
│                              │                                 │
│ quicksettings/ ─────────────►│  NothingVpnGraph (ручной DI)    │
│ BootCompletedReceiver ──► VpnAutomationCoordinator             │
│                              ▼                                 │
│ platform/  (адаптеры портов)  ── AndroidSystemVpn ──Intent──►  │
│                                                  vpn/NothingVpnService
│                                                   │ libbox/ (CommandServer, PlatformInterface)
└──────────────────────────────┬─────────────────────┼───────────┘
                               ▼                     ▼
                ┌────────────── shared ───────────┐  libbox.aar (sing-box)
                │ application: VpnSessionController│
                │ domain: TunnelPolicy, VlessProfile│
                │ infrastructure: SingBoxConfigCompiler│
                └──────────────────────────────────┘
```

Поток «Подключить»: профиль + политика → `SingBoxConfigCompiler` → JSON-файл сессии → `ACTION_CONNECT` в `NothingVpnService` → `CommandServer.startOrReloadService(json)` → ядро вызывает `openTun` → `VpnService.Builder.establish()` → `TunnelStatus.Running` в `TunnelStatusRepository` → smoke-проверка → `Connected`.
