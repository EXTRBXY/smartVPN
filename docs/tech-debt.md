# Наблюдения для рефакторинга

Список составлен по результатам чтения всего кода (коммит `287d861`). Это не план работ, а входные данные для него. Приоритеты: **P1** — риск багов/безопасности или блокирует остальное; **P2** — заметное ухудшение поддерживаемости; **P3** — гигиена.

## P1

### 1. ~~`serviceReload()` фактически мёртв~~ — сделано
Конфиг сессии хранится в памяти сервиса до teardown. Исходное описание:
`NothingVpnService.VpnBoxHandler.serviceReload()` читает `sessionConfigPath`, но `connectSuspend` обнуляет его сразу после удаления файла (по дизайну — чтобы секреты не лежали на диске). Следовательно reload/`setSystemProxyEnabled` не перезапускают ядро. Нужно решение: хранить JSON конфига в памяти сервиса на время сессии (и очищать в `teardown`) либо явно отключить возможность.

### 2. ~~Два источника истины о состоянии туннеля~~ — сделано
Введён `TunnelStatus` + `TunnelStatusRepository` (`StateFlow` в памяти процесса), broadcast и флаг на диске удалены, контроллер подписан на статус. См. [vpn-lifecycle.md](vpn-lifecycle.md). Исходное описание:
`VpnSessionController.state` (память), `VpnRunningState` (prefs + `@Volatile` + broadcast) и жизненное состояние сервиса. Выравнивание — `applyExternalTunnelRunning` с набором исключений для промежуточных состояний. Это источник гонок (плитка, автоматизация, Activity). Кандидат: единый `StateFlow` состояния в сервисе/репозитории состояния, доступный всем компонентам одного процесса, broadcast убрать.

### 3. ~~Хрупкие строковые коды и тексты ошибок~~ — сделано
Введены `VpnError`/`VpnException`, единый `toVpnError()` и исчерпывающий `UserErrorText`; см. [glossary.md](glossary.md). Исходное описание:
- Ошибки — произвольные `String`-сообщения исключений, `UserErrorText` сопоставляет по равенству и по подстрокам (`"tls"`, `"connect"` и т.д.), включая английские тексты парсера.
- Часть кодов не имеет отдельного текста, часть текстов (`smoke_dns_failed`, `smoke_tcp_failed`) не порождается.
- Рекомендация: sealed-иерархия `VpnError`/enum в `shared`, один маппер в UI.

### 4. ~~`MainActivity.kt` — 1838 строк~~ — сделано
Экраны разнесены по `ui/<экран>/`, навигация — `ui/navigation/AppNavGraph`, ViewModel на экран, `MainActivity` ~90 строк. Список приложений вынесен в `InstalledAppsRepository`, экран выбора — общий блок `AppSelection`. Исходное описание:
Все экраны, диалоги, кэш иконок, QR-лаунчер, ресивер и навигация в одном файле, функции `private`, один общий `AppViewModel`. Трудно тестировать и ревьюить. Разнести на `ui/<screen>/…Screen.kt` + отдельные ViewModel/состояния; навигацию вынести в `NavGraph`.

### 5. ~~Слои нарушены в `AppViewModel`~~ — сделано
Сетевые проверки — `ConnectivityProbePort` (`AndroidConnectivityProbe`), SSID — `WifiInfoProvider`, журнал — `EventLogStore`, QR-импорт — `ImportViewModel`. Остаток: `HomeViewModel`/`SettingsViewModel`/`RoutingViewModel` зависят от конкретного `AppPreferences`. Исходное описание:
ViewModel напрямую делает сокет-соединения (`testProfileLatency`, `testDnsEndpoint`, `testServerEndpoint`), HTTP-запрос внешнего IP, читает `AppPreferences`, комбинирует политику. По правилам проекта это должно быть портом (`ConnectivityProbePort`, `ExternalIpPort`) в `application` с реализацией в `platform`. `MainActivity` также напрямую вызывает `graph.profileRepository`/`sessionController` (QR-импорт).

### 6. Расхождение имён пакета и каталогов
Каталоги `com/nothing/mobilevpn`, пакеты и `applicationId` — `com.nothing.mobilecore`; `rootProject.name = NothingMobileVpn`; бренд в UI «Nothing Mobile». Выровнять в отдельной задаче: переименование `applicationId` потребует миграции данных (зашифрованное хранилище и ключ Keystore привязаны к приложению), action-строк и permission.

