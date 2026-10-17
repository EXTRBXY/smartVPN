# Данные и хранилища

## Карта хранилищ

| Хранилище | Тип | Что хранит | Класс |
|---|---|---|---|
| `nothing_vpn_profiles` | EncryptedSharedPreferences | `profiles_json` (список профилей **без** секретов), `active_profile_id`, `tunnel_policy_json`, маркер `legacy_migration_retry_needed` | `ProfileRepositoryAndroid`, `VpnEncryption` |
| `nothing_vpn_secure` | EncryptedSharedPreferences | `profile_secret:<profileId>` → JSON `ProfileSecrets{profileId, uuid, realityPublicKey, realityShortId}` | `SecureStoreAndroid` |
| `nothing_vpn_user_settings` | SharedPreferences (открытые) | флаги автоматизации, `trusted_wifi`, `routing_rule_sets` (JSON), `connection_requested` | `AppPreferences` |
| `nothing_vpn_running_flag` | SharedPreferences | `running`, `started_at` | `VpnRunningState` |
| `nothing_vpn_diagnostics` | SharedPreferences | журнал событий (≤80 строк) | `ConnectionEventLog` |
| `noBackupFilesDir/sing-box-session-*.json(.tmp)` | файлы | JSON-конфиг сессии с секретами (живёт секунды) | `AndroidSessionConfigWriter` |
| `filesDir/runtime`, `cacheDir` | каталоги libbox | рабочие данные и temp ядра | `LibboxAndroidBootstrap` |

Шифрование: `MasterKey` AES256_GCM (Android Keystore), ключи prefs — AES256_SIV, значения — AES256_GCM. Зависимость `androidx.security:security-crypto:1.1.0-alpha06`.
Автобэкап отключён (`allowBackup=false`, пустые правила).

## Модель секретов профиля

Секретные поля `VlessProfile`: `uuid`, `realityPublicKey`, `realityShortId`.

- `upsert(profile)`: секреты сохраняются в `SecureStore`, в списке профилей — `stripSecrets` (`uuid=""`, reality=null). Вызывающий авторитетен: ранее сохранённый секрет **не** подмешивается (иначе правка UUID была бы no-op).
- `listProfiles()`: для каждого профиля `restoreSecrets` читает секреты и прогоняет `ProfileSecretIntegrity.resolve`:
  - в secure-store лежит нечитаемое значение → считать секрет утраченным и удалить запись (`purgeSecureStore`);
  - запись есть, но `profileId` не совпал, `uuid` пуст или Reality-пара неполная → удалить;
  - записи нет, но секреты остались inline (старый формат) → мигрировать в secure-store;
  - иначе — использовать сохранённое.
  Если после этого сохранённый список отличается от «очищенного», он перезаписывается.
- Профиль с пустым `uuid` после чтения считается недоступным: подключение падает с `profile_secret_unavailable`.
- `delete(id)` удаляет профиль, сбрасывает `active_profile_id` (если он совпадал) и запись секрета.

`observeProfiles()` — `callbackFlow` по `OnSharedPreferenceChangeListener` на ключ списка профилей, с отменой предыдущей эмиссии.

## Миграция (`ProfileStorageMigration`)

Однократно при старте `Application`: если существует старый незашифрованный DataStore `files/datastore/nothing_mobile_vpn.preferences_pb`, ключи `profiles_json`, `active_profile_id`, `tunnel_policy_json` переносятся в зашифрованные prefs (`commit()`), затем файлы DataStore (`-shm/-wal/-journal`) удаляются (3 попытки). Маркер retry защищает от обрыва посередине. Любой доступ репозитория ждёт `awaitProfileStorageReady()` (`CompletableDeferred`, завершается и при ошибке миграции).

## Session-файлы

- Имя: `sing-box-session-<millis>.json`, запись через `.tmp` + `renameTo` (fallback — прямая запись).
- Очистка (`SessionConfigFileLifecycle.cleanup`): сортировка по `lastModified`; пропуск `keepPath` и `keepPath + ".tmp"` (по canonical path); удаляются файлы старше `maxAgeMs` или сверх `maxRetainedFiles=2`. `cleanupStartupStaleOnly` удаляет только устаревшие (>5 мин).
- Вызовы: конструктор writer и `writeSessionConfig`, `NothingVpnService.onCreate`, `cleanupPreConnect(keepPath)` перед стартом ядра, `teardown`.
- Сервис читает файл и сразу удаляет его, поэтому при крахе процесса секреты на диске не остаются.

## Политика туннеля

Один `TunnelPolicy` на приложение (не на профиль), JSON в `tunnel_policy_json`. Читается с `ignoreUnknownKeys`, пишется с `encodeDefaults`. При отсутствии значения возвращается `TunnelPolicy()` по умолчанию. Наборы маршрутизации (`RoutingRuleSet`) хранятся отдельно в `AppPreferences` и не являются частью политики.
