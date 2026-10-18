# Архитектура

## Модули Gradle

| Модуль | Плагины | Назначение |
|---|---|---|
| `:shared` | `com.android.kotlin.multiplatform.library`, `kotlin.multiplatform`, `serialization` | Платформенно-независимая логика. Таргеты: `android` (с `withHostTestBuilder`), `iosX64`, `iosArm64`, `iosSimulatorArm64`. Зависимости: coroutines 1.11.0, serialization-json 1.11.0, okio 3.9.1 (используется только для SHA-256 в `VlessLinkParser.stableId`). |
| `:androidApp` | `com.android.application`, `kotlin.plugin.compose`, `serialization` | Приложение. Зависит от `:shared` и `libbox.aar` (через `flatDir`, а не `files()` — иначе AGP не распаковывает AAR). |

Версии: AGP 9.2.1, Kotlin 2.2.10, Gradle 9.8.1, compileSdk 37, targetSdk 35, minSdk 26, JVM 17, Compose BOM 2026.09.00. SDK и координаты приложения задаются в `gradle.properties` (`android.compileSdk`, `android.targetSdk`, `android.minSdk`, `app.namespace`, `app.applicationId` = `com.nothing.mobilecore`).

`settings.gradle.kts` использует `FAIL_ON_PROJECT_REPOS`: репозитории объявляются только в корне (google, mavenCentral, flatDir `androidApp/libs`).

## Слои

Цель (из `.cursor/rules`): `domain → application → infrastructure → platform → ui`, зависимости только внутрь.

Фактическое размещение:

| Слой | Где | Содержимое |
|---|---|---|
| domain | `shared/.../domain` | `VlessProfile`, `TunnelPolicy` (+`validate()`), `RoutingMode`, `DnsMode`, `VpnSessionState`, `DnsHostValidation` |
| application | `shared/.../application` | Порты (`VpnPorts.kt`), `VpnSessionController`, `SessionConfigWriter` |
| infrastructure | `shared/.../infrastructure` | `SingBoxConfigCompiler`, `VlessLinkParser`, `VlessLinkFormatter`, `LogRedaction` |
| platform | `androidApp/.../platform`, `.../libbox`, `.../vpn`, `.../quicksettings` | Реализации портов, `VpnService`, мост к libbox, автоматизация |
| ui | `androidApp/.../ui`, `MainActivity.kt` | Compose-экраны по пакетам, ViewModel на экран, навигация, тема, тексты ошибок |

Отступления от идеала описаны в [tech-debt.md](tech-debt.md) (в частности, часть ViewModel зависит от конкретных `AppPreferences`/`TunnelStatsMonitor`, а не от портов).

## Порты (shared/application/VpnPorts.kt)

| Порт | Реализация (Android) | Роль |
|---|---|---|
| `SingBoxCoreInfoPort` | `AndroidSingBoxCoreInfo` | `Libbox.version()` |
| `SingBoxConfigCompilerPort` | `SingBoxConfigCompiler` (из shared) | `compile(profile, policy): Result<String>` |
| `ProfileRepositoryPort` | `ProfileRepositoryAndroid` | профили, активный профиль, `TunnelPolicy`, `observeProfiles(): Flow` |
| `SystemVpnPort` | `AndroidSystemVpn` | `startVpn`, `stopVpn`, `stopVpnAndAwait`, `smokeCheck`, `vpnPermissionNeeded` |
| `SessionConfigWriter` (в `VpnSessionController.kt`) | `AndroidSessionConfigWriter` | запись JSON сессии, возврат пути |

## Композиция (DI вручную)

`NothingVpnApp` (класс `Application`) лениво создаёт `NothingVpnGraph`:

```
NothingVpnGraph(context)
 ├─ vaultStore / vaultBootstrap   зашифрованное хранилище и его загрузка/миграция
 ├─ appPreferences         AppPreferences(vaultStore)  (автоматизация, наборы приложений, connectionRequested)
 ├─ profileRepository      ProfileRepositoryAndroid(vaultStore)
 ├─ configCompiler         SingBoxConfigCompiler
 ├─ singBoxCoreInfo        AndroidSingBoxCoreInfo
 ├─ tunnelStatus           TunnelStatusRepository  (StateFlow<TunnelStatus>, единственный источник истины)
 ├─ systemVpn              AndroidSystemVpn(tunnelStatus)
 ├─ sessionConfigWriter    AndroidSessionConfigWriter
 ├─ tunnelStatsMonitor     TunnelStatsMonitor
 ├─ connectivityProbe      AndroidConnectivityProbe
 ├─ wifiInfo / installedApps / eventLog   WifiInfoProvider, InstalledAppsRepository, EventLogStore
 ├─ sessionController      VpnSessionController(profileRepository, configCompiler, singBoxCoreInfo, systemVpn, sessionConfigWriter)
 └─ profileService         ProfileService(profileRepository)
```

Доступ: `(application as NothingVpnApp).graph` — из `MainActivity`, `VpnQuickTileService`, `BootCompletedReceiver` и `TunnelWatchdogJobService` (через `app.automation`). `NothingVpnService` берёт из графа `tunnelStatus`, `appPreferences` и `automation.restoreTunnel` (перезапуск системой, Always-on); параметры обычного подключения получает через `Intent`-extras (путь к конфигу и политику, без секретов).

`NothingVpnApp.onCreate`: запускает загрузку хранилища (`VaultBootstrap` → `VaultStore.initialize`), `LibboxAndroidBootstrap.attachBase/ensureSetup`, `automation.start()`, `tunnelStatsMonitor.start()`, создаёт канал уведомлений `nothing_vpn` (IMPORTANCE_LOW).

## Процессы и потоки

- Всё приложение — **один процесс**. Сервис, UI, плитка и ресивер делят синглтоны (`DefaultNetworkMonitor`, граф с `TunnelStatusRepository`).
- Команды в сервис идут через `Intent`, обратно — через `StateFlow<TunnelStatus>` в `TunnelStatusRepository` (единственный источник истины, только в памяти: после смерти процесса туннель гарантированно остановлен). См. [vpn-lifecycle.md](vpn-lifecycle.md).
- `VpnSessionController` получает `CoroutineScope` параметром (по умолчанию `SupervisorJob() + Dispatchers.Default`); `connectSuspend` сериализован `Mutex` (single-flight).
- `AndroidSystemVpn.startVpn/stopVpnAndAwait` выполняются на `Dispatchers.Main.immediate` (стартуют сервис); результат ждут по `TunnelStatus` с `withTimeoutOrNull` (30 с на старт, 10 с на остановку).
- `NothingVpnService` работает в `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, все изменения жизненного цикла ядра — под `lifecycleMutex`.

## Глобальные синглтоны (важно для рефакторинга)

`LibboxAndroidBootstrap`, `DefaultNetworkMonitor`, `DefaultNetworkListener` (actor), `ConnectionEventLog`, `LocalResolver`, `LibboxProxyProbe`, `SafeAndroidLog`. Они хранят изменяемое состояние на уровне процесса и затрудняют unit-тестирование.

## Расширение под iOS

Заложено: таргеты iOS в `shared`, порты платформы. Не сделано: `iosApp`, `PacketTunnelProvider`, реализации портов на Keychain/NE. Часть `TunnelPolicy` Android-специфична (`includedPackageNames`/`excludedPackageNames`, `systemDnsPackageNames`, `tunStack`) — при добавлении iOS её нужно вынести в платформенный раздел policy.
