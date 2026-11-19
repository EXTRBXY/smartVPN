# Релизы и обновление приложения

Версия продукта — `versionName` и `versionCode` в `androidApp/build.gradle.kts`. Сейчас **1.0.0** (`versionCode` 4). Клиент сравнивает только тройку чисел: `v1.2` и `1.2.0` — одна версия.

## Как пользователь получает обновление

Пока процесс приложения жив (его держит VPN-сервис), проверка идёт при старте и затем каждые 30 минут. Сам запрос к GitHub — не чаще раза в 23,5 часа. Кнопка в настройках проверяет сразу.

Запрос: `GET https://api.github.com/repos/EXTRBXY/smartVPN/releases/latest`. Берётся ассет `NothingMobileVpn.apk`. Скачивание идёт мимо своего VPN, по https, и только с `github.com`, `objects.githubusercontent.com`, `release-assets.githubusercontent.com`. Файл — zip (APK). Установку подтверждает система: нужно разрешение «из этого источника». Отказ «Позже» запоминает тег релиза и больше его не предлагает.

Первая установка подписанного релиза поверх debug-сборки не встанет: подписи разные. Её ставят заново. Следующие версии с тем же release-ключом обновляются поверх.

## Как выпустить версию

1. Поднять `versionName` и `versionCode` (код только растёт).
2. Закоммитить это в `main`.
3. Поставить тег `v` + `versionName`, например `v1.0.0`, на этот коммит и отправить тег.
4. Workflow `.github/workflows/release.yml` ставит Android SDK (platform 37.0), собирает `assembleRelease`, проверяет, что тег совпал с `versionName`, и публикует ассеты `NothingMobileVpn.apk` и `NothingMobileVpn.apk.sha256`.

Секреты репозитория:

| Secret | Что внутри |
|---|---|
| `RELEASE_STORE_BASE64` | `base64` от файла keystore, одна строка |
| `RELEASE_STORE_PASSWORD` | пароль хранилища. Им же открывается ключ: `keytool` без `-storetype` создаёт PKCS12, отдельный пароль ключа там не хранится |
| `RELEASE_KEY_ALIAS` | alias ключа |
| `RELEASE_KEY_PASSWORD` | тот же пароль, что у хранилища. Сборка подписывает паролем хранилища |

Без них workflow останавливается и не публикует APK, подписанный debug-ключом. `localRelease` по-прежнему только для установки себе.
