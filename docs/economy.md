# Экономика: премиум, расходники, косметика, тестовые аккаунты

Фундамент для премиума, достижений, гачи и покупок. Гача и автоматические триггеры достижений ещё не
реализованы: здесь описано, как их подключать, не трогая остальной код.

## Модель

| Понятие | Где | Суть |
|---|---|---|
| Каталог | `catalog_items` | `code`, `kind` (`CONSUMABLE` / `COSMETIC`), `category` (SUPERLIKE, GACHA_TICKET, STICKER_PACK, PHOTO_FRAME, …), `equip_slot`, `title`, `rarity`, `attributes` (JSON для отрисовки) |
| Кошелёк | `wallet_balances` | баланс по `(аккаунт, предмет, карман)`, никогда не меньше 0 |
| Карманы расходников | `bucket` | `PERMANENT` — куплено/заработано/выиграно, не сгорает; `PERIODIC` — из премиума, тратится первым |
| Журнал | `economy_transactions` + `economy_ledger_lines` | каждая выдача/трата с источником и ключом идемпотентности |
| Премиум | `premium_entitlements` | «премиум до»; дни из любого источника продлевают от `max(сейчас, текущий конец)` |
| Премиум-функции | `app.economy.premium-features` | коды функций; `FeatureAccess.require(accountId, "CODE")` → 403 `PREMIUM_REQUIRED` |
| Премиум-расходники | `app.economy.premium-allowances` | «N за период» в карман PERIODIC: `TOP_UP` (пополнить до N) или `ADD` (+N) |
| Косметика | `equipped_cosmetics` | надетый предмет на слот: `PHOTO_FRAME`, `PROFILE_STATUS` (видны другим), `CHAT_WALLPAPER` (приватно) |
| Достижения | `achievement_definitions`, `account_achievements` | определения с наградами (JSON) и факт выполнения |
| Часы экономики | `account_clock_offsets` | сдвиг времени только для тестовых аккаунтов |

Премиум **не меняет лимиты** (фото, карточки, слова-исключения) — только открывает функции и выдаёт расходники.

## Расходники: как устроены и почему так

- **Два кармана.** Если хранить всё в одном счётчике, «пополнить до 5» съедает купленные суперлайки
  (купил 10 → пополнять нечего). Поэтому премиум кладёт в `PERIODIC`, всё остальное — в `PERMANENT`.
- **Трата:** сначала `PERIODIC` (их всё равно пополнят), потом `PERMANENT`. Купленное не сгорает.
- **`TOP_UP`** — для «использованных за неделю» вещей (суперлайки, бусты): неиспользованное не копится,
  в начале периода снова N. **`ADD`** — для валют/билетов (билеты гачи): копится.
- **Стабы сейчас** (`application.yml`, пока премиум нельзя купить): `SUPERLIKE` 5/неделя TOP_UP,
  `GACHA_TICKET` 1/неделя ADD, `BOOST` 1/неделя TOP_UP (механика буста — [boost.md](boost.md)). Заменить, когда будет решён состав премиума.

Рекомендации на будущее:

| Расходник | Источники | Режим в премиуме | Замечание |
|---|---|---|---|
| Суперлайк | премиум, достижения, покупка | TOP_UP | главный «вау»-расходник; трата при отправке, возврат не нужен |
| Буст анкеты | премиум (1/нед), покупка | TOP_UP | действие на время (`attributes.durationMinutes`), нужен отдельный учёт «активен до» |
| Билет гачи | достижения, премиум, покупка | ADD | бесплатные и платные билеты лучше разделить кодами (`GACHA_TICKET` / `GACHA_TICKET_PAID`): у сторов и законов разные требования к платному рандому |
| «Вернуть анкету» | премиум | TOP_UP | дешёвый, но приятный |
| Мягкая валюта (если появится) | достижения, дубли из гачи | — | обычный `CONSUMABLE`; удобно для конвертации дублей косметики |

Не стоит делать расходником то, что логичнее как функция премиума («кто меня лайкнул», расширенные фильтры) —
для этого есть `premium-features`.

## Единственная точка изменений

```java
economyService.grant(accountId, RewardSource.ACHIEVEMENT, "first_photo", "achievement:first_photo",
        List.of(new Reward.Item("SUPERLIKE", 3), new Reward.Item("FRAME_STUB_GOLD", 1), new Reward.PremiumDays(3)));

economyService.spend(accountId, "SUPERLIKE", 1, likeId, "superlike:" + likeId); // 409 INSUFFICIENT_BALANCE
```

