# Модуль `shared`

Путь: `shared/src/commonMain/kotlin/com/nothing/mobilevpn/shared/` (пакет `com.nothing.mobilecore.shared.*`). Тесты — `shared/src/commonTest`, запускаются как `:shared:testAndroidHostTest` (и `:shared:check` в CI).

## domain

### `VlessProfile`
`@Serializable data class`. Поля: `id`, `name`, `uuid`, `host`, `port`, `type: VlessTransport` (`tcp|ws|grpc`), `security: VlessSecurity` (`tls|reality|none`), `encryption: VlessEncryption` (только `none`), `sni`, `alpn`, `fingerprint` (uTLS), `flow`, `realityPublicKey` (`pbk`), `realityShortId` (`sid`), `wsPath`, `wsHost`, `grpcServiceName`, `extraQuery` (неизвестные параметры ссылки), `favorite`, `sortOrder`.
Секретные поля: `uuid`, `realityPublicKey`, `realityShortId` — репозиторий хранит их отдельно (см. [data-and-storage.md](data-and-storage.md)).

`VlessUuid.isValid` — единая проверка UUID (версии 1–8) для парсера и формы; `VlessProfile.hasCompleteRealityKeys()` — для Reality нужны оба ключа.

### Enum со строковым представлением (`WireEnums.kt`)
`VlessTransport`, `VlessSecurity`, `VlessEncryption`, `TunStack`, `SingBoxLogLevel` реализуют `WireValue` (`wire` — строка в ссылке, конфиге и хранилище). `LenientWireSerializer` пишет `wire` (JSON совпадает с прежним, когда поля были `String`) и читает мягко: trim, без учёта регистра, неизвестное значение → fallback (`TCP`, `TLS`, `NONE`, `MIXED`, `INFO`) — то, во что прежний компилятор превращал нераспознанную строку. Так одно испорченное поле не делает нечитаемым весь список профилей. Строгий разбор для ссылок и ввода — `parse()`/`fromWire()` (null → ошибка). Покрыто `StorageCompatibilityTest`.

`id` для импортированных профилей — первые 16 hex SHA-256 от `uuid|host|port|type|security|sni|flow|wsPath|wsHost|grpcServiceName` (для enum берётся `wire`-строка, поэтому id совпадают с прежними) (повторный импорт той же ссылки даёт тот же id → upsert, а не дубль). Дубликаты через UI получают случайный UUID.

### `TunnelPolicy`
Единая политика туннеля, сериализуется в JSON, хранится в репозитории и передаётся в сервис через `Intent`-extra.

| Поле | По умолчанию | Смысл |
|---|---|---|
| `routingMode` | `FULL_TUN` | `FULL_TUN` — все приложения; `PER_APP_ALLOWED` — только `allowedPackageNames`; `PER_APP_EXCLUDED` — все, кроме `allowedPackageNames` |
| `allowedPackageNames` | `[]` | значение зависит от режима (имя поля историческое) |
| `tunIpv4Cidr` | `198.18.0.1/30` | адрес TUN |
| `mtu` | `1500` | 1280..9000 |
| `dnsMode` | `SYSTEM` | `SYSTEM` (локальный резолвер Android) или `DOH` |
| `dohServer` / `dohPath` / `dohSni` | `1.1.1.1` / `/dns-query` / `cloudflare-dns.com` | параметры DoH |
| `localDnsDomains` | `[]` | суффиксы доменов, резолвимые системным DNS даже в режиме DoH (≤128) |
| `systemDnsPackageNames` | `[]` | приложения, чьи DNS-запросы идут в системный DNS (≤4096) |
| `singleConnectAttempt` (в JSON — `failClosed`) | `true` | `true` → одна попытка подключения, без повторов; **не** системный Kill Switch |
| `singBoxLogLevel: SingBoxLogLevel` | `INFO` | `trace/debug/info/warn/error` |
| `tunStack: TunStack` | `MIXED` | `mixed/system/gvisor` |

`TunnelPolicy.validate(): Result<Unit>` — единственная точка валидации; ошибки — `VpnError` (`invalid_mtu`, `invalid_tun_ipv4_cidr`, `per_app_requires_packages`, `invalid_doh_server`, `invalid_doh_path`, `invalid_doh_sni_for_ip`, `invalid_package_name`, `too_many_packages`, `too_many_local_dns_domains`, `invalid_local_dns_domain`). Уровень журнала и стек TUN проверяет тип. Для IP-адреса DoH-сервера SNI обязателен.

### `VpnSessionState`
`Disconnected | Connecting | Recovering(attempt, maxAttempts) | Connected | Disconnecting | Error(error: VpnError)`.
`Error` выставляется кратковременно и сразу сменяется `Disconnected` (ошибка живёт в `VpnSessionController.lastError`).

### `DnsHostValidation.kt`
`isValidIpv4`, `isValidIpv4Cidr`, `isIpHost` (IPv4/IPv6; IPv6 — упрощённая проверка регулярным выражением). `internal`/`private` — используются `TunnelPolicy` и компилятором.

## application

### `VpnSessionController`
Сессия VPN: подключение, отключение, выравнивание по `TunnelStatus`. Зависимости — порты; `dispatcher` (по умолчанию `Default`) и `scope` внедряются, `eventLog` пишет неудачные попытки с цепочкой причин. Публичный API:

