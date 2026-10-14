# Экономика: премиум, расходники, косметика

Фундамент для премиума, достижений, гачи и покупок. Сами достижения и гача ещё не реализованы:
здесь описано, как их подключать, не трогая остальной код.

## Модель

| Понятие | Где | Суть |
|---|---|---|
| Каталог предметов | `catalog_items` | `code` + `kind`: `CONSUMABLE` (копится и тратится: суперлайки, бусты, билеты гачи), `COSMETIC` и `STICKER_PACK` (есть / нет) |
| Кошелёк | `wallet_balances` | баланс предмета у аккаунта, никогда не меньше 0 |
| Журнал | `economy_transactions` + `economy_ledger_lines` | каждая выдача/трата с источником (`RewardSource`) и ключом идемпотентности, уникальным в рамках аккаунта |
| Премиум | `premium_entitlements` | «премиум до»; выдаётся днями из любого источника, продлевается от `max(сейчас, текущий конец)` |
| Премиум-функции | `app.economy.premium-features` | коды функций, закрытых премиумом; проверка `FeatureAccess.require(accountId, "CODE")` → 403 `PREMIUM_REQUIRED` |
| Премиум-расходники | `app.economy.premium-allowances` | «N штук за период»: `TOP_UP` (пополнить до N) или `ADD` (добавить N); период UTC: день/ISO-неделя/месяц |

Премиум **не меняет лимиты** (фото, карточки, слова-исключения) — только открывает функции и выдаёт расходники.

## Единственная точка изменений

```java
economyService.grant(accountId, RewardSource.ACHIEVEMENT, "first_photo", "achievement:first_photo",
        List.of(new Reward.Item("SUPERLIKE", 3), new Reward.Item("FRAME_GOLD", 1), new Reward.PremiumDays(3)));

economyService.spend(accountId, "SUPERLIKE", 1, likeId, "superlike:" + likeId); // 409 INSUFFICIENT_BALANCE
```

- **Идемпотентность:** повтор с тем же ключом ничего не делает (`grant` вернёт `false`). Ключ строится из
  естественного идентификатора события: `achievement:<code>`, `gacha:<pullId>`, `subscription:<id>:<period>`,
  `purchase:<orderId>`.
- **Атомарность:** баланс меняется одним SQL-оператором; параллельные траты не уводят его в минус.
- Дубликат косметики записывается в журнал как строка с `quantity_delta = 0` — гача сможет конвертировать дубли.

## Как подключать будущие модули

**Достижения** (`com.dating.achievements`):
1. Таблицы `achievement_definitions` (код, условие, награды) и `account_achievements` (что получено, прогресс).
2. Слушать доменные события через `@TransactionalEventListener(phase = AFTER_COMMIT)`:
   `AccountCreatedEvent`, `ProfilePublishedEvent`, `ProfilePhotoAddedEvent` (новые события добавляются
   по тому же образцу: record в модуле-источнике + `ApplicationEventPublisher.publishEvent`).
3. При выполнении условия — `economyService.grant(..., RewardSource.ACHIEVEMENT, code, "achievement:" + code, rewards)`.

**Гача** (`com.dating.gacha`):
1. Таблицы баннеров, дроп-таблиц с весами и `gacha_pulls` (аудит: seed/результат, pity-счётчики).
2. Оплата круток — `spend` билетов (`CONSUMABLE`), выдача — `grant(..., RewardSource.GACHA, pullId, "gacha:" + pullId, rewards)`
   в той же транзакции, что и запись крутки.
3. Показывать шансы выпадения в клиенте (требование App Store / Google Play).

**Подписка и покупки** (будущий `com.dating.billing`): платёж подтверждается вебхуком провайдера,
после чего `grant(..., RewardSource.SUBSCRIPTION, subscriptionId, "subscription:" + id + ":" + period,
List.of(new Reward.PremiumDays(30)))`. Денежные записи (чеки, возвраты) хранятся отдельно от игровой экономики.

## Новые предметы и функции

- Предмет — строка в `catalog_items` (миграция). Названия/иконки пока на клиентах по `code`.
- Премиум-функция — код в `app.economy.premium-features` + вызов `featureAccess.require(...)` в сервисе функции.
- Недельные суперлайки для премиума:

```yaml
app:
  economy:
    premium-allowances:
      - item-code: SUPERLIKE
        quantity: 5
        period: WEEKLY
        mode: TOP_UP
```

## API

`GET /api/v1/me/wallet` — премиум, список премиум-функций и предметы (см. `docs/api-contract.md`).
Открытие кошелька заодно выдаёт положенные премиум-расходники; ежедневная задача делает то же для всех премиум-аккаунтов.

## Удаление аккаунта

Кошелёк, журнал и премиум удаляются вместе с аккаунтом (`AccountDeletedEvent`).
