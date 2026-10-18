# Генерация конфигурации sing-box

Класс: `shared/.../infrastructure/SingBoxConfigCompiler.kt`. Вход — `VlessProfile` + `TunnelPolicy`, выход — компактная JSON-строка (`Json { prettyPrint = false }`). Версия ядра в `libbox.aar` — стабильная **`v1.14.3`** (Go 1.26.2, только `arm64-v8a`), собрана `scripts/build-libbox-aar.ps1` из тега; единственная локальная правка в дереве sing-box — разделитель `PATH` для NDK на Windows в скрипте сборки, код ядра не изменён. Проверить: строка `1.14.3` в `jni/arm64-v8a/libbox.so`. Перед обновлением ядра сверяться с https://sing-box.sagernet.org/changelog/ — Android-API libbox меняется между версиями. Лицензия — `third_party/sing-box/LICENSE`; формат DNS-серверов (`type`/`server`/`action`) — новый (1.12+).

## Структура результата

```jsonc
{
  "log":      { "level": "<policy.singBoxLogLevel.wire>" },
  "dns":      { ... },                 // см. ниже
  "inbounds": [ { "type": "tun", "tag": "tun-in", ... } ],
  "outbounds": [ vless "proxy-node", selector "proxy", direct "direct" ],
  "route":    { "final": "proxy", "auto_detect_interface": true,
                "rules": [ { "port": [53], "action": "hijack-dns" } ] }
}
```

### inbound `tun-in`
`address: [tunIpv4Cidr]`, `mtu`, `auto_route: true`, `strict_route: true`, `stack: tunStack`. На Android `auto_route` приводит к тому, что libbox передаёт маршруты, DNS и фильтры приложений в `TunOptions`, а `NothingVpnService.openTun` применяет их к `VpnService.Builder`. Фильтрация по приложениям задаётся не в JSON, а через `OverrideOptions.includePackage/excludePackage` при `startOrReloadService`.

### outbounds
- `proxy-node` — `vless`: `server`, `server_port`, `uuid`, опционально `flow`; блоки `tls` и `transport` по профилю.
- `proxy` — `selector` с единственным `proxy-node` (нужен, чтобы `urlTest("proxy")` из `LibboxProxyProbe` тестировал настоящий исходящий канал).
- `direct`. Устаревший special outbound `block` (удалён в 1.13) не генерируется — блокировка при необходимости делается правилом с `"action": "reject"`; проверяет `compile_hasNoLegacySpecialOutbounds`.

**TLS** (`security != none`): `enabled`, `server_name = sni ?: host`, `alpn`, `utls{enabled, fingerprint}` при `fp`, при `security=reality` — `reality{enabled, public_key, short_id}` (обязательны, иначе ошибки `reality_public_key_missing` / `reality_short_id_missing`).

**Transport**: `tcp` → нет блока; `ws` → `{type: ws, path, headers.Host}`; `grpc` → `{type: grpc, service_name}`.

### dns
| `dnsMode` | Результат |
|---|---|
| `SYSTEM` | `final: local`, `reverse_mapping: true`, один сервер `{type: local, tag: local, prefer_go: false}` |
| `DOH` | `final: doh`, сервер `local` + `{type: https, tag: doh, server, server_port: 443, path, tls{server_name: sni}}`; если `dohServer` — не IP, добавляется `domain_resolver: local`; правила `rules[]` с `action: route → local` для `domain_suffix` из `localDnsDomains` и `package_name` из `systemDnsPackageNames` |

Для приложений с собственным DoH системные DNS-правила не действуют (ограничение, описанное в README).

### route
Единственное правило — перехват DNS (порт 53 → `hijack-dns`); всё остальное идёт в `proxy` (`final`). `auto_detect_interface` включён. Нет geoip/geosite/rule-set — маршрутизация по доменам/адресам отсутствует, управление только по приложениям.

## Соответствие policy ↔ платформа

| Поле policy | Где применяется |
|---|---|
| `tunIpv4Cidr`, `mtu`, `tunStack`, `singBoxLogLevel`, DNS | JSON |
| `routingMode`, `includedPackageNames` / `excludedPackageNames` | `OverrideOptions` (`includePackage` / `excludePackage`) |
| `singleConnectAttempt` | `VpnSessionController` (повторы), не ядро |
| DoH/локальные DNS | JSON + `LocalResolver` (локальный резолвер через `DnsResolver` Android) |

## Что учесть при изменениях
- Любое поле, которое добавляется в `TunnelPolicy`, нужно: сериализовать (значение по умолчанию!), валидировать в `validate()`, учесть в компиляторе/`OverrideOptions`, покрыть тестом в `SingBoxConfigCompilerTest`/`TunnelPolicyValidationTest`. Политика хранится как JSON и читается с `ignoreUnknownKeys`, поэтому старые сохранения должны оставаться совместимы.
- Сверено со списком устаревших возможностей sing-box (до 1.14): удалённых полей конфиг не использует. Впереди: опция TUN `stack` устарела в 1.15 и будет удалена в 1.17 — при обновлении ядра до 1.15+ убрать `tunStack` из конфига. Перед любым обновлением ядра сверяться с https://sing-box.sagernet.org/deprecated/ и смотреть WARN в журнале ядра при старте.
- Секреты попадают в JSON → файл существует только между `writeSessionConfig` и чтением сервисом (удаляется до запуска ядра).
- Отклонения от документации sing-box требуют обоснования (правило проекта). Документация: https://sing-box.sagernet.org/configuration/.
