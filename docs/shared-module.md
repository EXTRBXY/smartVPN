# Модуль `shared`

Путь: `shared/src/commonMain/kotlin/com/nothing/mobilevpn/shared/` (пакет `com.nothing.mobilecore.shared.*`). Тесты — `shared/src/commonTest`, запускаются как `:shared:testAndroidHostTest` (и `:shared:check` в CI).

## domain

### `VlessProfile`
`@Serializable data class`. Поля: `id`, `name`, `uuid`, `host`, `port`, `type` (`tcp|ws|grpc`), `security` (`tls|reality|none`), `encryption` (только `none`), `sni`, `alpn`, `fingerprint` (uTLS), `flow`, `realityPublicKey` (`pbk`), `realityShortId` (`sid`), `wsPath`, `wsHost`, `grpcServiceName`, `extraQuery` (неизвестные параметры ссылки), `favorite`, `sortOrder`.
Секретные поля: `uuid`, `realityPublicKey`, `realityShortId` — репозиторий хранит их отдельно (см. [data-and-storage.md](data-and-storage.md)).

`id` для импортированных профилей — первые 16 hex SHA-256 от `uuid|host|port|type|security|sni|flow|wsPath|wsHost|grpcServiceName` (повторный импорт той же ссылки даёт тот же id → upsert, а не дубль). Дубликаты через UI получают случайный UUID.

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
| `failClosed` | `true` | `true` → одна попытка подключения, без ретраев; **не** системный Kill Switch |
| `singBoxLogLevel` | `info` | `trace/debug/info/warn/error` |
| `tunStack` | `mixed` | `mixed/system/gvisor` |

`TunnelPolicy.validate(): Result<Unit>` — единственная точка валидации; ошибки — строковые коды (`invalid_mtu`, `invalid_tun_ipv4_cidr`, `per_app_requires_packages`, `invalid_doh_server`, `invalid_doh_path`, `invalid_doh_sni_for_ip`, `invalid_singbox_log_level`, `invalid_tun_stack`, `invalid_package_name`, `too_many_packages`, `too_many_local_dns_domains`, `invalid_local_dns_domain`). Для IP-адреса DoH-сервера SNI обязателен.

### `VpnSessionState`
`Disconnected | Connecting | Recovering(attempt, maxAttempts) | Connected | Disconnecting | Error(message)`.
`Error` выставляется кратковременно и сразу сменяется `Disconnected` (текст ошибки живёт в `VpnSessionController.lastError`).

### `DnsHostValidation.kt`
`isValidIpv4`, `isValidIpv4Cidr`, `isIpHost` (IPv4/IPv6; IPv6 — упрощённая проверка регулярным выражением). `internal`/`private` — используются `TunnelPolicy` и компилятором.

## application

### `VpnSessionController`
Центральный use-case-класс. Зависимости — порты. Публичный API:

| Метод | Что делает |
|---|---|
| `state: StateFlow<VpnSessionState>`, `lastError: StateFlow<String?>` | наблюдаемое состояние; `lastError` проходит `LogRedaction.redactUserMessage` |
| `importVlessLink(link): Result<VlessProfile>` | только парсинг (`VlessLinkParser`), сохранять должен вызывающий |
| `connect()` | запуск `connectSuspend` в собственном scope, игнорируется, если уже идёт |
| `connectSuspend(): Result<Unit>` | см. алгоритм ниже |
| `disconnectSuspend()` | отменяет `connectJob`, ставит `Disconnecting`, `systemVpn.stopVpnAndAwait()`, затем `Disconnected` |
| `updateProfileSuspend(id, draft)` | нормализация + валидация + upsert; `id` неизменяем |
| `deleteProfileSuspend(id)` | запрещено для активного профиля при активном VPN (`profile_delete_requires_disconnect`); при удалении активного сбрасывает `activeProfileId` |
| `singBoxCoreVersion()` | версия ядра |
| `applyExternalTunnelRunning(running)` | синхронизация состояния с реальностью (плитка, ресивер, старт Activity) |

**Алгоритм `connectSuspend`** (под `Mutex`; если уже `Connecting/Connected` — сразу успех):
1. Прочитать политику; `maxAttempts = if (failClosed) 1 else 3`.
2. Для каждой попытки: состояние `Connecting` (для 2+ — `Recovering`), очистить `lastError`; затем
   активный профиль → проверка секретов (`uuid` непустой; для Reality — оба ключа) → `policy.validate()` → `compile` → `configWriter.writeSessionConfig` → `systemVpn.startVpn(policy, path)` → `systemVpn.smokeCheck(policy)` → `Connected`.
3. Неудача → `systemVpn.stopVpn()` (best-effort) и экспоненциальная пауза `1s << (attempt-1)`.
4. После всех попыток: `lastError`, состояние `Error` → `Disconnected`, `Result.failure`.

**`applyExternalTunnelRunning(false)`** игнорируется в `Connecting/Recovering/Disconnected/Disconnecting` (сервис публикует «stopped» при пересоздании ядра), иначе переводит в `Disconnected` и, при `failClosed`, пишет `vpn_not_running_after_connect`.

Валидация профиля при сохранении (`validateProfileForSave`): имя, host, порт 1..65535, UUID (версии 1–5), полнота Reality. Парсер принимает UUID версий 1–8 — небольшое расхождение.

### `VpnPorts.kt`
См. таблицу портов в [architecture.md](architecture.md).

## infrastructure

### `SingBoxConfigCompiler`
Генерирует JSON для ядра — подробно в [sing-box-config.md](sing-box-config.md). JSON без `prettyPrint` (меньше структуры при дампе).

### `VlessLinkParser` / `VlessLinkFormatter`
- Парсер — собственный разбор URI (`vless://uuid@host:port?query#name`), IPv6 в `[...]`, ручной percent-decode (`+` → пробел). Допустимо: `encryption=none`, `security ∈ {tls, reality, none}`, `type ∈ {tcp, ws, grpc}`. Для Reality обязательны `pbk`, `sid`, `sni`. Имена параметров: `sni|serverName|servername`, `serviceName|servicename`, `fp`, `alpn` (разделители `,` и `;`), `flow`, `path`, `host`. Неизвестные параметры сохраняются в `extraQuery`. Имя обрезается до 64 символов.
- Форматтер — обратная операция (percent-encode по байтам UTF-8, IPv6 оборачивается в `[]`); требует непустой `uuid`.
- Сообщения исключений парсера на английском и используются `UserErrorText` как ключи (`"Only vless:// links are supported."`, `"Invalid UUID format."`, `"Missing host."`, `"Invalid port."`) — хрупкая связь.

### `LogRedaction`
`redactForLog` / `redactUserMessage` маскируют: UUID → `[uuid]`, `vless://...@` → `vless://[redacted]@`, `pbk=`, `sid=`, пути Windows/Unix (оставляя `[path]` для session-файлов). Внимание: unix-регулярка `/[^\s"]+` не заменяет, а сохраняет любые пути, кроме session-файлов (ветка `else path.value`) — по факту маскируются только session-пути.

## Тесты shared

| Файл | Покрывает |
|---|---|
| `VpnSessionControllerRecoveryTest` | ретраи, fail-closed, single-flight, ожидание остановки, удаление профиля, обновление профиля, игнор промежуточного «stopped» |
| `SingBoxConfigCompilerTest` | структура JSON (tun, DNS, outbounds, route) |
| `TunnelPolicyValidationTest` | границы MTU, DoH, лимиты списков |
| `VlessLinkParserTest`, `VlessLinkFormatterTest` | разбор и круговой формат |
| `LogRedactionTest` | маскирование |
