# Dating API contract v1 (source of truth = Java backend)

Base path: /api/v1. JSON, camelCase. Timestamps ISO-8601 UTC strings.
Auth: header `Authorization: Bearer <token>`.

## Errors
Every error body: { code: string, message: string (Russian, user-facing), traceId: string, fieldErrors?: { [field]: message }, details?: object }
Response header `X-Trace-Id` on every response.
Status codes: 400 validation/business, 401 UNAUTHORIZED (missing/invalid/expired token, or account not ACTIVE),
403 FORBIDDEN, 404 *_NOT_FOUND, 405, 413/400 FILE_TOO_LARGE, 415 UNSUPPORTED_MEDIA_TYPE, 429 RATE_LIMITED (+ `Retry-After` seconds header), 503 OTP_DELIVERY_UNAVAILABLE, 500 INTERNAL_ERROR.
Economy errors (no endpoint returns them yet; clients should already handle them generically):
  403 PREMIUM_REQUIRED     details: { feature }              → show paywall for that feature
  409 INSUFFICIENT_BALANCE details: { itemCode, available }  → show "not enough super likes" etc.

## Types
Tag        = { code, category: 'pace'|'format'|'calls'|'boundaries'|'social', categoryLabel, label, description, sortOrder }
Photo      = { id, url, mimeType, sizeBytes, sortOrder, isPrimary, createdAt }
             url is a RELATIVE signed path, e.g. "/api/v1/media/photos/{id}?exp=1760000000&sig=ab12..". Valid ~1h.
             It needs NO Authorization header (usable directly in <img src> / Image.network after prefixing API origin).
             Refetch the profile/feed to get fresh URLs.
MyProfile  = { id, displayName: string|null, age: number|null, city: string|null, aboutText: string|null,
               publicationStatus: 'DRAFT'|'PUBLISHED'|'HIDDEN',
               dailyStatusText: string|null, dailyStatusExpiresAt: string|null,   // already null when expired
               tags: Tag[], photos: Photo[] (sorted by sortOrder),
               completenessPercent: number (0..100),
               missingForPublish: string[]   // subset of 'displayName','age','city','photos'; empty => can publish
               decorations: Decorations,     // how others see my profile
               createdAt, updatedAt }
PublicProfile = { id, displayName, age, city, aboutText: string|null, dailyStatusText: string|null, tags: Tag[], photos: Photo[],
                  decorations: Decorations }
             (no accountId, no statuses)
Decorations = { [slot: 'PHOTO_FRAME'|'PROFILE_STATUS'|...]: Decoration }   // only public slots; may be {}
Decoration  = { code, title, rarity: 'COMMON'|'RARE'|'EPIC'|'LEGENDARY'|null, attributes: object }
             Render generically from attributes, unknown keys/slots must be ignored:
               PHOTO_FRAME:    { borderColor: '#RRGGBB', borderWidth: number, glow?: boolean }
               PROFILE_STATUS: { emoji: string, label: string, color: '#RRGGBB' }
Account    = { id, phoneMasked, status: 'ACTIVE'|'SUSPENDED'|'DELETED', createdAt, testAccount: boolean }

## Auth
POST /auth/otp/request   body { phone: string }   (any human format: "+7 (999) 123-45-67", "89991234567" … server normalizes)
  200 { challengeId, expiresAt, maskedPhone, devMockCode?: string }   // devMockCode present ONLY when server mock mode is on
  400 INVALID_PHONE | VALIDATION_ERROR ; 429 RATE_LIMITED ; 503 OTP_DELIVERY_UNAVAILABLE
POST /auth/otp/verify    body { challengeId: uuid, code: "6 digits" }    (NO phone parameter anymore)
  200 { token, expiresAt, accountId }
  400 INVALID_CHALLENGE | CHALLENGE_ALREADY_USED | CHALLENGE_EXPIRED | MAX_ATTEMPTS_EXCEEDED | INVALID_CODE
POST /auth/logout        (auth)  204

## Account
GET    /me   (auth) 200 Account
DELETE /me   (auth) 204 — wipes profile, photos, sessions; token becomes invalid immediately.

## Tags
GET /tags  (public) 200 Tag[] sorted by sortOrder

