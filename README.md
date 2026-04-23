# Nothing Mobile VPN

Android-first мобильный VPN-клиент с архитектурным заделом под iOS.

## Что уже подготовлено

- Базовая Gradle/KMP структура: `androidApp` + `shared`.
- Shared-слой с первичными моделями и портами.
- Android runtime-заготовка `SingBoxBinaryInstaller` для установки бинарника из assets.
- Архитектурное правило Cursor в `.cursor/rules/`.

## Структура проекта

- `androidApp` — Android приложение и platform-адаптеры (`VpnService`, runtime, permissions).
- `shared` — Kotlin Multiplatform shared-логика (`domain` + `application`).
- `androidApp/src/main/assets/singbox/arm64-v8a/` — место для бинарника `sing-box`.
- `sing-box-1.13.9-android-arm64/` — ваш исходный скачанный архив (рабочая папка).

## Что сделать вручную (обязательно)

1. Установить Android Studio (последняя стабильная), Android SDK и JDK 17.
2. Сгенерировать Gradle Wrapper в корне проекта:
   - `gradle wrapper --gradle-version 8.9`
3. Скопировать бинарник:
   - из `sing-box-1.13.9-android-arm64/sing-box`
   - в `androidApp/src/main/assets/singbox/arm64-v8a/sing-box`
4. Открыть проект в Android Studio и выполнить Gradle Sync.
5. Добавить launcher Activity и `VpnService` implementation.

## Следующие инженерные шаги

1. Перенести policy-модели и валидации из desktop в `shared/domain`.
2. Добавить generator `sing-box` config в `shared/infrastructure` (или отдельный модуль).
3. Реализовать lifecycle use-cases: connect/disconnect/reconnect.
4. Добавить smoke-check: `sing-box version`, запуск, DNS, корректная остановка.

## Ограничения

- Полноценная iOS сборка и подпись требуют macOS.
- Секреты и ключи хранить только в защищённом хранилище (Android Keystore).
