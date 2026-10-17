# Глоссарий

## Термины

| Термин | Значение |
|---|---|
| sing-box | Универсальная прокси-платформа; в проекте — ядро туннеля |
| libbox | gomobile-обёртка sing-box (`io.nekohasekai.libbox`), поставляется как `libbox.aar` |
| SFA | sing-box-for-android — эталонная реализация, по образцу которой сделан мост (`CommandServer` + `PlatformInterface`) |
| CommandServer / CommandClient | API libbox: сервер управляет ядром, клиент получает статус, группы, умеет `urlTest` |
| PlatformInterface | Колбэки платформы для ядра: `openTun`, сети, DNS, сертификаты, Wi-Fi |
| TunOptions / OverrideOptions | Параметры TUN от ядра / переопределения от приложения (include/exclude пакеты) |
| Policy (`TunnelPolicy`) | Декларативные настройки туннеля, из которых строится JSON |
| Smoke-check | Проверка после старта: `urlTest("proxy")` + HTTPS 204 + running-флаг |
| Split tunneling / per-app | Маршрутизация по приложениям (`PER_APP_ALLOWED` / `PER_APP_EXCLUDED`) |
| Fail-closed (в проекте) | Одна попытка подключения без ретраев; не системный Kill Switch |
| Kill Switch | Системный «Always-on VPN» + «Block connections without VPN» — включает пользователь |
| Reality | Режим TLS-маскировки (VLESS Reality): `pbk` (public key), `sid` (short id) |
| Graph | `NothingVpnGraph` — ручной DI-контейнер |
| Session config | Одноразовый JSON-файл `sing-box-session-*.json` с конфигом ядра |
| `TunnelStatus` | Фактическое состояние туннеля (`Stopped/Starting/Running/Stopping`) в `TunnelStatusRepository`; единственный источник истины |

## Ошибки (`VpnError`) и их тексты (`UserErrorText`)

Все ошибки, которые видит пользователь, — `enum class VpnError` в `shared/domain/VpnError.kt`. Бросаются как `VpnException(error)` (хелперы `ensure { }` / `vpnFail()`); `message` исключения равен стабильному `code` — он же пишется в журнал.

`Throwable.toVpnError()` — единственная точка классификации: `VpnException` (в т.ч. в цепочке `cause`) → точный код в `message` → код внутри `message` (ошибки из `openTun`, вернувшиеся через ядро) → эвристика по тексту ядра (`tls/certificate/reality`, `dns/resolve`, `timeout/connect`, `tun`) → `Unknown`.

`UserErrorText.from(context, VpnError | Throwable)` — исчерпывающий `when` по `VpnError` → `strings.xml`. Новый код без текста не скомпилируется.

| Группа | Коды | Источник |
|---|---|---|
| Профили | `no_active_profile`, `profile_not_found`, `no_reachable_profile`, `profile_secret_unavailable`, `profile_id_immutable`, `profile_delete_requires_disconnect`, `profile_name_required`, `profile_host_required`, `profile_port_invalid`, `profile_uuid_invalid`, `profile_reality_incomplete` | controller, compiler, ViewModel |
| Импорт ссылки | `link_not_vless`, `link_invalid`, `link_invalid_uuid`, `link_invalid_host_or_port`, `link_unsupported`, `link_reality_incomplete` | `VlessLinkParser` |
| Политика | `per_app_requires_packages`, `invalid_package_name`, `too_many_packages`, `invalid_mtu`, `invalid_tun_ipv4_cidr`, `invalid_tun_stack`, `invalid_singbox_log_level`, `invalid_doh_server`, `invalid_doh_path`, `invalid_doh_sni_for_ip`, `invalid_local_dns_domain`, `too_many_local_dns_domains` | `TunnelPolicy.validate`, controller |
| Туннель | `vpn_permission_missing`, `vpn_connect_timeout`, `vpn_start_failed`, `vpn_start_superseded`, `vpn_stop_failed`, `vpn_stop_timeout`, `vpn_revoked`, `vpn_not_running_after_connect`, `tun_establish_failed`, `empty_config`, `wifi_state_needs_fine_location` | сервис, `AndroidSystemVpn`, controller |
| Проверка | `smoke_proxy_failed`, `smoke_timeout` | `AndroidSystemVpn.smokeCheck` |
| Ядро (по тексту) | `core_tls_failed`, `core_dns_failed`, `core_server_unreachable` | `toVpnError` |
| Прочее | `unknown` | → `user_error_generic` |