## My profile (auth)
GET  /me/profile           200 MyProfile (created empty on first access: displayName/age/city null, DRAFT)
PUT  /me/profile           body (all optional; omitted = unchanged):
       { displayName?: string (2..50 after trim), age?: int 18..100, city?: string (1..60 after trim),
         aboutText?: string (<=1000, "" clears), tagCodes?: string[] (<=10, must be known active codes, else 400 UNKNOWN_TAG),
         dailyStatusText?: string (<=200; "" clears; sets 24h expiry) }
       200 MyProfile ; 400 VALIDATION_ERROR (fieldErrors) | UNKNOWN_TAG
POST /me/profile/publish   200 MyProfile ; 400 PROFILE_INCOMPLETE with fieldErrors keyed by missing fields
POST /me/profile/hide      200 MyProfile

## Photos (auth)
GET    /me/profile/photos               200 Photo[]
POST   /me/profile/photos               multipart/form-data, field name `file`; JPEG/PNG/WebP (checked by content, not header), <=5 MB, max 6 photos.
                                        Server strips EXIF/metadata. 200 Photo ; 400 PHOTO_LIMIT_EXCEEDED | FILE_TOO_LARGE ; 415 UNSUPPORTED_MEDIA_TYPE
PATCH  /me/profile/photos/{photoId}     no body — make primary. 204 ; 404 PHOTO_NOT_FOUND
DELETE /me/profile/photos/{photoId}     204 ; 404 PHOTO_NOT_FOUND
GET    /media/photos/{photoId}?exp&sig  (public, signature-checked) binary image with correct Content-Type; 404 if bad/expired sig or deleted.

## Discovery (auth REQUIRED)
GET /discovery/profiles?city=&minAge=&maxAge=&tagCodes=A,B&tagLogic=AND|OR&page=0&limit=20
  limit 1..50 (default 20), page >=0. Excludes caller's own profile. Only PUBLISHED profiles of ACTIVE accounts.
  Tag filter applied in DB before pagination (totals are exact).
  Caller's exclusion words (below) are applied automatically, also before pagination.
  200 { items: PublicProfile[], page, limit, totalElements, totalPages }

## Exclusion words (auth) — private hard filter for the caller's feed, never shown to anyone else
GET /me/exclusion-words      200 { words: string[], maxWords: 20 }
PUT /me/exclusion-words      body { words: string[] }  → 200 { words: string[], maxWords: 20 }
  Replaces the whole list. Server normalizes: trim, lowercase, drop duplicates, keep order.
  Each word: 2..30 chars, letters and digits only (one word, no spaces/punctuation). At most 20 words.
  Errors: 400 VALIDATION_ERROR with fieldErrors.words.
  Effect: discovery hides every profile whose aboutText or active (non-expired) dailyStatusText
  contains any of the words in any grammatical form (PostgreSQL full-text search, Russian stemming:
  "курю" also hides "курить", "курит"). displayName, city and tags are NOT matched.
  An empty list disables the filter.

## Wallet (auth) — premium status, consumables, cosmetics
GET /me/wallet  200 Wallet
Wallet = {
  premium: { active: boolean, until: string|null },
  premiumFeatures: string[],          // feature codes that require premium (from server config)
  items: WalletItem[],                // only quantity > 0; consumables first
  equipped: { [slot]: itemCode }      // equipped cosmetics, incl. private slots (CHAT_WALLPAPER)
}
WalletItem = { code, kind: 'CONSUMABLE'|'COSMETIC',
               category: 'SUPERLIKE'|'GACHA_TICKET'|'BOOST'|'STICKER_PACK'|'CHAT_WALLPAPER'|'PROFILE_STATUS'|'PHOTO_FRAME'|...,
               title, rarity: string|null, equipSlot: string|null, attributes: object,
               quantity: number,          // total (cosmetics: 1)
               periodicQuantity: number } // part of quantity from the premium allowance, spent first
  Opening the wallet also grants due premium allowances (e.g. weekly super likes), idempotently per period.
  Attributes by category (stubs today, attributes.stub = true):
    STICKER_PACK:   { emoji, stickers: string[] }
    CHAT_WALLPAPER: { background: '#RRGGBB', accent: '#RRGGBB', pattern: 'stars'|'waves'|... }
    PROFILE_STATUS / PHOTO_FRAME: see Decoration above
    consumables:    { icon: 'star'|'ticket'|'rocket'|... }

## Cosmetics (auth)
PUT    /me/cosmetics/equipped/{slot}   body { itemCode }   200 Wallet
       400 UNKNOWN_SLOT | WRONG_SLOT ; 403 ITEM_NOT_OWNED ; 404 ITEM_NOT_FOUND
