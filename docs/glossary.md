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
| Internal broadcast | Локальные `VPN_CONNECT_RESULT` и `VPN_RUNNING_STATE` с signature-permission |

## Коды ошибок и их тексты (`UserErrorText`)

Контроллер/сервис передают ошибки кодами; `UserErrorText.from(context, raw)` переводит их в строку (`strings.xml`), иначе — эвристика по подстрокам и общий текст `user_error_generic`.

| Код | Источник | Смысл |
|---|---|---|
| `no_active_profile`, `profile_not_found` | controller | не выбран/не найден профиль |
| `profile_secret_unavailable` | controller, compiler, formatter | нет UUID/ключей Reality в защищённом хранилище |
| `invalid_mtu`, `invalid_tun_ipv4_cidr`, `invalid_tun_stack`, `invalid_singbox_log_level` | `TunnelPolicy.validate` | сетевые параметры |
| `per_app_requires_packages`, `invalid_package_name`, `too_many_packages` | policy/controller | split tunneling |
| `invalid_doh_server`, `invalid_doh_path`, `invalid_doh_sni_for_ip` | policy | DoH |
| `invalid_local_dns_domain`, `too_many_local_dns_domains` | policy | локальные DNS-домены |
| `vpn_connect_timeout`, `vpn_stop_timeout` | `AndroidSystemVpn` | таймауты старта/остановки |
| `vpn_not_running_after_connect` | smoke/controller | туннель не поднят |
| `smoke_proxy_failed`, `smoke_timeout`, `smoke_dns_failed`, `smoke_tcp_failed` | smoke/health | проверки доступности (`dns`/`tcp` — зарезервированы в тексте, код сейчас формирует только `proxy`/`timeout`) |
| `wifi_state_needs_fine_location` | сервис | ядру нужен доступ к SSID |
| `reality_public_key_missing`, `reality_short_id_missing` | compiler | неполный Reality-профиль |
| `profile_delete_requires_disconnect`, `profile_name_required`, `profile_host_required`, `profile_port_invalid`, `profile_uuid_invalid`, `profile_reality_incomplete`, `profile_id_immutable` | controller | CRUD профиля |
| `empty_config`, `connectVpn_failed`, `vpn_start_failed`, `connect_failed`, `disconnect_failed`, `profile_update_failed`, `profile_delete_failed` | сервис/controller | общие; без отдельного текста → generic |
| `"Only vless:// links are supported."`, `"Invalid UUID format."`, `"Missing host."`, `"Invalid port."` | `VlessLinkParser` | ошибки импорта (английские тексты используются как ключи) |
