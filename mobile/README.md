# Dating MVP — мобильный клиент (Flutter)

Минимальный клиент для API из `docs/api-contract.md`: вход по номеру телефона (OTP) и лента анкет.

## Структура

```
lib/
  main.dart                     — точка входа: восстанавливает сессию и запускает приложение
  app.dart                      — MaterialApp + AuthGate (вход ↔ лента)
  core/api/                     — HTTP-клиент, конфигурация адреса API, ApiException
  core/models/                  — модели по контракту (Tag, Photo, PublicProfile, DiscoveryPage, ExclusionWords, auth)
  core/storage/token_storage.dart — токен в flutter_secure_storage
  features/auth/                — экран телефона, экран кода, SessionController
  features/discovery/           — лента анкет (pull-to-refresh, догрузка страниц)
  features/exclusion_words/     — экран «Слова-исключения» и клиентская валидация слов
test/                           — unit-тесты клиента и парсинга моделей
```

## Первый запуск

Папки платформ (`android/`, `ios/`) в репозиторий **не коммитятся**. Сгенерируйте их один раз внутри `mobile/`:

```bash
cd mobile
flutter create --platforms=android,ios .
flutter pub get
```

Если `flutter create` создал `test/widget_test.dart` (он ссылается на несуществующий `MyApp`) — удалите его.

## Адрес API

Адрес задаётся при сборке через `--dart-define`:

```bash
flutter run --dart-define=API_BASE_URL=http://<host>:8080/api/v1
```

Без параметра используется:

- Android-эмулятор: `http://10.0.2.2:8080/api/v1` (так эмулятор видит localhost компьютера);
- iOS-симулятор и прочее: `http://localhost:8080/api/v1`.

Для реального устройства укажите IP компьютера в локальной сети, например `http://192.168.1.10:8080/api/v1`.

Ссылки на фото приходят относительными (`/api/v1/media/photos/...?exp=...&sig=...`) и дополняются origin'ом API. Подписанные ссылки живут ~1 час — для свежих потяните ленту вниз (pull-to-refresh).

## HTTP без TLS при локальной разработке

**Android** (9+) по умолчанию запрещает незашифрованный HTTP. Для отладки разрешите его только в debug-манифесте `android/app/src/debug/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET"/>
    <application android:usesCleartextTraffic="true" />
</manifest>
```

Для release используйте HTTPS и не забудьте `<uses-permission android:name="android.permission.INTERNET"/>` в `android/app/src/main/AndroidManifest.xml` (шаблон Flutter добавляет его только в debug/profile).

**iOS**: ATS пропускает `localhost`, но для HTTP по IP в локальной сети нужен исключающий ключ в `ios/Runner/Info.plist` (только для разработки) либо HTTPS.

## Вход в режиме разработки

Если бэкенд запущен с mock-отправкой OTP, ответ на запрос кода содержит `devMockCode` — экран ввода кода покажет его подсказкой (по нажатию код подставится). В продакшене поле не приходит и подсказки нет.

## Слова-исключения

Кнопка «Слова-исключения» (значок «запрет» в шапке ленты) открывает личный список до 20 слов (`GET`/`PUT /me/exclusion-words`). Анкеты, где в «О себе» или в действующем статусе дня есть любое из слов в любой форме («курю» скрывает и «курить»), в ленту не попадают. Список виден только владельцу. Каждое слово — 2–30 букв или цифр без пробелов; повторы без учёта регистра отбрасываются. Сохранение заменяет список целиком, после чего лента перезагружается с первой страницы.

## Проверки

```bash
flutter analyze
flutter test
```