DELETE /me/cosmetics/equipped/{slot}                        200 Wallet
  Slots: PHOTO_FRAME (public), PROFILE_STATUS (public), CHAT_WALLPAPER (private). Sticker packs are owned, not equipped.

## Achievements (auth)
GET /me/achievements  200 Achievement[]
Achievement = { code, title, description, completed: boolean, completedAt: string|null,
                rewards: [ { type: 'ITEM'|'PREMIUM_DAYS', itemCode?, quantity?, days? } ] }
  No automatic triggers yet; completions come from the testing API.

## Testing API (auth, test accounts only; exists only when the server runs with app.testing.enabled=true)
Test accounts = phones listed in server config; they log in with a fixed OTP code (no SMS), have Account.testAccount = true
and are isolated from real users in discovery (each side sees only its own kind). Non-test accounts get 403 TEST_ACCOUNT_REQUIRED.
Every endpoint returns TestingState = { accountId, clockOffsetDays: number, economyNow: string, wallet: Wallet, achievements: Achievement[] }
GET    /testing/state
POST   /testing/achievements/{code}/complete     mark completed + grant rewards (once)
DELETE /testing/achievements/{code}              reset completion (rewards already granted are kept); can complete again
POST   /testing/wallet/items/{itemCode}          body { quantity >= 1 }  add consumables / grant cosmetic
PUT    /testing/wallet/items/{itemCode}          body { quantity >= 0 }  set the bought (PERMANENT) balance exactly
POST   /testing/premium                          body { days 1..3650 }   add premium days
DELETE /testing/premium                          end premium now
POST   /testing/clock/advance                    body { days 1..3650 }   move this account's economy time forward
                                                 (7 = "next premium week": allowances refill, premium may expire)
DELETE /testing/clock                            back to real time

## Premium content (v1)
Premium-only features are listed in server config `app.economy.premium-features`; codes today:
  ADVANCED_FILTERS — discovery by height and goals + saved excluded goals
  EXCLUSION_WORDS  — saved exclusion words (PUT /me/exclusion-words now returns 403 PREMIUM_REQUIRED for free users)
Clients read the list from Wallet.premiumFeatures; a feature not listed there is free.
Premium badge: Wallet.premium.active, MyProfile.premium and PublicProfile.premium (shown to others in the feed).
When premium ends, saved hard filters are KEPT but NOT applied (responses carry `active: false`); editing them returns 403.

## Height and goals (profiles)
GET /goals (public) 200 Goal[]       Goal = { code, label, sortOrder }
  codes: SERIOUS_RELATIONSHIP, FAMILY, DATING, FRIENDSHIP, CHAT, NOT_SURE
MyProfile and PublicProfile additionally have: heightCm: number|null, goals: Goal[], premium: boolean
PUT /me/profile additionally accepts: heightCm (120..230), goalCodes: string[] (<= 3, known codes, else 400 UNKNOWN_GOAL; [] clears),
  clearHeight: true (removes the height; 400 fieldErrors.heightCm if sent together with heightCm)

## Discovery: advanced filters (premium ADVANCED_FILTERS, else 403 PREMIUM_REQUIRED details.feature)
GET /discovery/profiles additionally accepts: minHeight, maxHeight (cm; profiles without height do not match),
  goalCodes=A,B (profile has at least one of them)
Saved excluded goals (hard filter: profiles having any of these goals are hidden):
GET /me/excluded-goals  200 { goalCodes: string[], active: boolean }
PUT /me/excluded-goals  body { goalCodes: string[] }  200 same ; 403 PREMIUM_REQUIRED ; 400 UNKNOWN_GOAL
GET /me/exclusion-words now returns { words, maxWords, active }.

## Testing: seed profiles (part of the Testing API: test accounts only, app.testing.enabled=true)
Ready-made profiles for QA. Each one is a phone-less TEST account (cannot log in), so it is visible only to test
accounts in discovery. Flow: create (DRAFT) → upload photos one per request → publish.
SeedProfile = { accountId, profile: MyProfile }
GET    /testing/profiles                          200 SeedProfile[] (oldest first)
POST   /testing/profiles                          body { profile: <PUT /me/profile body>, premiumDays?: 0..3650,
                                                         cosmetics?: itemCode[] (<= 10; equippable ones are equipped) }
                                                  201 SeedProfile ; 400 VALIDATION_ERROR|UNKNOWN_TAG|UNKNOWN_GOAL ; 404 ITEM_NOT_FOUND