| Метод | Что делает |
|---|---|
| `state: StateFlow<VpnSessionState>`, `lastError: StateFlow<VpnError?>` | наблюдаемое состояние; ошибка — типизированная, см. [glossary.md](glossary.md) |
| `connect()` | запуск `connectSuspend` в собственном scope, игнорируется, если уже идёт |
| `connectSuspend(): Result<Unit>` | см. алгоритм ниже |
| `disconnectSuspend()` | отменяет `connectJob`, ставит `Disconnecting`, `systemVpn.stopVpnAndAwait()`, затем `Disconnected` |
| `isBusy` | подключено / подключается / отключается |
| `singBoxCoreVersion()` | версия ядра |
| `tunnelStatus: StateFlow<TunnelStatus>` | фактическое состояние туннеля из `SystemVpnPort`; контроллер подписан на него с момента создания |

**Алгоритм `connectSuspend`** (под `Mutex`; если `Connected` и туннель `Running` — сразу успех):
1. Прочитать политику; `maxAttempts = if (singleConnectAttempt) 1 else 3`.
2. Для каждой попытки: состояние `Connecting` (для 2+ — `Recovering`), очистить `lastError`; затем
   активный профиль → проверка секретов (`uuid` непустой; для Reality — оба ключа) → `policy.validate()` → `compile` → `configWriter.writeSessionConfig` → `systemVpn.startVpn(policy, path)` → `systemVpn.smokeCheck(policy)` → проверка `tunnelStatus is Running` → `Connected` (и повторная проверка после записи).
3. Неудача → `systemVpn.stopVpn()` (best-effort) и экспоненциальная пауза `1s << (attempt-1)`.
4. После всех попыток: `lastError`, состояние `Error` → `Disconnected`, `Result.failure`.

**Подписка на `tunnelStatus`**: `Stopped` при `Connected` → `Disconnected` и `lastError = error` (если остановка с причиной, напр. `vpn_revoked`; отключение пользователем ошибки не даёт); `Running` при `Disconnected/Error` → `Connected`. В `Connecting/Recovering/Disconnecting` события игнорируются (сервис кратко публикует `Stopped` при пересоздании ядра).

### `ProfileService`
Операции с профилями; общий `lastError` сессии не трогает, ошибки — в `Result`.

| Метод | Что делает |
|---|---|
| `parseLink(link)` | только разбор `vless://` |
| `importLink(link, activate)` | разбор + сохранение; QR-импорт делает профиль активным |
| `update(id, draft)` | нормализация (trim; ключи Reality сбрасываются для не-Reality) + валидация (имя, host, порт, UUID v1–8, полнота Reality) + upsert; `id` неизменяем |
| `delete(id)` | запрещено для активного профиля, пока `isTunnelBusy()` (`profile_delete_requires_disconnect`); сбрасывает `activeProfileId` |

### `VpnPorts.kt`
См. таблицу портов в [architecture.md](architecture.md).

## infrastructure

### `SingBoxConfigCompiler`
Генерирует JSON для ядра — подробно в [sing-box-config.md](sing-box-config.md). JSON без `prettyPrint` (меньше структуры при дампе).

### `VlessLinkParser` / `VlessLinkFormatter`
- Парсер — собственный разбор URI (`vless://uuid@host:port?query#name`), IPv6 в `[...]`, ручной percent-decode (`+` → пробел). Допустимо: `encryption=none`, `security ∈ {tls, reality, none}`, `type ∈ {tcp, ws, grpc}`. Для Reality обязательны `pbk`, `sid`, `sni`. Имена параметров: `sni|serverName|servername`, `serviceName|servicename`, `fp`, `alpn` (разделители `,` и `;`), `flow`, `path`, `host`. Неизвестные параметры сохраняются в `extraQuery`. Имя обрезается до 64 символов.
- Форматтер — обратная операция (percent-encode по байтам UTF-8, IPv6 оборачивается в `[]`); требует непустой `uuid`.
- Ошибки парсера — `VpnException` с `VpnError.Link*`; ссылка не `vless://` → `link_not_vless`.

### `LogRedaction`
`redactForLog` маскирует: UUID → `[uuid]`, `vless://...@` → `vless://[redacted]@`, `pbk=`, `sid=`, пути Windows/Unix (оставляя `[path]` для session-файлов). Внимание: unix-регулярка `/[^\s"]+` не заменяет, а сохраняет любые пути, кроме session-файлов (ветка `else path.value`) — по факту маскируются только session-пути.

## Тесты shared

| Файл | Покрывает |
|---|---|
| `VpnSessionControllerRecoveryTest` | ретраи, fail-closed, single-flight, ожидание остановки, удаление профиля, обновление профиля, игнор промежуточного «stopped» |
| `SingBoxConfigCompilerTest` | структура JSON (tun, DNS, outbounds, route) |
| `TunnelPolicyValidationTest` | границы MTU, DoH, лимиты списков |
| `VlessLinkParserTest`, `VlessLinkFormatterTest` | разбор и круговой формат |
| `LogRedactionTest` | маскирование |
