# Данные и хранилища

## Карта хранилищ

| Хранилище | Тип | Что хранит | Класс |
|---|---|---|---|
| `noBackupFilesDir/vault/state.bin` | зашифрованный файл (AES-256-GCM, ключ в Android Keystore) | JSON `StoredVault`: профили **вместе с секретами**, `activeProfileId`, `TunnelPolicy`, служебные флаги миграции | `VaultFile`, `VaultStore`, `ProfileRepositoryAndroid` |
| `nothing_vpn_user_settings` | SharedPreferences (открытые) | флаги автоматизации, `trusted_wifi`, `routing_rule_sets` (JSON), `connection_requested` | `AppPreferences` |
| `nothing_vpn_diagnostics` | SharedPreferences | журнал событий (≤80 строк) | `ConnectionEventLog` |
| `noBackupFilesDir/sing-box-session-*.json(.tmp)` | файлы | JSON-конфиг сессии с секретами (живёт секунды) | `AndroidSessionConfigWriter` |
| `filesDir/runtime`, `cacheDir` | каталоги libbox | рабочие данные и temp ядра | `LibboxAndroidBootstrap` |

Автобэкап отключён (`allowBackup=false`, пустые правила); хранилище к тому же лежит в `noBackupFilesDir`.

Старые хранилища (только для миграции, удаляются после неё): EncryptedSharedPreferences `nothing_vpn_profiles` (список профилей без секретов, активный профиль, политика) и `nothing_vpn_secure` (`profile_secret:<id>` → `ProfileSecrets`), ещё раньше — незашифрованный DataStore `files/datastore/nothing_mobile_vpn.preferences_pb`.

## Зашифрованное хранилище (`platform/storage/`)

- **`KeystoreVaultCipher`** — AES-256-GCM, ключ `nothing_vpn_vault_v1` в Android Keystore (не покидает Keystore), случайный IV на каждую запись. Формат файла: `[версия 1 байт][IV 12 байт][шифротекст + тег 128 бит]`. Своя реализация на `javax.crypto` вместо устаревшего `security-crypto`.
- **`VaultFile`** — атомарная запись: шифрование → временный файл → `fsync` → `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`. При обрыве на диске остаётся либо старая, либо новая версия целиком. Нерасшифровываемый файл не удаляется, а переименовывается в `state.bin.corrupt-<время>`.
- **`StoredVault`** — содержимое (`schemaVersion = 1`). Новое поле — только со значением по умолчанию; смена смысла — поднять версию и описать миграцию в `VaultBootstrap`.
- **`VaultStore`** — состояние в памяти (`StateFlow`) + запись. Чтения ничего не пишут. Изменение (`update`) под мьютексом сначала записывается на диск и только потом становится видимым; ошибка записи пробрасывается вызывающему. Все чтения ждут `awaitReady()` — завершения загрузки при старте процесса.
- **`ProfileRepositoryAndroid`** — `ProfileRepositoryPort` поверх `VaultStore`; `observeProfiles` — поток из состояния в памяти.

Секреты профиля (`uuid`, `realityPublicKey`, `realityShortId`) хранятся внутри зашифрованного документа вместе с профилем — отдельного хранилища секретов больше нет. Профиль с пустым `uuid` считается недоступным: подключение падает с `profile_secret_unavailable`.

## Загрузка и миграция (`VaultBootstrap`)

Запускается в `NothingVpnApp.onCreate` в фоне; итог (кроме обычной загрузки) пишется в журнал событий как `Storage: <outcome>`.

| Ситуация | Действие | Итог |
|---|---|---|
| Хранилище есть и читается | загрузить | `loaded` |
| … и `legacyCleanupPending` | удалить старые EncryptedSharedPreferences/DataStore, снять флаг | `loaded_and_legacy_deleted` |
| … и `legacyMigrationIncomplete` | ещё раз прочитать старые данные и **добавить** недостающие профили (по id), не трогая новые | `merged_late_legacy` |
| Хранилища нет, есть старые данные | перенести (`AndroidLegacySource` + `LegacyDecoder`, секреты через `ProfileSecretIntegrity`), записать, проверить обратным чтением; старые данные **не удалять**, поставить `legacyCleanupPending` | `migrated_from_legacy` |
| Старые данные есть, но не читаются | стартовать пустыми с `legacyMigrationIncomplete` — при следующих запусках повторить и подмешать | `legacy_unreadable` |
| Запись при миграции не удалась | частичный файл в карантин, в памяти — перенесённые данные с `legacyMigrationIncomplete` | `write_failed` |
| Файл хранилища не расшифровывается | карантин файла, далее как без хранилища | `vault_unreadable_quarantined` / `fresh` |
| Ничего нет | пустое хранилище | `fresh` |
| Неожиданное исключение | хранилище **только для чтения** (пусто, изменения отклоняются `storage_read_only`) — чтобы не затереть данные на диске | `Storage: load failed, read-only` |

Двухфазность: старые данные удаляются только при *следующем* запуске, после того как новое хранилище успешно расшифровалось. Сбой между записью и проверкой или ключ, который не читается после перезапуска, не приводят к потере данных. Логика покрыта `VaultTest` (фейковый шифр и источник старых данных).

`security-crypto` и `datastore-preferences` остаются в зависимостях только ради чтения старых данных в `AndroidLegacySource`; их можно удалить, когда старых установок не останется.

## Session-файлы

- Имя: `sing-box-session-<millis>.json`, запись через `.tmp` + `renameTo` (fallback — прямая запись).
- Очистка (`SessionConfigFileLifecycle.cleanup`): сортировка по `lastModified`; пропуск `keepPath` и `keepPath + ".tmp"` (по canonical path); удаляются файлы старше `maxAgeMs` или сверх `maxRetainedFiles=2`. `cleanupStartupStaleOnly` удаляет только устаревшие (>5 мин).
- Вызовы: конструктор writer и `writeSessionConfig`, `NothingVpnService.onCreate`, `cleanupPreConnect(keepPath)` перед стартом ядра, `teardown`.
- Сервис читает файл и сразу удаляет его, поэтому при крахе процесса секреты на диске не остаются. На время сессии конфиг хранится только в памяти сервиса (для `serviceReload`).

## Политика туннеля

Один `TunnelPolicy` на приложение (не на профиль), часть `StoredVault`. При отсутствии — `TunnelPolicy()` по умолчанию. Наборы маршрутизации (`RoutingRuleSet`) хранятся отдельно в `AppPreferences` и не являются частью политики.