PUT    /testing/profiles/{accountId}              body <PUT /me/profile body>      200 SeedProfile
POST   /testing/profiles/{accountId}/photos       multipart `file` (same rules as /me/profile/photos) 200 Photo
POST   /testing/profiles/{accountId}/publish      200 SeedProfile ; 400 PROFILE_INCOMPLETE
DELETE /testing/profiles/{accountId}              204
DELETE /testing/profiles                          200 { deleted: number }   — all seed profiles
  Unknown/deleted ids and non-seed accounts: 404 SEED_PROFILE_NOT_FOUND.
  Bulk loading from a folder: scripts/seed-profiles.mjs (see seed/README.md).

## Gacha (auth) — design and decisions: docs/gacha.md
Pulls cost the banner's cost item (GACHA_TICKET by default, see Wallet). Results are rolled on the server (SecureRandom).
Rarity = 'COMMON'|'RARE'|'EPIC'|'LEGENDARY'
GachaReward = { type: 'ITEM'|'PREMIUM_DAYS',
                item: { code, kind, category, title, rarity, equipSlot, attributes } | null,   // render like WalletItem
                quantity: number|null, days: number|null }
PityRules   = { epicOrBetterWithin: number|null,   // EPIC or LEGENDARY guaranteed within N pulls (10: every 10-pull has one)
                legendaryWithin: number|null,      // LEGENDARY guaranteed at the M-th pull without one (80)
                softPityStartsAt: number|null, softPityLegendaryWeightStep: number }   // soft pity, off by default
PityProgress = { pityGroup, pullsSinceEpic, pullsSinceLegendary,
                 epicGuaranteedIn: number|null, legendaryGuaranteedIn: number|null,     // 1 = the next pull is guaranteed
                 totalPulls }
  Banners of one pityGroup share these counters (e.g. all limited banners). Counters reset when the rarity drops in any way.
PoolEntry   = { entryId, rarity, reward: GachaReward, weight, percent: number, featured: boolean }
Banner      = { code, title, description: string|null, type: 'PERMANENT'|'LIMITED',
                startsAt: string|null, endsAt: string|null,   // window [startsAt, endsAt)
                open: boolean,                                 // pulls possible now
                attributes: object,                            // rendering hints (accent, art); ignore unknown keys
                cost: { itemCode, perPull, multiPullSize, multiPullCost },
                pityRules: PityRules, myPity: PityProgress,
                rates: { totalWeight, rarities: [ { rarity, weight, percent } ] },  // all 4 rarities, base chance of one pull
                featured: PoolEntry[],                         // preview (featured entries, or the rarest ones)
                pool?: PoolEntry[] }                           // only in GET /gacha/banners/{code}
  Rates disclosure: percent has 4 decimals; rarities and pool each sum to exactly 100 (largest remainder rounding).
  Exact chance = weight / totalWeight. These are base chances; guarantees make EPIC/LEGENDARY more frequent.
PullResult  = { index, entryId, rarity, reward: GachaReward,
                isNew: boolean,                                // credited as is (new cosmetic, consumable, premium days)
                outcome: 'NEW'|'DUPLICATE_SHARDS',             // DUPLICATE_SHARDS: owned cosmetic → shards instead of the item
                shardsGranted: number,
                pityTrigger: 'EPIC_PITY'|'LEGENDARY_PITY'|'FORCED'|null }

GET  /gacha/banners           200 Banner[] (without pool) — banners open now, sorted by sortOrder
GET  /gacha/banners/{code}    200 Banner (with pool) ; 404 BANNER_NOT_FOUND (unknown, deactivated or not started yet;
                              ended banners are returned with open: false)
POST /gacha/banners/{code}/pull  body { count: 1 | multiPullSize, idempotencyKey: string (1..100, new per user action) }
  200 { requestId, bannerCode, count, replayed: boolean, results: PullResult[], shardsGranted,
        pity: PityProgress, costBalance: { itemCode, quantity }, shardBalance: { itemCode, quantity } }
  A retry with the same idempotencyKey returns the same results with replayed: true and spends nothing.
  404 BANNER_NOT_FOUND ; 409 BANNER_NOT_ACTIVE (outside the window / deactivated / empty pool) ;
  400 INVALID_PULL_COUNT | VALIDATION_ERROR ; 409 INSUFFICIENT_BALANCE details { itemCode, available } (nothing changes) ;
  409 IDEMPOTENCY_KEY_REUSED (same key, other banner or count)
