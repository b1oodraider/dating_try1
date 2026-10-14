# dating — Сервис комфортных знакомств (MVP)

Модульный монолит для закрытого MVP сервиса знакомств с фокусом на согласование темпа и формата общения.

---

## 1. Архитектура и стек

- **Backend**: Java 21 LTS, Spring Boot 3.3.4, Gradle, Spring Security, Spring Data JPA, Liquibase, PostgreSQL 16.
- **Web**: React 19 + TypeScript + Tailwind CSS (Vite).
- **Mobile**: Flutter / Dart (единый клиент для Android и iOS).
- **Инфраструктура**: Docker Compose, PostgreSQL в изолированной внутренней сети, без избыточных сервисов (Kafka, Redis, Kubernetes исключены по архитектурным ограничениям).

---

## 2. Структура репозитория

```text
dating/
├── backend/                  # Java 21 + Spring Boot (модульный монолит)
│   ├── build.gradle          # Сборка и зависимости
│   ├── settings.gradle
│   ├── Dockerfile            # Multi-stage Dockerfile с ограничением памяти
│   └── src/
│       ├── main/java/com/dating/
│       │   ├── DatingApplication.java
│       │   ├── common/       # errors, security, time
│       │   ├── auth/         # OTP challenges, сессии, rate limits
│       │   ├── accounts/     # состояние аккаунта, удаление данных
│       │   ├── profiles/     # анкеты, карточки общения, 24ч статус
│       │   ├── discovery/    # поиск, фильтры по карточкам (AND/OR), пагинация
│       │   ├── media/        # загрузка и валидация фото (MIME, max 5MB)
│       │   └── productevents/# асинхронные продуктовые события без PII
│       ├── main/resources/
│       │   ├── application.yml
│       │   ├── application-dev.yml
│       │   └── db/changelog/ # Liquibase миграции и сиды
│       └── test/             # Модульные и интеграционные тесты
├── src/                      # Web-клиент (React + TypeScript)
│   ├── core/                 # типы, api-клиент, AuthContext
│   ├── features/             # auth, profiles, photos, discovery, tags, account
│   └── shared/               # общие UI-компоненты
├── infra/
│   └── compose.yaml          # Запуск backend + PostgreSQL
└── docs/                     # Проектная документация и ТЗ
```

---

## 3. Быстрый запуск

### 3.1. Запуск всей инфраструктуры (Backend + PostgreSQL) через Docker Compose

```bash
docker compose -f infra/compose.yaml up -d --build
```

- Backend API: `http://localhost:8080`
- Healthcheck: `http://localhost:8080/actuator/health`
- Swagger/OpenAPI: `http://localhost:8080/api/v1/...`
- PostgreSQL изолирована внутри `internal_net` (порт не публикуется наружу).

### 3.2. Локальный запуск бэкенда (Java 21)

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'
```

*В профиле `dev` включён Mock OTP:*
Для любого номера телефона код подтверждения: `123456`. В `prod`-профиле mock OTP строго отключён.

### 3.3. Запуск тестов бэкенда

```bash
cd backend
./gradlew test
```

### 3.4. Запуск веб-клиента (React + TypeScript)

```bash
npm install
npm run dev
```

---

## 4. Базовые REST API контракты (`/api/v1`)

- **Auth**:
  - `POST /api/v1/auth/otp/request` — запрос OTP (маскированный ответ).
  - `POST /api/v1/auth/otp/verify` — подтверждение кода и выдача сессии.
  - `POST /api/v1/auth/logout` — отзыв текущей сессии.
- **Account & Profile**:
  - `GET /api/v1/me` — данные текущего аккаунта.
  - `DELETE /api/v1/me` — запрос каскадного удаления аккаунта.
  - `GET /api/v1/me/profile` — собственная анкета.
  - `PUT /api/v1/me/profile` — редактирование анкеты и карточек.
  - `POST /api/v1/me/profile/publish` — публикация анкеты.
  - `POST /api/v1/me/profile/hide` — скрытие анкеты.
  - `GET /api/v1/tags` — справочник функциональных карточек общения.
- **Photos**:
  - `POST /api/v1/me/profile/photos` — multipart загрузка (JPEG/PNG/WebP до 5 МБ).
  - `GET /api/v1/me/profile/photos` — список фотографий.
  - `PATCH /api/v1/me/profile/photos/{id}` — назначение основного фото.
  - `DELETE /api/v1/me/profile/photos/{id}` — удаление фото.
  - `GET /api/v1/profiles/{profileId}/photos/{photoId}` — авторизованная выдача фото.
- **Discovery**:
  - `GET /api/v1/discovery/profiles` — выдача только опубликованных анкет с фильтрами по карточкам (`tagLogic=AND|OR`), городу и возрасту.