## P2

### 7. Глобальные синглтоны с изменяемым состоянием
`LibboxAndroidBootstrap`, `DefaultNetworkMonitor`, `DefaultNetworkListener`, `ConnectionEventLog`, `LibboxProxyProbe`. Нет внедрения зависимостей (ручной `NothingVpnGraph`, сервис его не использует). Для тестов сервис/мониторы недоступны. Рассмотреть Hilt/Koin или явную передачу через `Graph`; `NothingVpnService` получает политику сериализованной в `Intent`.

### 8. ~~`VpnSessionController` создаёт собственный scope и содержит смесь ответственностей~~ — сделано
Профили вынесены в `ProfileService`, `dispatcher`/`scope` внедряются. `AndroidSystemVpn` по-прежнему жёстко использует `Main.immediate`/`IO`. Исходное описание:
Подключение, CRUD профилей, нормализация/валидация профиля, синхронизация внешнего состояния, версия ядра. Dispatchers (`Dispatchers.Default`, `Main.immediate` в `AndroidSystemVpn`) жёстко зашиты — нельзя подставить тестовый. Разделить `ConnectUseCase`, `ProfileUseCases`, инжектировать `CoroutineDispatcher`/`CoroutineScope`.

### 9. ~~Типизация domain~~ — сделано
Enum с мягким сериализатором (`WireEnums.kt`), единый `VlessUuid` (v1–8), поле security в форме — выбор из вариантов; списки приложений разделены на `includedPackageNames`/`excludedPackageNames` (схема хранилища v2). Исходное описание:
`VlessProfile.type/security/encryption`, `TunnelPolicy.tunStack/singBoxLogLevel` — строки с проверками в разных местах (`validate`, парсер, компилятор, `normalizeProfileForSave`). Заменить `enum class`/value-классами; сейчас проверка UUID дублируется в `VpnSessionController` (версии 1–5) и `VlessLinkParser` (1–8).
Имя поля `allowedPackageNames` вводит в заблуждение для режима `PER_APP_EXCLUDED`.

### 10. ~~Семантика `failClosed`~~ — сделано
Свойство переименовано в `singleConnectAttempt` (ключ JSON прежний), связь с `autoReconnect` — в одном месте (`SettingsViewModel`). Разнести на два переключателя в UI — продуктовое решение, не сделано. Исходное описание:
Название намекает на Kill Switch, фактически — «без ретраев». UI-переключатель «Восстанавливать соединение» пишет в него `!enabled`, дублируя флаг `autoReconnect` в `AppPreferences` — два источника для двух связанных, но разных вещей (ретраи подключения vs. восстановление после сбоя). Переименовать/разделить.

### 11. Политика туннеля глобальна и размазана по хранилищам — решено
Профили, секреты, политика и настройки (`AppPreferences`) — в одном зашифрованном хранилище с версией схемы (v3). Остаётся только флаг «running» в `VpnRunningState`. Исходное описание:
`TunnelPolicy` в `nothing_vpn_profiles`, `RoutingRuleSet` и автоматизация — в открытых `nothing_vpn_user_settings`, `connectionRequested` — там же и дополнительно «running» в третьем файле. Консолидировать (Proto/Preferences DataStore), добавить версионирование схемы.

### 12. ~~Хранилище и крипто~~ — сделано
Своё хранилище на Keystore + AES-GCM с атомарной записью, чтения не пишут, двухфазная миграция; `security-crypto` нужен только для чтения старых данных. Исходное описание:
- `androidx.security:security-crypto:1.1.0-alpha06` (alpha, deprecated-направление).
- `ProfileRepositoryAndroid.listProfiles()` при чтении может **писать** (самоисцеление/санитизация), а каждое чтение секретов — отдельный вызов на профиль.
- `apply()` вместо `commit()` для критичных ключей (`active_profile_id`, список профилей) — окно потери при убийстве процесса.
- Миграция DataStore → EncryptedSharedPreferences с retry-маркером может быть удалена, когда не останется старых установок.

