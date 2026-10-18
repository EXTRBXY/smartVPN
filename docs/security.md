# Безопасность

Требования взяты из `.cursor/rules/nothing-mobile-vpn-singbox-architecture.mdc` и подтверждены кодом. Раздел — чек-лист при ревью изменений.

## Активы
UUID профиля и параметры Reality (`pbk`, `sid`); адрес сервера; список приложений пользователя (метаданные о привычках); трафик пользователя.

## Реализованные меры

| Область | Мера | Где |
|---|---|---|
| Секреты в покое | Единый зашифрованный файл в `noBackupFilesDir` (AES-256-GCM, ключ в Android Keystore, новый IV на запись), атомарная запись | `platform/storage/` |
| Секреты в конфиге | Файл сессии в `noBackupFilesDir`, удаляется сервисом **до** старта ядра; чистка устаревших файлов | `AndroidSessionConfigWriter`, `NothingVpnService.connectSuspend` |
| Логи | Маскирование UUID, `vless://…@`, `pbk`, `sid`, путей session-файлов; в release исключения логируются без сообщения; события пользовательского журнала режутся до 240 символов и без переводов строк | `LogRedaction`, `SafeAndroidLog`, `ConnectionEventLog` |
| Экспорт | Ссылка с UUID отдаётся только после диалога-предупреждения; диагностический отчёт не содержит секретов | `HomeRoute`, `DiagnosticsRoute` |
| Скриншоты | `FLAG_SECURE` в не-debug сборках | `MainActivity.onCreate` |
| Состояние туннеля | Передаётся внутри процесса через `StateFlow` (`TunnelStatusRepository`); внутренних broadcast и собственного permission нет — нечего подделать извне | `TunnelStatusRepository`, `NothingVpnService` |
| Экспортируемые компоненты | `VpnService` не экспортирован; экспортированы только Activity, boot-ресивер и tile (обязательно) | манифест |
| Уведомления ядра | Открытие URL только `https` и не localhost/`.local`/private/link-local/CGNAT/loopback | `NothingVpnService.isSafeNotificationUrl` |
| Сетевой трафик приложения | Cleartext запрещён | `network_security_config.xml` |
| Резервное копирование | Отключено | `allowBackup=false`, пустые rules |
| Release | `isDebuggable=false`, R8; подпись release-ключом из env (`RELEASE_STORE_FILE/PASSWORD`, `RELEASE_KEY_ALIAS/PASSWORD`); без них release не подписан. `localRelease` — подпись debug-ключом, только для локальной установки | `androidApp/build.gradle.kts` |
| Рабочие каталоги ядра | Внутреннее хранилище (`filesDir/runtime`) | `LibboxAndroidBootstrap` |
| Защита сокетов ядра | `protect(fd)` через `autoDetectInterfaceControl` — трафик ядра не заворачивается обратно в туннель | `NothingVpnService` |

## Ограничения и риски (известные)

- **Утечки вне туннеля**: Kill Switch системный и включается пользователем; `singleConnectAttempt` (бывш. `failClosed`) его не заменяет.
- **Внешний IP**: запрос `https://api.ipify.org` выполняется только по кнопке; smoke-check обращается к `gstatic.com`.
- **`QUERY_ALL_PACKAGES`**: допустим только при личном/внутреннем распространении (Google Play потребует обоснования).
- **`ACCESS_FINE_LOCATION`**: нужен для SSID Wi-Fi и `NeedWIFIState`; без него сессия с правилами по Wi-Fi падает (`wifi_state_needs_fine_location`).
- **`AppPreferences`** (имена доверенных Wi-Fi, наборы приложений, флаги) с версии 0.3.0 лежат в зашифрованном хранилище вместе с профилями.
- `security-crypto` (alpha, deprecated) больше не используется для хранения — только для чтения старых данных при миграции; удалить вместе с `AndroidLegacySource`, когда старых установок не останется.
- `lastError` — `VpnError` с фиксированным текстом, данных профиля в нём нет. Журнал событий проходит редакцию регулярками — это best-effort, а не гарантия; новые сообщения не должны включать данные профиля.
- Парсер ссылок принимает произвольные `extraQuery`; они сохраняются в профиле и воспроизводятся при экспорте, но в JSON ядра не попадают.

## Чек-лист ревью изменений

1. Новый код не пишет `uuid`/`pbk`/`sid`/полные ссылки в `Log`, `Toast`, `Intent`, `SharedPreferences` (открытые), исключения.
2. Предпочитай `StateFlow`/граф внутри процесса. Если нужен внутренний broadcast — `setPackage`, signature-permission и `RECEIVER_NOT_EXPORTED`.
3. Новые файлы с конфигом — в `noBackupFilesDir`, учтены в `SessionConfigFileLifecycle` (имя `sing-box-session-*`) либо удаляются сразу.
4. Любое «открыть URL» из внешних данных проходит строгую проверку.
5. Новая настройка политики проходит `TunnelPolicy.validate()`.
6. Release-сборка не использует debug-ключ.
7. Нет `ProcessBuilder`/запуска бинарника `sing-box`.
