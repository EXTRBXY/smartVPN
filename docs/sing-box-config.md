# Генерация конфигурации sing-box

Класс: `shared/.../infrastructure/SingBoxConfigCompiler.kt`. Вход — `VlessProfile` + `TunnelPolicy`, выход — компактная JSON-строка (`Json { prettyPrint = false }`). Версия ядра в репозитории — sing-box **1.13.x** (`libbox.aar`, лицензия — `third_party/sing-box/LICENSE`); формат DNS-серверов (`type`/`server`/`action`) — новый (1.12+).

## Структура результата

```jsonc
{
  "log":      { "level": "<policy.singBoxLogLevel>" },
  "dns":      { ... },                 // см. ниже
  "inbounds": [ { "type": "tun", "tag": "tun-in", ... } ],
  "outbounds": [ vless "proxy-node", selector "proxy", direct "direct", block "block" ],
  "route":    { "final": "proxy", "auto_detect_interface": true,
                "rules": [ { "port": [53], "action": "hijack-dns" } ] }
}
```

### inbound `tun-in`
`address: [tunIpv4Cidr]`, `mtu`, `auto_route: true`, `strict_route: true`, `stack: tunStack`. На Android `auto_route` приводит к тому, что libbox передаёт маршруты, DNS и фильтры приложений в `TunOptions`, а `NothingVpnService.openTun` применяет их к `VpnService.Builder`. Фильтрация по приложениям задаётся не в JSON, а через `OverrideOptions.includePackage/excludePackage` при `startOrReloadService`.

### outbounds
- `proxy-node` — `vless`: `server`, `server_port`, `uuid`, опционально `flow`; блоки `tls` и `transport` по профилю.
- `proxy` — `selector` с единственным `proxy-node` (нужен, чтобы `urlTest("proxy")` из `LibboxProxyProbe` тестировал настоящий исходящий канал).
- `direct`, `block`.

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
| `routingMode`, `allowedPackageNames` | `OverrideOptions` (`includePackage` / `excludePackage`) |
| `failClosed` | `VpnSessionController` (ретраи), не ядро |
| DoH/локальные DNS | JSON + `LocalResolver` (локальный резолвер через `DnsResolver` Android) |

## Что учесть при изменениях
- Любое поле, которое добавляется в `TunnelPolicy`, нужно: сериализовать (значение по умолчанию!), валидировать в `validate()`, учесть в компиляторе/`OverrideOptions`, покрыть тестом в `SingBoxConfigCompilerTest`/`TunnelPolicyValidationTest`. Политика хранится как JSON и читается с `ignoreUnknownKeys`, поэтому старые сохранения должны оставаться совместимы.
- Спецоутбаунды `block` (и `dns`) в sing-box ≥1.11 помечены как deprecated в пользу route-actions; перед обновлением ядра проверить, что генерируемый конфиг не использует удалённые поля (см. [tech-debt.md](tech-debt.md)).
- Секреты попадают в JSON → файл существует только между `writeSessionConfig` и чтением сервисом (удаляется до запуска ядра).
- Отклонения от документации sing-box требуют обоснования (правило проекта). Документация: https://sing-box.sagernet.org/configuration/.