- Повтор с тем же ключом ничего не делает. Ключ — из естественного идентификатора события:
  `achievement:<code>`, `gacha:<pullId>`, `subscription:<id>:<period>`, `purchase:<orderId>`.
- Баланс меняется атомарно (upsert / блокировка строк); параллельные траты не уводят в минус.
- Дубликат косметики пишется в журнал с `quantity_delta = 0` — гача сможет конвертировать дубли.

## Косметика

- Новый предмет существующего слота — только строка в `catalog_items` (миграция), клиенты рисуют по `attributes`:
  - `PHOTO_FRAME`: `borderColor`, `borderWidth`, `glow`
  - `PROFILE_STATUS`: `emoji`, `label`, `color`
  - `CHAT_WALLPAPER`: `background`, `accent`, `pattern`
  - `STICKER_PACK`: `emoji`, `stickers[]` (коды стикеров; ассеты появятся вместе с чатом)
- Новый **вид** надеваемой косметики — константа в `CosmeticSlot` (+ видна ли другим) и отрисовка в клиентах.
- Надеть можно только то, что есть; видимые слоты отдаются в `decorations` анкеты в ленте и в `/me/profile`.
- Сейчас в каталоге **заглушки** (`*_STUB_*`, `attributes.stub = true`) — для проверки витрины и экипировки.

## Достижения

Определения — данные: `rewards` = `[{"item": "SUPERLIKE", "quantity": 1}, {"premiumDays": 3}]`.
`AchievementService.complete(accountId, code)` отмечает выполнение и выдаёт награды один раз.
Триггеры добавляются слушателями доменных событий (`@TransactionalEventListener(phase = AFTER_COMMIT)`):
`AccountCreatedEvent`, `ProfilePublishedEvent`, `ProfilePhotoAddedEvent`. Заглушки: `STUB_FIRST_PHOTO`,
`STUB_PROFILE_PUBLISHED`, `STUB_COLLECTOR`.

## Тестовые аккаунты

- Номера из `app.testing.test-phones` (env `APP_TESTING_TEST_PHONES`, через запятую) становятся тестовыми
  при входе: `Account.testAccount = true`. Они **изолированы**: тестовые видят в ленте только тестовых,
  реальные — только реальных. Можно включать на проде для закрытого тестирования.
- `app.testing.enabled=true` (env `APP_TESTING_ENABLED`) включает `/api/v1/testing/*` и фиксированный код
  `app.testing.otp-code` для этих номеров (SMS не отправляется). Без флага эндпоинтов нет (404).
- В dev: номера `+7 900 000-00-01..03`, код `123456`.

| Что нужно | Запрос |
|---|---|
| Состояние (кошелёк, достижения, время) | `GET /api/v1/testing/state` |
| Отметить достижение | `POST /api/v1/testing/achievements/{code}/complete` |
| Сбросить достижение | `DELETE /api/v1/testing/achievements/{code}` |
| Пополнить расходники / выдать косметику | `POST /api/v1/testing/wallet/items/{code}` `{"quantity": 5}` |
| Установить купленный баланс | `PUT /api/v1/testing/wallet/items/{code}` `{"quantity": 0}` |
| Дать премиум | `POST /api/v1/testing/premium` `{"days": 7}` |
| Убрать премиум | `DELETE /api/v1/testing/premium` |
| «Следующая неделя премиума» | `POST /api/v1/testing/clock/advance` `{"days": 7}` |
| Вернуть реальное время | `DELETE /api/v1/testing/clock` |

Сдвиг времени действует только на экономику этого аккаунта (истечение премиума, периоды выдачи
расходников), остальная система живёт в реальном времени.

## Подключение будущих модулей

- **Гача** — реализована, см. [docs/gacha.md](gacha.md): `spend` билета (`gacha:spend:<requestId>`) +
  `grant(..., GACHA, requestId, "gacha:" + requestId, rewards)` в одной транзакции; дубли → `GACHA_SHARD`.
- **Подписка/покупки** (будущий `billing`): после подтверждённого вебхука —
  `grant(..., SUBSCRIPTION, id, "subscription:" + id + ":" + period, List.of(new Reward.PremiumDays(30)))`.
  Денежные записи хранятся отдельно от игровой экономики.

## Удаление аккаунта

Кошелёк, журнал, премиум, надетая косметика, достижения и сдвиг часов удаляются вместе с аккаунтом.
