# Nothing Mobile VPN

Android-first мобильный VPN-клиент с архитектурным заделом под iOS.

## Что уже подготовлено

- Базовая Gradle/KMP структура: `androidApp` + `shared`.
- Shared-слой с первичными моделями и портами.
- Android: встраивание **libbox** (`androidApp/libs/libbox.aar`) и `VpnService` по образцу SFA (`CommandServer` + `PlatformInterface`).
- Архитектурное правило Cursor в `.cursor/rules/`.

## Структура проекта

- `androidApp` — Android приложение и platform-адаптеры (`VpnService`, runtime, permissions).
- `shared` — Kotlin Multiplatform shared-логика (`domain` + `application`).
- `androidApp/libs/libbox.aar` — нативная обёртка sing-box (libbox), не коммитится без артефакта.

## Что сделать вручную (обязательно)

1. Установить Android Studio (последняя стабильная), Android SDK и JDK 17.
2. Собрать или получить **`libbox.aar`** (тот же модуль, что в sing-box-for-android) и положить в **`androidApp/libs/libbox.aar`** (см. сообщение задачи `checkLibboxAar` при сборке).
3. Открыть проект в Android Studio и выполнить Gradle Sync.

## Следующие инженерные шаги

1. Перенести policy-модели и валидации из desktop в `shared/domain`.
2. Добавить generator `sing-box` config в `shared/infrastructure` (или отдельный модуль).
3. Реализовать lifecycle use-cases: connect/disconnect/reconnect.
4. Добавить smoke-check: `sing-box version`, запуск, DNS, корректная остановка.

## Ограничения

- Полноценная iOS сборка и подпись требуют macOS.
- Секреты и ключи хранить только в защищённом хранилище (Android Keystore).
