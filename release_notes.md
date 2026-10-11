## Как деплоить

Приложение разворачивается через `docker compose` (app + Postgres). Раздела «деплой» в README нет, схема описана в [docs/architecture.md §13](docs/architecture.md#L726).

1. Скопируйте `.env.example` в `.env` и заполните его. Файл в `.gitignore` и в `.dockerignore`, в образ он не попадает.
2. Запустите `docker compose up -d --build`. Профиль `prod` включён в compose (`SPRING_PROFILES_ACTIVE: prod`).
3. Приложение слушает порт `8081` на хосте (`8081:8080`) и работает по http. Для публичного доступа нужен reverse proxy с TLS (nginx или Caddy). Прокси должен дописывать `X-Forwarded-For` и ставить `X-Forwarded-Proto`. Таймаут запроса у него нужен большой: конвертация фото синхронная.
4. Postgres наружу не публикуется. Данные лежат в томах `postgres-data` и `uploads`.

## Обязательные переменные

Compose падает при старте, если любая из них пуста. В профиле `prod` у этих переменных **нет fallback-ов**.

| Переменная                                                 | Что это                                                          |
| ---------------------------------------------------------- | ---------------------------------------------------------------- |
| `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`                    | Postgres. Эти же значения использует и контейнер `db`            |
| `BEACON_ENCRYPTION_KEY`                                    | AES-256-ключ, base64 ровно от 32 байт: `openssl rand -base64 32` |
| `BEACON_EMAIL_HASH_PEPPER`                                 | Секрет для HMAC-поиска по email: `openssl rand -base64 32`       |
| `ADMIN_EMAIL`                                              | Email единственного админа, на него приходит OTP                 |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` | SMTP                                                             |

Три замечания:

- **Ключ и pepper нельзя менять после старта.** Потеряете ключ — зашифрованные данные не прочитать. Смените pepper — перестанет работать поиск по email.
- **`DB_PASSWORD` читается только при первой инициализации тома Postgres.** Если том уже создан с дефолтным паролем, смена в `.env` ничего не даст. Нужно сделать `ALTER USER` внутри БД или пересоздать том.
- **Адрес «From» берётся из `MAIL_USERNAME`** ([SmtpOtpMailer.java:20](src/main/java/com/iitm/beacon/config/SmtpOtpMailer.java#L20)). Логин должен быть полноценным email-адресом. Логин вида `apikey` (SendGrid) не подойдёт, и без правки кода отдельный From не задать.

## Почта: возможная проблема

В конфиге нет ни `starttls`, ни `ssl` (проверил grep по `src/main/resources`). Если SMTP-сервер требует STARTTLS (Gmail и большинство провайдеров на 587) или SSL (465), отправка, скорее всего, упадёт. Я это не запускал, вывод сделан по конфигу.

Лечится переменными окружения Spring:

- `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true` для порта 587.
- `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE=true` для порта 465.

Compose пробрасывает в контейнер только перечисленные переменные, так что эти строки надо добавить в секцию `environment` сервиса `app`.

Если SMTP не работает, OTP не уйдёт, и никто не сможет ни войти как админ, ни отправить отзыв. Проверьте отправку сразу после деплоя.

## Опциональные переменные (с дефолтами)

**Compose-ом пробрасываются:**

- `BEACON_ALLOWED_ORIGINS` (по умолчанию пусто). За прокси обязательно задайте, например `https://beacon.example`. Иначе same-origin проверка раскрытия контактов будет отклонять все запросы.
- `BEACON_TRUSTED_PROXIES` (пусто). Точные IP прокси. Без него все посетители будут делить один IP, и лимиты OTP по IP сработают сразу на всех.
- `BEACON_COOKIE_SECURE` (`true` в prod). Ставьте `false` только если заходите по голому http с другой машины.
- `BEACON_RETENTION_CRON` (`0 0 3 * * SUN`, `-` отключает). Это очистка отклонённых отзывов.

**В контейнер не пробрасываются.** Они есть в `.env.example`, но в compose-деплое не подействуют, пока их не добавят в `environment` (это BL-023 в бэклоге). Дефолты берутся из `application.yml`:

| Переменная                                                         | Дефолт          |
| ------------------------------------------------------------------ | --------------- |
| `BEACON_SESSION_TIMEOUT`                                           | `24h`           |
| `BEACON_UPLOADS_ROOT`                                              | `/data/uploads` |
| `BEACON_PHOTO_MAX_COUNT`                                           | `50`            |
| `BEACON_PHOTO_MAX_PER_SECTION`                                     | `5`             |
| `BEACON_PHOTO_MAX_SIZE_BYTES`                                      | `20971520`      |
| `BEACON_PHOTO_MAX_PIXELS`                                          | `250000000`     |
| `BEACON_PHOTO_FULL_MAX_EDGE`                                       | `2560`          |
| `BEACON_PHOTO_THUMB_MAX_EDGE`                                      | `640`           |
| `BEACON_PHOTO_WEBP_QUALITY`                                        | `82`            |
| `BEACON_PHOTO_WEBP_THUMB_QUALITY`                                  | `75`            |
| `BEACON_PHOTO_BACKFILL_ENABLED`                                    | `true`          |
| `BEACON_UPLOAD_MAX_FILE_SIZE`                                      | `25MB`          |
| `BEACON_UPLOAD_MAX_REQUEST_SIZE`                                   | `1010MB`        |
| `BEACON_UPLOAD_MAX_PART_COUNT`                                     | `500`           |
| `BEACON_NON_UPLOAD_MULTIPART_MAX_SIZE`                             | `16KB`          |
| `ADMIN_OTP_TTL`, `VISITOR_OTP_TTL`                                 | `PT5M`          |
| `*_OTP_MAX_ATTEMPTS`                                               | `5`             |
| `*_OTP_REQUEST_LIMIT_PER_EMAIL` / `*_OTP_REQUEST_WINDOW_PER_EMAIL` | `1` / `PT1M`    |
| `*_OTP_REQUEST_LIMIT_PER_IP` / `*_OTP_REQUEST_WINDOW_PER_IP`       | `5` / `PT1M`    |
| `DB_PORT`                                                          | `5432`          |

Для `*_OTP_*` есть два набора: с префиксом `ADMIN_` и с префиксом `VISITOR_`.

## Что подставляется по умолчанию (только профиль `dev`)

Эти fallback-и есть только в [application-dev.yml](src/main/resources/application-dev.yml), в `prod` их нет:

- `MAIL_HOST=localhost`, `MAIL_PORT=1025`, `MAIL_USERNAME=dev`, `MAIL_PASSWORD=dev`. При этом в dev OTP только пишется в лог, письма не уходят.
- `BEACON_ENCRYPTION_KEY=aunkIRNayPv…` — ключ зашит в репозиторий.
- `BEACON_EMAIL_HASH_PEPPER=dev-only-insecure-pepper-do-not-use-in-prod`.
- `ADMIN_EMAIL=admin@example.com`.
- БД: H2-файл `./data/beacon-dev`, пользователь `sa` с пустым паролем. Консоль H2 включена.
- Хранилище фото: `./data/uploads-dev`.

Для `prod` риск небольшой, потому что там нет fallback-ов и приложение не стартует без переменных. Реальная опасность — запустить без `SPRING_PROFILES_ACTIVE=prod`, например `java -jar` вручную. Тогда включится профиль по умолчанию без пароля на БД и с известным ключом. В Docker-варианте этого не случится, профиль задан в compose.

## Перед выходом в прод

- В вашем текущем `.env` лежат демо-значения: `local-dev-password`, `local-demo-pepper`, `admin@example.com`, `MAIL_HOST=beacon-mailhog`. Для сервера создайте отдельный `.env` со своими секретами.
- Добавьте в compose STARTTLS/SSL для почты (см. выше).
- Решите, пробрасывать ли остальные переменные (BL-023).
- Настройте бэкап томов `postgres-data` и `uploads`, а также сохраните ключ шифрования отдельно от сервера.

Могу добавить в compose проброс STARTTLS и недостающих переменных, но по правилам проекта сначала спрошу: сделать сейчас или завести записи в `BACKLOG.md`?