### 13. Конфигурация sing-box
- ~~`block` outbound~~ — убран; конфиг сверен с deprecated-списком до 1.14. Ядро в AAR — стабильная `1.14.3`. TUN `stack` устареет в 1.15. Маршрутизации нет, кроме `hijack-dns` и `final=proxy`.
- Параметр `profile` в `buildTunInbound` не используется.
- Поддерживаются только `tcp/ws/grpc`; нет `httpupgrade`, `h2`, `xhttp`, `mux`, multiplex, `packet_encoding`.
- Конфиг строится руками через `buildJsonObject`; рассмотреть типизированные модели сериализации.

### 14. Проверки доступности вводят в заблуждение
«Задержка» — TCP connect, не проверка VLESS/Reality; DNS-проверка — TCP на 443. В UI это подано как проверка, документация (README) это признаёт. Можно использовать `urlTest` ядра для активного профиля.

### 15. Тестовое покрытие — частично
Добавлены: `TileModelTest`, `DnsViewModelTest`, `AutomationDecisionsTest`, `ProfileServiceTest`, `StorageCompatibilityTest`, `VpnErrorTest`. Остаток ниже. Исходное описание:
Отсутствуют тесты: `NothingVpnService`, libbox-мост, `VpnAutomationCoordinator`, большинства ViewModel (есть `DnsViewModelTest`), UI (Compose), миграция. Единственный интеграционный набор — инструментальный и запускается на эмуляторе в CI. Начать с выноса логики решений (`maybeConnect`, `ProfileSecretIntegrity`-подобные) в чистые функции.

### 16. ~~Локализация~~ — сделано (кроме английской локали)
Все 162 строки UI вынесены в `strings.xml`. Исходное описание:
Русские строки смешаны: часть в `strings.xml`, большая часть захардкожена в `MainActivity`, `UserErrorText`, `VpnQuickTileService` (напр., «Отключение…», « · N мин»). Нет английской локали. Вынести всё в ресурсы.

## P3

### 17. Мусор и артефакты в git
- ~~`assets/singbox/arm64-v8a/sing-box` (≈58 МБ), `sing-box-1.13.9-android-arm64/`, `.kotlin/metadata/`~~ — удалены; лицензия sing-box перенесена в `third_party/sing-box/LICENSE`, `.kotlin/` в `.gitignore`. Бинарник остаётся в истории git (≈58 МБ при клоне) — чистить историю только отдельным решением.
- `androidApp/libs/libbox.aar` (≈40 МБ) закоммичен, хотя README пишет «не коммитится без артефакта». Определиться: Git LFS / релизные артефакты / сборка в CI.
- ~~`cursor_.md`~~ — перенесён в `docs/history/`.

### 18. Гигиена кода
- `VpnQuickTileService` и `MainActivity` дублируют регистрацию ресивера; ссылки на `Context.registerReceiver` повторяются 4 раза — вынести в helper.
- `AndroidSessionConfigWriter` — три похожих companion-функции очистки с одинаковыми параметрами.
- `LogRedaction.redactForLog`: unix-регулярка не маскирует произвольные пути (сохраняет их).
- `versionCode`/`versionName` захардкожены; нет схемы релизов.
- CI теперь запускает `lintDebug` (0 ошибок, ~50 предупреждений — разобрать). README и CI используют разные задачи для shared (`testAndroidHostTest` vs `check`; `check` их включает).
- `QUERY_ALL_PACKAGES`, `CAMERA`, `FINE_LOCATION` в манифесте: пересмотреть при публикации в Play.
- iOS-таргеты объявлены, `iosApp` нет, а `TunnelPolicy` содержит Android-специфику; `okio` подключён ради одного SHA-256.

## Предлагаемый порядок (ориентир)

1. Зафиксировать поведение тестами там, где их нет (парсер уже покрыт; добавить тесты на `AppViewModel`-логику после выноса портов).
2. Разрезать `MainActivity` по экранам без изменения поведения (P1-4).
3. Вынести сетевые проверки в порты (P1-5), ввести типизированные ошибки (P1-3).
4. Единый источник состояния туннеля (P1-2) и решение по `serviceReload` (P1-1).
5. Типизация domain и разделение `failClosed`/`autoReconnect` (P2-9, P2-10).
6. Чистка репозитория (P3-17), локализация (P2-16), DI (P2-7), обновление/замена крипто-слоя (P2-12).
7. Переименование пакета/applicationId (P1-6) — отдельным релизом с миграцией.
