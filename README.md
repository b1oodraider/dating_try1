# dating — Сервис комфортных знакомств (MVP)

Модульный монолит для закрытого MVP сервиса знакомств с фокусом на согласование темпа и формата общения.

---

## 1. Архитектура и стек

- **Backend** (источник истины): Java 21, Spring Boot 4.1, Gradle 9, Spring Security 7, Jackson 3, Spring Data JPA, Liquibase, PostgreSQL 16.
- **Web**: React 19 + TypeScript + Tailwind CSS (Vite). В разработке ходит в backend через прокси Vite.
- **Dev-мок** (`server.ts`): in-memory сервер на Express с тем же контрактом API — только для разработки фронта без Java/PostgreSQL. В production не запускается.
- **Mobile**: Flutter / Dart (Android и iOS), см. [mobile/README.md](mobile/README.md).
- **Инфраструктура**: Docker Compose, PostgreSQL во внутренней сети. Kafka, Redis, Kubernetes не используются по архитектурным ограничениям.

Контракт API: [docs/api-contract.md](docs/api-contract.md) (кратко) и [docs/openapi.yaml](docs/openapi.yaml) (полностью).

---

## 2. Структура репозитория

```text
dating/
├── backend/                  # Java 21 + Spring Boot (модульный монолит)
│   └── src/main/java/com/dating/
│       ├── common/           # ошибки, безопасность, trace id, rate limiter
│       ├── auth/             # OTP, сессии, лимиты запросов кода
│       ├── accounts/         # аккаунт и его удаление (событие AccountDeletedEvent)
│       ├── profiles/         # анкеты, карточки общения, статус на 24 часа
│       ├── discovery/        # лента: фильтры по карточкам (AND/OR), городу, возрасту
│       ├── media/            # фото: проверка формата, очистка EXIF, подписанные ссылки
│       └── productevents/    # продуктовые события без PII
├── src/                      # Web-клиент (React + TypeScript)
├── server.ts                 # dev-мок API (npm run dev:mock)
├── mobile/                   # Flutter-клиент
├── infra/compose.yaml        # backend + PostgreSQL
└── docs/                     # контракт API
```

---

## 3. Запуск

### 3.1. Backend + PostgreSQL через Docker Compose

```bash
cp infra/.env.example infra/.env   # заполнить DB_PASSWORD, APP_MEDIA_URL_SIGNING_SECRET, APP_CORS_ALLOWED_ORIGINS
docker compose -f infra/compose.yaml --env-file infra/.env up -d --build
```

- API: `http://localhost:8080/api/v1`, healthcheck: `http://localhost:8080/actuator/health`.
- PostgreSQL доступна только во внутренней сети compose.
- Без обязательных переменных compose не стартует — дефолтных паролей нет.

> **Отправка SMS не подключена.** В профиле `prod` запрос кода возвращает `503 OTP_DELIVERY_UNAVAILABLE`,
> пока не добавлен бин `OtpSender` с реальным SMS-провайдером (`backend/.../auth/application/OtpSender.java`).

### 3.2. Backend локально (профиль dev)

Нужна PostgreSQL на `localhost:5432` (база `dating`, пользователь `dating_user`, пароль `dating_dev_password`
или свои значения в `SPRING_DATASOURCE_*`).

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'
```

Профиль по умолчанию не задан: без `dev`/`prod` приложение не запустится (нет datasource).
В `dev` включён mock OTP: код `123456`, он же возвращается в ответе как `devMockCode`.
Mock OTP вместе с профилем `prod` запрещён — приложение не стартует.

### 3.3. Тесты backend

```bash
cd backend
./gradlew test
```

Интеграционные тесты поднимают PostgreSQL через Testcontainers (нужен запущенный Docker); без Docker они пропускаются.

### 3.4. Web-клиент

```bash
npm install
npm run dev        # http://localhost:3000, /api проксируется на VITE_API_TARGET (по умолчанию http://localhost:8080)
npm run dev:mock   # то же, но с in-memory мок-сервером вместо Java backend
npm run lint       # проверка типов
npm run build
```

---

## 4. REST API (`/api/v1`)

| Метод | Путь | Описание |
|---|---|---|
| POST | `/auth/otp/request` | Запрос кода (лимиты: 1 раз в 60 с и 5 раз в час на номер, 30 в час на IP) |
| POST | `/auth/otp/verify` | `{challengeId, code}` → сессия. Номер берётся из challenge, не от клиента |
| POST | `/auth/logout` | Отзыв текущей сессии |
| GET / DELETE | `/me` | Аккаунт / полное удаление данных |
| GET / PUT | `/me/profile` | Своя анкета / частичное обновление |
| POST | `/me/profile/publish`, `/me/profile/hide` | Публикация (нужны имя, возраст, город, фото) / скрытие |
| GET / POST | `/me/profile/photos` | Список / загрузка (multipart `file`, JPEG/PNG/WebP до 5 МБ, до 6 шт.) |
| PATCH / DELETE | `/me/profile/photos/{id}` | Сделать главным / удалить |
| GET | `/media/photos/{id}?exp&sig` | Изображение по подписанной ссылке (без заголовка авторизации) |
| GET | `/discovery/profiles` | Лента (только для авторизованных) |
| GET / PUT | `/me/exclusion-words` | Приватные слова-исключения: анкеты с этими словами в «о себе» или статусе дня (в любой словоформе) скрываются из ленты |
| GET | `/tags` | Справочник карточек общения |

---

## 5. Безопасность и приватность

- Телефон хранится только как SHA-256 от нормализованного номера и в маскированном виде.
- Токены сессий и коды хранятся только в виде хэшей; неудачные попытки ввода кода сохраняются (не более 5 на код).
- При удалении аккаунта удаляются анкета, фото (включая файлы) и сессии, а аккаунт анонимизируется.
- Из фото удаляются EXIF (включая GPS), XMP и текстовые метаданные; формат определяется по содержимому файла.
- Фото выдаются по короткоживущим HMAC-ссылкам, только владельцу и авторизованным пользователям ленты.
- Публичная лента не отдаёт `accountId` и доступна только после входа.
- CORS — явный список origin'ов (`APP_CORS_ALLOWED_ORIGINS`).
- За reverse proxy лимит по IP считает адрес прокси: настройте `server.forward-headers-strategy` только для доверенного прокси.