GET  /gacha/history?limit=20&before=   newest first; limit 1..100 (else 400)
  200 { items: [ { id, requestId, bannerCode, bannerTitle, index, entryId, rarity, reward, isNew, outcome,
                   shardsGranted, pityTrigger, createdAt } ], nextBefore: number|null }   // pass nextBefore as before
GET  /gacha/shards            200 { balance: { itemCode: 'GACHA_SHARD', quantity }, ticketItemCode, shardsPerTicket: 30,
                                    maxTicketsPerExchange: 100, duplicateShards: { COMMON: 1, RARE: 5, EPIC: 20, LEGENDARY: 60 } }
POST /gacha/shards/exchange   body { tickets: 1..maxTicketsPerExchange, idempotencyKey }
  200 { applied: boolean (false = key already used, nothing changed), tickets, shardsSpent, shardBalance, ticketBalance }
  409 INSUFFICIENT_BALANCE details.itemCode = 'GACHA_SHARD' ; 400 VALIDATION_ERROR

## Gacha admin (auth + content editor role) — /admin/gacha
TEMPORARY: until roles exist, only test accounts pass and only when app.testing.enabled=true; others get 403 ROLE_REQUIRED.
AdminBanner = { code, title, description, type, startsAt, endsAt, active, costItemCode, costPerPull, multiPullSize,
                pityGroup, pityRules: PityRules, sortOrder, attributes, rates, pool: PoolEntry[], pulls: number }
GET    /admin/gacha/banners                     200 AdminBanner[] (all, incl. inactive)
GET    /admin/gacha/banners/{code}              200 AdminBanner ; 404 BANNER_NOT_FOUND
POST   /admin/gacha/banners                     201 AdminBanner ; 400 VALIDATION_ERROR ; 404 ITEM_NOT_FOUND ; 409 BANNER_ALREADY_EXISTS
  body { code: [A-Z0-9_]{2,50}, title, description?, type, startsAt?, endsAt? (both required for LIMITED), active? (true),
         costItemCode? (GACHA_TICKET, consumables only), costPerPull? (1), multiPullSize? (10), pityGroup? (= code),
         epicPity? (10; 0 = off), legendaryPity? (80; 0 = off), softPityStart? (off), softPityWeightStep? (0),
         sortOrder? (0), attributes? ({}) }
PUT    /admin/gacha/banners/{code}              same body (full replacement, code from the path) 200 AdminBanner
DELETE /admin/gacha/banners/{code}              204 ; 409 BANNER_HAS_PULLS (deactivate instead)
PUT    /admin/gacha/banners/{code}/pool/{entryId}   create or replace an entry, entryId [A-Za-z0-9_-]{1,50}
  body { rewardType: 'ITEM'|'PREMIUM_DAYS', itemCode?, quantity? (1; cosmetics must be 1), days? (1..3650),
         rarity? (required unless the cosmetic has a catalog rarity; must match it), weight: 1..1e9, featured?, sortOrder? }
  200 AdminBanner ; 400 VALIDATION_ERROR ; 404 ITEM_NOT_FOUND
DELETE /admin/gacha/banners/{code}/pool/{entryId}   200 AdminBanner ; 404 POOL_ENTRY_NOT_FOUND

## Testing: gacha (part of the Testing API: test accounts only, app.testing.enabled=true)
GachaTestingState = { forcedQueue: { [bannerCode]: entryId[] }, pity: { [pityGroup]: { pullsSinceEpic, pullsSinceLegendary, totalPulls } },
                      pulls: number }
GET    /testing/gacha/state
PUT    /testing/gacha/banners/{code}/forced   body { entryIds: string[] (<= 100) }  next pulls on the banner return these, in order
                                              (pityTrigger FORCED; counters advance by their rarity) ; 404 POOL_ENTRY_NOT_FOUND
DELETE /testing/gacha/forced                  clear all forced queues
PUT    /testing/gacha/pity/{pityGroup}        body { pullsSinceEpic, pullsSinceLegendary } (0..10000)
DELETE /testing/gacha/pity                    reset all counters
DELETE /testing/gacha/history                 delete pull history (wallet and counters are kept)
Tickets: POST /testing/wallet/items/GACHA_TICKET { quantity }. End of a limited banner: POST /testing/clock/advance { days }.
