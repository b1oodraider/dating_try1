# Dating API contract v1 (source of truth = Java backend)

Base path: /api/v1. JSON, camelCase. Timestamps ISO-8601 UTC strings.
Auth: header `Authorization: Bearer <token>`.

## Errors
Every error body: { code: string, message: string (Russian, user-facing), traceId: string, fieldErrors?: { [field]: message }, details?: object }
Response header `X-Trace-Id` on every response.
Status codes: 400 validation/business (VALIDATION_ERROR with fieldErrors; MALFORMED_REQUEST for unreadable JSON, a wrong enum
value, a missing query parameter or multipart part, a non-UUID path id), 401 UNAUTHORIZED (missing/invalid/expired token, or account not ACTIVE),
403 FORBIDDEN, 404 *_NOT_FOUND (NOT_FOUND for an unknown path), 405 METHOD_NOT_ALLOWED, 413 FILE_TOO_LARGE, 415 UNSUPPORTED_MEDIA_TYPE,
429 RATE_LIMITED (+ `Retry-After` seconds header), 503 OTP_DELIVERY_UNAVAILABLE, 500 INTERNAL_ERROR.
Errors with `details` (returned by the endpoints listed below):
  403 PREMIUM_REQUIRED     details: { feature }              → show paywall for that feature
                           (premium filters, PUT exclusion words / excluded goals, received likes list)
  409 INSUFFICIENT_BALANCE details: { itemCode, available }  → show "not enough super likes" etc.
                           (super like, gacha pull, shard exchange, boost)
  403 ROLE_REQUIRED        details: { roles: string[] }      → any one of these staff roles would grant access (/admin/**)
  403 ACCOUNT_RESTRICTED   details: { kind, until }          → a moderation restriction (LIKES on like/super like, MESSAGES on
                                                              sending); until = ISO time or null (until lifted). See GET /me/restrictions

## Types
Tag        = { code, category: string, categoryLabel, label, description, sortOrder, scalePosition: number|null (place on the
               category's scale, smaller = closer to its min end; null = the category is not a scale) }
             category = a tag category code (seeded: pace, format, calls, boundaries, social; editable via /admin/tag-categories)
Photo      = { id, url, mimeType, sizeBytes, sortOrder, isPrimary, createdAt, underReview: boolean }
             underReview: a verified person's new photo whose face did not match the reference photos; only the owner
             (and moderators) see it until a moderator decides. Other people's photo lists leave it out.
             url is a RELATIVE signed path, e.g. "/api/v1/media/photos/{id}?exp=1760000000&sig=ab12..". Valid ~1h.
             It needs NO Authorization header (usable directly in <img src> / Image.network after prefixing API origin).
             Refetch the profile/feed to get fresh URLs.
MyProfile  = { id, displayName: string|null, age: number|null (computed from birthDate), city: string|null, aboutText: string|null,
               gender: 'MALE'|'FEMALE'|null, seeking: 'MALE'|'FEMALE'|'ANY'|null, birthDate: 'yyyy-MM-dd'|null,
               ageFrom: number (default 18), ageTo: number|null (no upper limit),
               cityId: number|null (dictionary city; city = its official name), searchRadiusKm: number|null,
               locationShared: boolean,
               publicationStatus: 'DRAFT'|'PUBLISHED'|'HIDDEN',
               dailyStatusText: string|null, dailyStatusExpiresAt: string|null,   // already null when expired
               tags: Tag[], photos: Photo[] (sorted by sortOrder),
               completenessPercent: number (0..100) = sum of the weights of done tips,
               tips: [ { code, text, weight, done, requiredToPublish } ]   // not done first, most valuable first; weights add up
                     // to 100: NAME 10, AGE 10, CITY 10, FIRST_PHOTO 15 (these 4 are required to publish), MORE_PHOTOS 10 (3+),
                     // ABOUT 15 (50+ chars), CARDS 10 (cards in 2+ categories), GOALS 8, VERIFIED 8 (selfie), HEIGHT 4.
                     // A hint for the owner only: the feed never ranks by it.
               missingForPublish: string[]   // subset of 'displayName','birthDate','gender','seeking','city','photos'; empty => can publish
               decorations: Decorations,     // how others see my profile
               verified: boolean,            // selfie checked by a moderator; kept when photos are added
               identityLocked: boolean,      // gender and birth date change only via support (never for test accounts)
               createdAt, updatedAt }
PublicProfile = { id, displayName, age, city, aboutText: string|null, dailyStatusText: string|null, tags: Tag[], photos: Photo[],
                  decorations: Decorations, verified: boolean (selfie checked by a moderator, see Selfie verification),
                  compatibility: number|null (0..100, match with the VIEWER by cards; null with fewer than 2 categories
                  filled in by both) }
             Compatibility: each category both filled in counts equally. Scale category: 1 − |mean position A − mean
             position B| / scale span (neighbours close, opposite ends far). Other categories: shared cards / the
             smaller selection. Present in the feed, received likes and matches.
             (no accountId, no statuses)
Decorations = { [slot: 'PHOTO_FRAME'|'PROFILE_STATUS'|...]: Decoration }   // only public slots; may be {}
Decoration  = { code, title, rarity: 'COMMON'|'RARE'|'EPIC'|'LEGENDARY'|null, attributes: object }
             Render generically from attributes, unknown keys/slots must be ignored:
               PHOTO_FRAME:    { borderColor: '#RRGGBB', borderWidth: number, glow?: boolean }
               PROFILE_STATUS: { emoji: string, label: string, color: '#RRGGBB' }
Account    = { id, phoneMasked, status: 'ACTIVE'|'SUSPENDED'|'DELETION_PENDING'|'DELETED', createdAt, testAccount: boolean,
               roles: string[] }   // staff roles 'ADMIN'|'CONTENT_EDITOR'|'MODERATOR'|'SUPPORT' (sorted); [] for regular users

## Auth
POST /auth/otp/request   body { phone: string }   (any human format: "+7 (999) 123-45-67", "89991234567" … server normalizes)
  200 { challengeId, expiresAt, maskedPhone, devMockCode?: string }   // devMockCode present ONLY when server mock mode is on
  400 INVALID_PHONE | VALIDATION_ERROR ; 429 RATE_LIMITED ; 503 OTP_DELIVERY_UNAVAILABLE
POST /auth/otp/verify    body { challengeId: uuid, code: "6 digits" }    (NO phone parameter anymore)
  200 { token, expiresAt, accountId }
  400 INVALID_CHALLENGE | CHALLENGE_ALREADY_USED | CHALLENGE_EXPIRED | MAX_ATTEMPTS_EXCEEDED | INVALID_CODE ; 403 ACCOUNT_SUSPENDED
POST /auth/logout        (auth)  204

## Account
GET    /me/restrictions   (auth) 200 [ { kind: RestrictionKind, reason, startsAt, endsAt: string|null } ]   my active restrictions
                          (see "Sanctions" in Admin); show them instead of failing silently.
GET    /me   (auth) 200 Account
DELETE /me   (auth) 204 — wipes profile, photos, sessions; token becomes invalid immediately.

## Tags
GET /tags  (public) 200 Tag[] sorted by sortOrder

## Cities (public) — dictionary (GeoNames, CC BY 4.0: show «Данные о городах: GeoNames» in the app)
GET /cities?query=пит&limit=10 (1..20)  200 [ { id, name, region, matchedName: string|null } ]
  Exact name first, then prefix, then similar (typos); bigger cities first. matchedName = the alias that matched
  when it is not the official name («Питер», «Ленинград», «СПб», «Мск»). Empty query → [].

## Location (auth) — approximate, for distances; never shown to others as coordinates
PUT    /me/location   body { latitude, longitude }  200 MyProfile (rounded to 0.01°, ~1 km) ; 400 VALIDATION_ERROR
DELETE /me/location   200 MyProfile — distances fall back to the city centre

## My profile (auth)
GET  /me/profile           200 MyProfile (created empty on first access: displayName/age/city null, DRAFT)
PUT  /me/profile           body (all optional; omitted = unchanged):
       { displayName?: string (2..50 after trim), age?: int 18..100, city?: string (1..60 after trim),
         aboutText?: string (<=1000, "" clears), tagCodes?: string[] (<=10, must be known active codes, else 400 UNKNOWN_TAG),
         dailyStatusText?: string (<=200; "" clears; sets 24h expiry),
         gender?: 'MALE'|'FEMALE', seeking?: 'MALE'|'FEMALE'|'ANY' (ANY = both), birthDate?: 'yyyy-MM-dd' (18..100),
         ageFrom?: 18..100, ageTo?: 18..100 (>= ageFrom), clearAgeTo?: true,
         cityId?: number (from GET /cities; 400 fieldErrors.cityId if unknown), searchRadiusKm?: 1..20000,
         clearSearchRadius?: true }
       city (free text) is legacy: resolved through the dictionary («Питер» → Санкт-Петербург); unknown text is kept
       without an id (no distances).
       After the first publish gender is locked (400 fieldErrors.gender) and birthDate may only be refined within the same
       age (400 fieldErrors.birthDate); support changes them.
       age (18..100) is legacy: sets an approximate birthDate when birthDate is absent.
       200 MyProfile ; 400 VALIDATION_ERROR (fieldErrors) | UNKNOWN_TAG
POST /me/profile/publish   200 MyProfile ; 400 PROFILE_INCOMPLETE with fieldErrors keyed by missing fields
POST /me/profile/hide      200 MyProfile

## Photos (auth)
GET    /me/profile/photos               200 Photo[]
POST   /me/profile/photos               multipart/form-data, field name `file`; JPEG/PNG/WebP (checked by content, not header), <=5 MB, max 6 photos.
                                        Server strips EXIF/metadata. 200 Photo ; 400 PHOTO_LIMIT_EXCEEDED | FILE_TOO_LARGE | NO_PERSON_ON_PHOTO | NO_FACE_ON_PHOTO ; 415 UNSUPPORTED_MEDIA_TYPE
                                        Photo check (app.faces.model-dir; off when blank): no person at all → 400 NO_PERSON_ON_PHOTO.
                                        A person whose face is hidden (hair, back, far away): 400 NO_FACE_ON_PHOTO until the selfie is
                                        verified, accepted as is after. Verified profile and a visible face: compared with the reference
                                        photos (in the profile when the selfie was approved, plus moderator-approved ones); no match →
                                        underReview. Face vectors are computed for one comparison and never stored.
PATCH  /me/profile/photos/{photoId}     no body — make primary. 204 ; 404 PHOTO_NOT_FOUND
DELETE /me/profile/photos/{photoId}     204 ; 404 PHOTO_NOT_FOUND
GET    /media/photos/{photoId}?exp&sig  (public, signature-checked) binary image with correct Content-Type; 404 if bad/expired sig or deleted.

## Discovery (auth REQUIRED)
GET /discovery/profiles?city=&minAge=&maxAge=&tagCodes=A,B&tagLogic=AND|OR&page=0&limit=20
  limit 1..50 (default 20), page >=0. Excludes caller's own profile. Only PUBLISHED profiles of ACTIVE accounts.
  Tag filter applied in DB before pagination (totals are exact).
  Caller's exclusion words (below) are applied automatically, also before pagination.
  Two-way hard filters (docs/matching.md): the candidate's gender is who I look for and vice versa; the candidate's
  age is in my ageFrom..ageTo and mine is in theirs (minAge/maxAge only narrow mine for this request); goals meet
  (none set or NOT_SURE matches all). Without my gender, seeking and birthDate: 400 PROFILE_INCOMPLETE (fieldErrors).
  cityId=… (or legacy city text, resolved through the dictionary) keeps one city. radiusKm=… (or my saved
  searchRadiusKm) keeps profiles within that many km: points are the shared location, else the city centre;
  profiles without either are left out. A profile whose owner liked me passes through my radius.
  PublicProfile.distanceKm (whole km, >= 1) is present only when both of us share our location.
  Order: best score first (docs/matching.md, part 2; SQL feed_score_parts in migration 024): distance (up to 30;
  a city centre on either side = 15), shared goals (up to 25), profile quality (up to 20), −0.15 per day without a
  visit (premium −0.1), boost +12, newcomer week +15, outside the candidate's own radius −20, shown 10 min–3 h ago
  without a swipe −10, people who liked me get a random 0..25 (stable for a day). Never by the date of an edit.
GET /admin/discovery/score?viewerAccountId=&candidateProfileId=   (MODERATOR|ADMIN) 200 { distance, goals, quality,
  recency, boost, newcomer, outside_their_radius, recently_shown, liked_me, total } — why a profile stands where it does
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

## Purchases — STUB until real payments (auth)
POST /me/purchases/stub   body { product: PREMIUM_MONTH (30 days) | SUPERLIKE_PACK (5) | BOOST_PACK (3) }  200 Wallet — granted
                          without payment; 404 PRODUCT_NOT_FOUND ; 403 PURCHASES_UNAVAILABLE when app.purchases.stub-enabled=false

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
POST   /testing/wallet/items/{itemCode}          body { quantity 1..100000 }  add consumables / grant cosmetic
PUT    /testing/wallet/items/{itemCode}          body { quantity 0..100000, total?: boolean }  set the bought (PERMANENT) balance
                                                 exactly; total=true sets the whole balance, premium allowance units included
POST   /testing/premium                          body { days 1..3650 }   add premium days
DELETE /testing/premium                          end premium now
POST   /testing/clock/advance                    body { days 1..3650 }   move this account's economy time forward
                                                 (7 = "next premium week": allowances refill, premium may expire)
DELETE /testing/clock                            back to real time

## Premium content (v1)
Premium-only features are listed in server config `app.economy.premium-features`; codes today:
  ADVANCED_FILTERS — discovery by height + saved excluded goals
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
GET /discovery/profiles additionally accepts: minHeight, maxHeight (cm; profiles without height do not match).
  goalCodes=A,B (profile has at least one of them) is free.
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
POST   /testing/profiles/crowd                    body { count 1..50, likeMe?: boolean }  201 { created, liked }
                                                  published profiles that match me (my seeking, age range, city), photos copied
                                                  from existing seed profiles of that gender (else 400 NO_SEED_PHOTOS; my
                                                  gender/seeking/city missing → 400 PROFILE_INCOMPLETE); likeMe → each likes me
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

## Gacha admin (auth + role CONTENT_EDITOR or ADMIN, else 403 ROLE_REQUIRED) — /admin/gacha
Every change is written to the admin audit log (entityType GACHA_BANNER).
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
## Boost (auth) — see docs/boost.md
For `app.boost.duration` (default 30 min) the caller's PUBLISHED profile gets +12 to its feed score (it is shown more
often, but a clearly better match stays above it), ONLY among profiles that already match their filters (city/age/tags/premium filters/exclusion words/excluded goals/
test-real isolation all still apply). Costs 1 BOOST consumable (premium: 1 per week, TOP_UP).
BoostStatus = { active: boolean,
                startsAt: string|null, endsAt: string|null,   // current boost; null when not active
                impressions: number,                          // current boost; 0 when not active
                available: number,                            // BOOST balance
                durationMinutes: number,                      // length of a new boost
                now: string,                                  // "now" on the owner's clock (shifted for test accounts): use it for the countdown
                history: [ { id, startsAt, endsAt, impressions } ] }   // last 10 incl. the current one, newest first
  impression = the profile was included in a discovery page served to ANOTHER user while the boost was active.
GET  /me/boost   200 BoostStatus
POST /me/boost   body { idempotencyKey: string (1..100; new per tap, reused on retry) }   200 BoostStatus
  400 PROFILE_NOT_PUBLISHED ; 409 BOOST_ALREADY_ACTIVE (nothing spent; boosts do not stack) ;
  409 INSUFFICIENT_BALANCE details { itemCode: 'BOOST', available } ; 400 VALIDATION_ERROR (fieldErrors.idempotencyKey)
  A retry with the same key returns 200 with the same boost and never spends twice (even after the boost has ended).
Others do NOT see that a profile is boosted. Only when the server runs with app.boost.public-badge=true does
PublicProfile additionally carry `boosted: true` while boosted (the field is omitted otherwise).

## Testing: boost (part of the Testing API: test accounts only, app.testing.enabled=true)
Optional `?accountId=<seed profile id>` targets a seed profile instead of the caller (else 404 SEED_PROFILE_NOT_FOUND).
TestBoostState = { accountId, status: BoostStatus, boosts: [ { id, startsAt, endsAt, impressions } ] (all, up to 100, newest first) }
GET    /testing/boost                 200 TestBoostState
POST   /testing/boost                 body { minutes?: 1..1440 } (body optional)  start WITHOUT spending ; 400 PROFILE_NOT_PUBLISHED ; 409 BOOST_ALREADY_ACTIVE
DELETE /testing/boost                 end the boost now
PUT    /testing/boost/impressions     body { impressions 0..1e9 }  set impressions of the active (else the latest) boost ; 404 BOOST_NOT_FOUND
Boosts follow the owner's economy clock: POST /testing/clock/advance ends a running boost for everyone;
DELETE /testing/clock brings it back if it is still within its real 30 minutes.

## Reactions (auth) — like / super like / pass from the feed
Profiles are addressed by PublicProfile.id (account ids are never exposed). Target must be a PUBLISHED, public profile of an
ACTIVE account of the same kind (test/real), not blocked either way; otherwise 404 PROFILE_NOT_FOUND (a block is never revealed).
ReactionResult = { matched: boolean, matchId?: uuid }   // matchId only when matched (mutual like, or the pair is already matched)
POST /discovery/profiles/{profileId}/like        200 ReactionResult ; 404 PROFILE_NOT_FOUND ; 400 SELF_ACTION
POST /discovery/profiles/{profileId}/superlike   body { idempotencyKey: string (1..100, non-blank; new per tap, reused on retry) }
  200 ReactionResult ; 409 INSUFFICIENT_BALANCE details { itemCode: 'SUPERLIKE', available } ; 400 VALIDATION_ERROR | SELF_ACTION ; 404 PROFILE_NOT_FOUND
  Spends 1 SUPERLIKE; a retry with the same key, or a target already super-liked, never spends again.
POST /discovery/profiles/{profileId}/pass        200 ReactionResult (always matched: false) ; 409 ALREADY_LIKED ; 404 PROFILE_NOT_FOUND ; 400 SELF_ACTION
  Rules: repeating a reaction is idempotent (200, matched: false); like after a super like changes nothing; a like/super like
  to an already matched pair returns matched: true + matchId and spends nothing. Only a NEW like can create a match
  (re-liking after an unmatch does not resurrect it). A pass hides the profile from the feed for app.social.pass-ttl (30d);
  liked/super-liked profiles and blocks (both directions) are hidden from discovery permanently.
  Side effects: the target gets the `like_received` real-time event (no match) or both get `match` (see Realtime).

## Profile views (auth) — for the like-spam rule; never shown to anybody
POST /discovery/views   body { views: 1..100 × { profileId: uuid, dwellMs: 0..3600000 (time on screen, card + details),
                        openedDetails?: boolean } }   204 ; 400 VALIDATION_ERROR. Unknown / own profiles are skipped.
  Send in batches when cards leave the screen or details close. Kept 90 days, deleted with the account.

## Like spam (automatic)
Over the last 10 reactions (within 10 min): >= 90 % likes AND (median pause < 3 s OR, when views were sent for most
of the likes, >= 80 % of likes after < 1 s on screen without opening the details) → LIKES restriction (reason LIKE_SPAM,
source RULE) for 1 h, doubling with each repeat within 30 days up to 7 d. The likes of that window are removed (they
vanish from the receivers' "who liked me"); likes behind an existing match stay. The like that triggered it still
answers 200 (and is removed); the next one gets 403 ACCOUNT_RESTRICTED. Passing is never restricted.

## Selfie verification (auth) — against fake profiles; badge `verified` on PublicProfile and MyProfile
VerificationRequest = { id, status: 'AWAITING_SELFIE'|'PENDING'|'APPROVED'|'REJECTED'|'EXPIRED', gesture (code), instruction (text to show),
                        expiresAt (upload deadline, 30 min), submittedAt, decidedAt, rejectReason }
GET  /me/verification                       200 { verified: boolean, verifiedAt, current: VerificationRequest|null (latest) }
POST /me/verification   body { faceComparisonConsent: true } (required, else 400 CONSENT_REQUIRED)
                        200 VerificationRequest — the open request (same gesture) or a new one with a random gesture
                                            409 ALREADY_VERIFIED | VERIFICATION_PENDING ; 429 RATE_LIMITED (3 requests a day)
POST /me/verification/{id}/selfie           multipart `file` (JPEG/PNG/WebP, <= 5 MB, metadata stripped) — a selfie showing the gesture
                                            200 VerificationRequest (PENDING) ; 404 VERIFICATION_NOT_FOUND ; 409 VERIFICATION_EXPIRED |
                                            VERIFICATION_NOT_AWAITING ; 400 EMPTY_FILE ; 413 FILE_TOO_LARGE ; 415 UNSUPPORTED_MEDIA_TYPE
  A moderator compares the selfie with the profile photos BY EYE (no automated face matching); the selfie is deleted right after
  the decision. Approval sets the badge and lifts VERIFICATION_REQUIRED. Adding a photo later keeps the badge.

## Photo review (MODERATOR / ADMIN)
GET  /admin/photo-reviews?page=0&limit=20   oldest first
  200 { items: [{ photoId, accountId, profileId, displayName, photo: Photo, references: Photo[], uploadedAt }], page, limit,
        totalElements }
POST /admin/photo-reviews/{photoId}/decision   body { approve: boolean }   204
  approve: visible and a reference from now on; reject: the photo is deleted. 404 PHOTO_NOT_FOUND ; 409 PHOTO_NOT_UNDER_REVIEW

## Received likes (auth) — "who liked me"
Likes I have not answered yet (no like back, no active pass of mine, no block); super likes first, then newest.
ReceivedLike = { profile: PublicProfile, superlike: boolean, likedAt }
GET /me/likes/received?page=0&limit=20   page < 0 → 0; limit <= 0 → 20, capped at 50
  200 { items: ReceivedLike[], page, limit, totalElements, totalPages }
  403 PREMIUM_REQUIRED details.feature = 'WHO_LIKED_ME' (only while WHO_LIKED_ME is in Wallet.premiumFeatures; free otherwise)
GET /me/likes/received/count             200 { count, superlikeCount }   (always available, no premium)

## Verification gate (app.verification-gate, on by default; test accounts exempt)
- Feeds of real users show only profiles with a verified selfie (MyProfile.verified).
- An unverified viewer sees at most 50 distinct profiles a day (Moscow day):
  GET /discovery/profiles → 429 DAILY_VIEWS_LIMIT details.limit (the last page may be shorter).
- An unverified like is queued: 200 { matched: false, queued: true }. Nobody is told, no match yet, the profile leaves
  my feed. On selfie approval queued likes go out as normal likes (match, push). A queued like dies 24 h after it was
  made; time while a selfie waits for review does not count.
- Super like while unverified: 403 VERIFICATION_REQUIRED.

## Going back in the feed (auth)
POST /me/reactions/undo   premium UNDO_SWIPE; takes back my newest like or pass  200 PublicProfile (show it again)
  403 PREMIUM_REQUIRED details.feature='UNDO_SWIPE' ; 409 NOTHING_TO_UNDO ; 409 UNDO_LIMIT_REACHED (5 in a row; any new
  swipe resets the count) ; 409 CANNOT_UNDO (a super like, or a like that made a match)
POST /me/passes/refresh?feedEmpty=false   brings all my passed profiles back  200 { restored: int }
  With premium PASS_REFRESH: any time. Without: only with feedEmpty=true (the client's feed ran out), once per 24h:
  409 FEED_NOT_EMPTY details.feature='PASS_REFRESH' ; 429 PASS_REFRESH_TOO_SOON details.retryAt
A pass no longer returns by itself (app.social.pass-ttl = 10 years).

Feed visibility: profiles inactive for 30 days (no activity day; profiles younger than 30 days are kept) leave the feed
of real users; a push `inactivity_reminder` goes 7, 3 and 1 day before. Invisible profiles (MyProfile.invisible, premium
INVISIBLE; ignored while premium is off) are shown only to people their owner liked. MyProfile.showPremiumBadge=false
makes PublicProfile.premium false for others.

## Blocks and reports (auth)
POST /accounts/{profileId}/hide    (PROFILE id) 204 ; 404 PROFILE_NOT_FOUND ; 400 SELF_ACTION
  Idempotent. «Not interested»: only I stop seeing the person (feed and received likes); they are not told and still see me.
POST /accounts/{profileId}/block   (path takes the PROFILE id) 204 ; 404 PROFILE_NOT_FOUND ; 400 SELF_ACTION
  Idempotent. Works on any existing profile of an active account of the same kind (also hidden ones). Hides both people
  from each other everywhere (feed, received likes, matches) and closes their active match silently (both get `match_closed`).
POST /reports   body { profileId: uuid (required), messageId?: uuid, reason: 'SPAM'|'FAKE_PROFILE'|'INAPPROPRIATE_CONTENT'|'HARASSMENT'|
                       'SCAM'|'UNDERAGE'|'OTHER'|'NOT_RESPONDING' (required), comment?: string (<= 1000; blank = none) }
  201 { id, createdAt, status: 'NEW'|'DISMISSED', note: string|null }
  Silence is not a violation: NOT_RESPONDING — and OTHER whose comment only complains about silence while the person
  never wrote to the reporter — is closed at once (status DISMISSED, note = a hint to show, e.g. that the match can be
  undone). It never reaches moderators and does not count against the reported person.
  400 VALIDATION_ERROR | SELF_ACTION | MALFORMED_REQUEST (unknown reason) ; 404 PROFILE_NOT_FOUND ;
  404 MESSAGE_NOT_FOUND (messageId must be a message FROM the reported person in a chat I take part in, active or closed) ;
  429 RATE_LIMITED (app.social.reports-per-hour, default 20 per account)
  Stored with status NEW (message text/sticker snapshot kept); moderators work them in /admin/reports (see Admin).

## Matches and chat (auth)
Unmatched / blocked matches (or ones whose other account is gone) answer 404 MATCH_NOT_FOUND for both people.
Message   = { id, matchId, mine: boolean, type: 'TEXT'|'STICKER', text: string|null (TEXT), stickerPackCode: string|null (STICKER),
              sticker: string|null (STICKER, a code from the pack's attributes.stickers), clientMessageId: string|null (only on my own
              messages), createdAt, replyTo: { id, mine, type, text: string|null (null for stickers) }|null (the message this
              one answers; null when none or it is gone) }
Match     = { id, createdAt, profile: PublicProfile (the other person), lastMessage: Message|null, unreadCount: number,
              myLastReadMessageId: uuid|null, otherLastReadMessageId: uuid|null, otherLastReadAt: string|null }   // other* = read receipt
ReadState = { matchId, lastReadMessageId, lastReadAt, unreadCount }
GET    /matches                         200 Match[] (active only, most recent activity first: last message, else match time)
DELETE /matches/{matchId}               unmatch: 204 ; 404 MATCH_NOT_FOUND. Hides the chat for both, blocks new messages; likes are
                                        kept, so the two do not meet again in the feed. Both get `match_closed`.
GET    /matches/{matchId}/messages?before=<messageId>&limit=30
  newest first; limit <= 0 or absent → 30, capped at 100; before = id of the oldest message the client already has.
  200 { items: Message[], nextBefore: uuid|null }   // pass nextBefore as before; null = no older messages
  404 MATCH_NOT_FOUND | MESSAGE_NOT_FOUND (before is not a message of this match)
POST   /matches/{matchId}/messages
  body { clientMessageId: string (1..64, non-blank; generated once per message, reused on retry), type: 'TEXT'|'STICKER',
         text?: string (TEXT: 1..2000 after trim; bean limit 4000 raw), stickerPackCode?: string (<= 50), sticker?: string (<= 50),
         replyToMessageId?: uuid (a message of this chat; else 404 MESSAGE_NOT_FOUND) }
  TEXT must not carry sticker fields; STICKER needs stickerPackCode + sticker and no text.
  200 Message (as seen by me). A retry with the same clientMessageId returns the original message (nothing stored twice,
  not rate limited, no new events).
  400 VALIDATION_ERROR (fieldErrors.text|sticker|stickerPackCode|clientMessageId|type) | NOT_A_STICKER_PACK | UNKNOWN_STICKER ;
  403 ITEM_NOT_OWNED (sticker pack not owned) ; 404 MATCH_NOT_FOUND | ITEM_NOT_FOUND ;
  429 RATE_LIMITED (app.chat.messages-per-minute, default 30 per account)
  Both participants get the `message` real-time event after commit.
POST   /matches/{matchId}/read          body { lastReadMessageId: uuid (required, any message of the match) }
  200 ReadState ; 404 MATCH_NOT_FOUND | MESSAGE_NOT_FOUND ; 400 VALIDATION_ERROR
  The pointer only moves forward (an older id is ignored). When it moves, the other person gets the `read` event.

Match additionally carries otherLastSeenAt: string|null (the other person's last authenticated request, written at most once a
minute) and otherOnline: boolean (an open event stream or activity in the last 2 minutes).
POST /matches/{id}/typing   204 ; 404 MATCH_NOT_FOUND — relays `typing` to the other person; send at most every ~3 s while typing

## Date plan (auth) — see docs/date-plan.md
DatePlanItem = { placeName, address: string|null, latitude, longitude, startsAt, activity: COFFEE|WALK|FOOD|CINEMA|CULTURE|ACTIVE|OTHER,
                 comment: string|null, venue: Venue|null }
DatePlan = { id, matchId, status: PROPOSED|ACCEPTED|DECLINED|CANCELLED, version, mine (I proposed), lastEditMine,
             canRespond (I may accept/decline), canEdit (open and premium), items: DatePlanItem[], createdAt, updatedAt }
Venue = { id, name, address, latitude, longitude, category, description, ad: { label: 'Реклама', advertiser, erid } | null }
GET    /matches/{id}/date-plan        200 { plan: DatePlan|null }   the plan in play (PROPOSED or ACCEPTED)
POST   /matches/{id}/date-plan        body { items: [ { placeName?, address?, latitude?, longitude?, startsAt, activity?, comment?,
                                      venueId? } ] (1..5; a venue fills name, coordinates, address and activity) }  201 DatePlan ;
                                      403 PREMIUM_REQUIRED (DATE_PLAN) | ACCOUNT_RESTRICTED ; 409 PLAN_ALREADY_OPEN ;
                                      400 VALIDATION_ERROR fieldErrors "items[i].startsAt" (future, <= 120 days) …
GET    /date-plans/{id}               200 DatePlan (participants only, else 404 PLAN_NOT_FOUND)
PUT    /date-plans/{id}               body { version, items }  counter proposal (premium) 200 ; 409 PLAN_CHANGED | PLAN_CLOSED
POST   /date-plans/{id}/accept|decline   only the side it was proposed to: 200 ; 409 PLAN_NOT_YOURS_TO_ANSWER | PLAN_CLOSED
POST   /date-plans/{id}/cancel        either side, open plans: 200 ; 409 PLAN_CLOSED
GET    /date-plans/{id}/history       200 [ { version, mine, action: PROPOSE|EDIT|ACCEPT|DECLINE|CANCEL, items, at } ]
Every action posts a chat Message { type: 'DATE_PLAN', text: one-line summary, datePlanId } to both (SSE `message`, push).
Community = { id, title, kind: 'FRANCHISE'|'FILM'|'SERIES'|'BOOK'|'ANIME'|'GAME'|'MUSIC'|'OTHER', kindLabel, emoji|null,
              description|null, members, subscribed }        (docs/communities.md)
GET    /communities?q=&kind=          200 Community[] (<= 100, active, most members first); unknown kind → 400 UNKNOWN_KIND
GET    /communities/{id}              200 Community ; 404 COMMUNITY_NOT_FOUND
GET    /me/communities                200 Community[] (mine, latest joined first)
PUT|DELETE /communities/{id}/subscription   200 Community (subscribe / unsubscribe; repeating is a no-op)
POST   /communities/suggestions       body { title (2..80), comment? (<= 300) } 201 ; 429 RATE_LIMITED after 5 a day
GET|POST /admin/communities, PUT /admin/communities/{id}   (CONTENT_EDITOR, ADMIN) body { slug (create only, ^[a-z0-9-]),
                                      title, kind, emoji?, description?, sortOrder?, active? }
GET    /admin/communities/suggestions ; POST /admin/communities/suggestions/{id}/decision { accepted: boolean } 204
Discovery: GET /discovery/profiles?communityIds=1,2 — only members of at least one; PublicProfile.communities =
  [{ id, title, emoji, shared }] (shared = the viewer is in it too, listed first); shared group titles come first in `common`.
GET    /venues?latitude=&longitude=   200 Venue[] (<= 50, ~30 km, sponsored first); test accounts see only test places
GET    /places/search?q=&latitude=&longitude=   200 { name, address|null, latitude, longitude }[] (<= 8, address or place
                                      name, nearest to the point first); q < 2 chars or search off/unavailable → [];
                                      429 RATE_LIMITED after 30 a minute. For now public Photon (OpenStreetMap), testing only
GET|POST /admin/venues, PUT /admin/venues/{id}   (CONTENT_EDITOR, ADMIN) body { name, address?, latitude, longitude, category,
                                      description?, sponsored?, advertiser? (required when sponsored), erid?, active? } ; audited (VENUE)
POST   /testing/venues                body { latitude, longitude }  201 — 3 test-only places around the point, one sponsored

## Realtime (Server-Sent Events)
POST /events/ticket   (auth) 200 { ticket: string, expiresAt }   one-time, valid app.realtime.ticket-ttl (60s), kept in memory
GET  /events          text/event-stream. Auth: `Authorization: Bearer` header (mobile, fetch-based clients) OR `?ticket=<ticket>`
                      (browser EventSource). The session token is NOT accepted in the URL. Without either: 401 UNAUTHORIZED (JSON).
  Response headers: Cache-Control: no-cache, X-Accel-Buffering: no. Each event: `event: <name>` + `data: <JSON>`.
  A connection is closed after app.realtime.emitter-timeout (30m); max app.realtime.max-connections-per-account (5) per
  account, the oldest is closed. Best effort, no replay: after every (re)connect (`ready`) refetch matches and messages.
  Events:
    ready          { heartbeatSeconds }                 right after connecting
    heartbeat      { at }                               every app.realtime.heartbeat-interval (25s)
    message        Message                              new message in one of my matches (as seen by the receiver: `mine` true
                                                        for my own messages, which are also pushed to my other connections)
    match          Match                                a new match (as seen by the receiver)
    match_closed   { matchId }                          unmatch, block, or the other account was deleted
    like_received  { superlike: boolean, count }        someone liked me (no identity); count = GET /me/likes/received/count .count
    read           { matchId, lastReadMessageId, lastReadAt }   the other person read the chat up to this message
    typing         { matchId }                          the other person is typing (not stored; show «печатает…» ~5 s)
  All events are sent only after the business transaction commits. Account deletion closes all its streams.

## Push notifications (auth)
The push token of THIS device, tied to the current session: logout or session expiry stops pushes, account deletion
removes the device. One device per session (a refreshed token replaces the old one); a token seen under another
session moves there. Register after every login and whenever the SDK reports a new token.
PUT    /me/push-device   body { provider: 'RUSTORE'|'FCM', token: string (1..1024) }   204 ; 400 VALIDATION_ERROR | MALFORMED_REQUEST
DELETE /me/push-device   204 (forget this device; idempotent)
  RUSTORE = RuStore Push SDK token (Android in Russia); FCM = Firebase token (Android with Google services, iOS via APNs).
  Pushes (FCM HTTP v1 / RuStore format) are sent after commit, best effort, also when the app is open (hide them in the
  foreground: the same thing arrives over SSE). Texts are generic (no names, no message text). `data` (all strings):
    { type: 'match', matchId }               new match (both people)
    { type: 'message', matchId }             new message (recipient only)
    { type: 'like_received', superlike: 'true'|'false' }
  A provider without server credentials is off: devices are kept, nothing is sent.

## Catalog (auth)
CatalogItem = { code, kind: 'CONSUMABLE'|'COSMETIC', category, equipSlot: string|null, rarity: string|null, title, attributes: object,
                availableFrom: string|null, availableUntil: string|null,   // acquisition window [from, until), null = open
                availableNow: boolean,                                      // in the caller's economy time (shifted for test accounts)
                author: { id, displayName } | null }
GET /catalog?kind=CONSUMABLE|COSMETIC&category=   200 CatalogItem[] — active items only (also outside their window), sorted by
                                                  kind, category, code ; 400 VALIDATION_ERROR (unknown kind)
  attributes.imageUrl (optional) is a relative public URL "/api/v1/catalog/images/{imageId}".
GET /catalog/images/{imageId}   (public, no auth) binary image (JPEG/PNG/WebP), Cache-Control: public, max-age=1 year, immutable ;
                                404 IMAGE_NOT_FOUND. An image never changes; a new upload gets a new id.

## Interests, music, zodiac (docs/matching.md, part 1)
GET /interests          (public) 200 [{ code, label, items: Interest[] }]   // categories in display order
Interest = { code, label, category, categoryLabel }
GET /artists?q=         (auth)   200 Artist[] (<= 10)  Artist = { id, name }; q < 2 chars → []. Our dictionary first; with fewer
                        than 5 matches the server asks MusicBrainz once per query and adds what it finds (app.music.musicbrainz-enabled).
PUT /me/profile         + interestCodes: string[] (<= 10, [] clears; unknown → 400 fieldErrors.interestCodes),
                          interestComments: { code: string } (<= 80 each, only for chosen interests, "" removes;
                          else 400 fieldErrors.interestComments). Interest = { code, label, emoji|null, category, categoryLabel,
                          comment: string|null }; a dropped interest loses its comment,
                          artistIds: number[] (<= 5), showZodiac: boolean
MyProfile               + interests: Interest[], artists: Artist[], showZodiac, zodiac: string|null (my sign, e.g. «Лев»)
PublicProfile           + sharedTags: string[] (feed only: codes of the cards both chose; omitted when empty)
PUT /me/profile tagCodes: at most one card per category, else 400 ONE_TAG_PER_CATEGORY (fieldErrors.tagCodes names them)
PublicProfile           + interests, artists, zodiac (only when the owner shows it), common: string[] (feed only: up to 2
                          interests / artists / cards the viewer shares; omitted when empty)
Every change of the profile stores its previous state in profile_history (no API; for moderation and support).
Removed reference photos keep their files so later photos can still be compared with them.

## Profile facts and fact filters (docs/matching.md, part 1)
GET /profile-options    (public) 200 { kidsHas, kidsPlans, smoking, alcohol, sport, pets, educationLevel, languages:
                          [{ code, label }] }   // answers in display order; clients show these labels
PUT /me/profile         + kidsHas, kidsPlans, smoking, alcohol, sport, educationLevel: code ('' clears),
                          pets, languages: code[] ([] clears; pets NONE cannot be mixed with animals),
                          profession (<= 80), university (<= 120): text ('' clears); unknown code → 400 fieldErrors.<field>
MyProfile, PublicProfile + facts: { kidsHas, kidsPlans, smoking, alcohol, sport, pets, profession, educationLevel,
                          university, languages }   // null / [] = not said
GET /me/fact-filters    (auth) 200 { <fact>: { accept: code[], unspecified: boolean } }
PUT /me/fact-filters    (auth) same body; replaces all. Keys: kidsHas, kidsPlans, smoking, alcohol, sport, pets
                          (other → 400); accept must not be empty (400). Free, not premium.
Two-way: a candidate is shown only if their facts pass my filters AND mine pass theirs; `unspecified` also lets
through those who did not say. Feed score: + interests (3 per shared interest, up to 4 for shared categories,
3 per shared artist; capped together with goals at 25) and behavior (stub, 0).

## Support (auth) — see docs/support.md
GET    /faq          (public) 200 [ { id, question, answer, sortOrder } ]   active items, by sortOrder (the app keeps a built-in copy as fallback)
GET    /admin/faq    (CONTENT_EDITOR, SUPPORT, ADMIN) 200 [ { id, question, answer, sortOrder, active, updatedAt } ]  all, by sortOrder
POST   /admin/faq    body { question (1..200), answer (1..4000), sortOrder? 0..100000 (0), active? (true) }  201 ; 400 VALIDATION_ERROR
PUT    /admin/faq/{id}  same body, full replace  200 ; 404 FAQ_NOT_FOUND
DELETE /admin/faq/{id}  204 ; 404 FAQ_NOT_FOUND        Changes are audited (FAQ_ITEM).
Users: POST/GET /support/tickets, GET /support/tickets/{id}, POST /support/tickets/{id}/messages.
Staff (SUPPORT, MODERATOR, ADMIN): /admin/support/tickets… — queue, reply (push `support_reply`), close.

## Admin (auth + staff role; /admin/**)
Roles are read from the DB on every request (granting/revoking applies to the next request). Missing role: 403 ROLE_REQUIRED
details.roles. "Editors" below = CONTENT_EDITOR or ADMIN. Moderators = MODERATOR or ADMIN. Support staff = SUPPORT, MODERATOR or ADMIN (tickets, docs/support.md). Every write is recorded in the
audit log (same transaction). Gacha admin endpoints (editors): see "Gacha admin" above.

### Reports moderation (moderators)
ModerationReport = { id, status: 'NEW'|'IN_REVIEW'|'RESOLVED'|'DISMISSED', reason: ReportReason (as reported),
                     finalReason: ReportReason|null (confirmed violation), decision: 'ACCEPTED'|'RECLASSIFIED'|'REJECTED'|null,
                     sanctioned: boolean (the policy was applied), reportedRiskScore: number|null (bot / fake risk),
                     autoDismissed: boolean (closed by the system), priority: number, priorityReasons: string[],
                     comment, messageId, messageSnapshot, reporterAccountId, reportedAccountId, reportedProfileId,
                     reportedDisplayName: string|null (null when the profile is gone),
                     reportedConfirmedViolations: number (ACCEPTED + RECLASSIFIED reports against that account),
                     createdAt, moderatorAccountId, moderatorNote, decidedAt }
GET  /admin/reports?status=&sort=priority&page=0&limit=20   status absent = open (NEW, IN_REVIEW); limit 1..100 (else 20)
     sort=priority (default): most urgent first, then oldest; sort=oldest: arrival order. Priority = category (UNDERAGE 100,
     SCAM 80, HARASSMENT 70, FAKE_PROFILE 50, INAPPROPRIATE_CONTENT 40, SPAM 30, OTHER 20) + 10 per extra person with an open
     report on the account (max 40) + risk score / 4 (max 25) + 10 per confirmed violation (max 30) + 10 for contacts/links
     in the reported message + 60 if the message or comment reads as a threat; priorityReasons spells it out.
     RECLASSIFIED to NOT_RESPONDING → 400 (silence cannot be a confirmed violation).
     200 { items: ModerationReport[], page, limit, totalElements } ; 400 VALIDATION_ERROR (unknown status, case-insensitive)
GET  /admin/reports/{reportId}                200 ModerationReport ; 404 REPORT_NOT_FOUND
POST /admin/reports/{reportId}/decision       body { decision: 'ACCEPTED'|'RECLASSIFIED'|'REJECTED', reason?: ReportReason, note?: string (<= 1000),
                                                     sanction?: boolean (true), removePhotoIds?: uuid[] (<= 6), clearAboutText?: boolean,
                                                     clearDailyStatus?: boolean }
     ACCEPTED: violation as reported (finalReason = reason), status RESOLVED.
     RECLASSIFIED: violation of another kind; reason required and different from the reported one, status RESOLVED.
     REJECTED: no violation, status DISMISSED. reason is only allowed with RECLASSIFIED.
     200 ModerationReport ; 400 VALIDATION_ERROR (fieldErrors.reason) ; 404 REPORT_NOT_FOUND ; 409 REPORT_ALREADY_DECIDED
     A confirmed violation (ACCEPTED / RECLASSIFIED) gets the sanction policy step of its finalReason unless sanction = false
     (a duplicate report about an already sanctioned violation), and may delete photos of the reported profile (404 PHOTO_NOT_FOUND
     if not theirs) and its about text / daily status. REJECTED with content removal → 400. Every decision re-evaluates the
     review hold. Audited as entityType REPORT, action = decision.

### Sanctions
Restriction = { id, accountId, kind: RestrictionKind, reason (report reason, or LIKE_SPAM | REVIEW_HOLD | MANUAL), source: 'MODERATOR'|'RULE'|'ADMIN',
                reportId, createdBy, note, startsAt, endsAt (null = until lifted), liftedAt, liftedBy, liftNote }
RestrictionKind: LIKES (no likes / super likes) | MESSAGES (no chat messages) | HIDDEN (out of the feed and of everybody's
  "who liked me") | SUSPENDED (login closed; applying it REMOVES ALL LIKES the account gave — they vanish from the receivers'
  "who liked me" — and closes its matches) | VERIFICATION_REQUIRED (hidden until a moderator lifts it)
SanctionPolicy = { reason, windowDays, holdOnReports: number|null, steps: [ { restrictions: [ { kind, hours: number|null } ] } ] }
  The (n+1)-th sanctioned violation of a reason within windowDays gets steps[n] (the last step repeats).
  holdOnReports: the profile is hidden (REVIEW_HOLD) while that many different people have open reports with this reason.
  Defaults: UNDERAGE/SCAM → SUSPENDED (hold at 1 / 2 reporters); HARASSMENT → MESSAGES 7 d, then SUSPENDED;
  FAKE_PROFILE → VERIFICATION_REQUIRED, then SUSPENDED; INAPPROPRIATE_CONTENT → 3rd strike in 90 d: HIDDEN 7 d;
  SPAM → LIKES + MESSAGES 1 h, doubling up to 7 d (30-day window); OTHER → nothing (use a manual restriction).
GET  /admin/accounts/{accountId}/restrictions   (moderators) 200 Restriction[] newest first, incl. lifted/expired ; 404 ACCOUNT_NOT_FOUND
POST /admin/accounts/{accountId}/restrictions   (moderators) body { kind: RestrictionKind, hours?: 1..87600 (absent = until lifted),
                                                note?: <= 1000 }  201 Restriction (reason MANUAL) ; 409 ACCOUNT_DELETED
POST /admin/restrictions/{restrictionId}/lift   (ADMIN) body? { note?: <= 1000 }  200 Restriction ;
                                                404 RESTRICTION_NOT_FOUND ; 409 RESTRICTION_NOT_ACTIVE. Removed likes are NOT restored.
GET  /admin/sanction-policy                     (moderators) 200 SanctionPolicy[]
PUT  /admin/sanction-policy/{reason}            (ADMIN) body { windowDays: 1..3650, holdOnReports?: 1..100, steps: 1..20 × { restrictions: 0..5 ×
                                                { kind, hours?: 1..87600 } } }  200 SanctionPolicy ; 400 VALIDATION_ERROR (unknown reason)
Restriction changes are audited (ACCOUNT_RESTRICTION: RESTRICT / LIFT; SANCTION_POLICY: UPDATE).

### Behavior metrics (moderators)
GET /admin/accounts/{accountId}/behavior   200 { accountId, computedAt, metrics } ; 404 ACCOUNT_NOT_FOUND
  Recomputed on request (and nightly for accounts active in 90 days). metrics (rates are null when there is nothing to divide):
  likesGiven30d, superlikesGiven30d, passesGiven30d, profilesSeen30d, likeRatio (likes / (likes + passes)), medianSwipeMs,
  timesShown30d, likesReceived30d, inboundLikeRate, matches90d, dialogs90d, silentMatches90d (no message at all),
  responseRate (answered when they wrote first), ghostRate (never wrote back when they wrote), initiationRate (wrote first),
  medianResponseSeconds, messagesSent90d, avgMessageLength, oneWordShare ("answers in one word"), reportsReceived,
  reportsMade, confirmedViolations, blockedBy, activeDays30d. Only numbers: message text is never copied or returned.
  Data behind it (reactions, views, feed impressions, active days) is kept 90 days and deleted with the account.

### Selfie verification (moderators)
ReviewItem = { id, accountId, profileId, displayName, status, gesture, instruction, selfieAvailable, submittedAt, decidedAt,
               decidedBy, rejectReason, profilePhotos: Photo[] (to compare with) }
GET  /admin/verifications?status=PENDING&page=0&limit=20   oldest first; 200 { items: ReviewItem[], page, limit, totalElements }
GET  /admin/verifications/{id}                              200 ReviewItem ; 404 VERIFICATION_NOT_FOUND
GET  /admin/verifications/{id}/selfie                       the image (Authorization header required, Cache-Control: no-store);
                                                            404 SELFIE_NOT_FOUND after the decision
POST /admin/verifications/{id}/decision   body { approve: boolean, reason?: <= 500 (required to reject; the user sees it) }
                                          200 ReviewItem ; 409 VERIFICATION_NOT_PENDING ; 400 VALIDATION_ERROR. Audited (VERIFICATION).

### Bot / fake risk (moderators)
Risk = { accountId, score: 0..100, level: 'LOW' (< 30) | 'MEDIUM' (< 60) | 'HIGH', signals: [ { code, weight, detail } ], computedAt }
Signals (rules, weights summed and capped at 100): SIGNUP_BURST (3+ accounts from one address within a day, 25/35),
DUPLICATE_PHOTO (same picture on another account: exact or perceptual hash, 30), DUPLICATE_ABOUT (same "about me", 20),
CONTACTS_IN_PROFILE (links / messengers / @handles / phones, 15), SAME_MESSAGE_TO_MANY (same text in 3+ chats a week, 25),
CONTACTS_IN_MESSAGES (in 2+ chats a week, 20), LIKE_SPAM (10 per restriction, max 20), BOT_MESSAGES (25),
REPORTED_BY_MANY (2+ / 5+ reporters in 30 days, 15/30), REPORTED_AS_FAKE (FAKE_PROFILE/SCAM/SPAM, 10), NEW_ACCOUNT (< 3 days, 5).
The score only orders the queue and gives context — it never sanctions by itself. ModerationReport.reportedRiskScore shows it.
GET /admin/accounts/{accountId}/risk        recomputed now; 200 Risk ; 404 ACCOUNT_NOT_FOUND
GET /admin/risk?minScore=30&page=0&limit=20 ACTIVE accounts at/above the score, riskiest first;
                                            200 { items: [ { accountId, profileId, displayName, score, level, signals, computedAt } ], page, limit, totalElements }
Automatic rule (the only one that acts): the same text WITH links/contacts sent to 3+ different chats within a day →
MESSAGES restriction for 24 h (reason BOT_MESSAGES, source RULE). Signup addresses are stored only as a keyed hash.

### Roles and audit (ADMIN)
AdminAccount = { accountId, phoneMasked, status, testAccount, roles: string[], createdAt }
GET    /admin/roles                                  200 { available: string[] (ADMIN, CONTENT_EDITOR, MODERATOR, SUPPORT), accounts: AdminAccount[] (all accounts holding a role) }
GET    /admin/accounts/by-phone?phone=               200 AdminAccount ; 400 INVALID_PHONE (fieldErrors.phone) | MALFORMED_REQUEST (no phone) ; 404 ACCOUNT_NOT_FOUND
GET    /admin/accounts/{accountId}                   200 AdminAccount ; 404 ACCOUNT_NOT_FOUND
PUT    /admin/accounts/{accountId}/roles/{role}      grant (idempotent; role case-insensitive) 200 AdminAccount ;
                                                     400 UNKNOWN_ROLE ; 404 ACCOUNT_NOT_FOUND ; 409 ACCOUNT_NOT_ACTIVE
DELETE /admin/accounts/{accountId}/roles/{role}      revoke (idempotent) 200 AdminAccount ; 400 UNKNOWN_ROLE ; 404 ACCOUNT_NOT_FOUND ;
                                                     409 LAST_ADMIN (the last active ADMIN cannot lose the role)
GET    /admin/audit?entityType=&entityId=&limit=50   newest first; limit clamped to 1..200
  200 [ { id, actorAccountId, action, entityType, entityId, before: any|null, after: any|null, createdAt } ]
  entityType: ACCOUNT_ROLE, TAG, TAG_CATEGORY, GOAL, CATALOG_ITEM, AUTHOR, ACHIEVEMENT, PREMIUM_COMPOSITION, GACHA_BANNER
  action: CREATE, UPDATE, DELETE, DEACTIVATE, GRANT, REVOKE, TESTING_GRANT, TESTING_REVOKE, IMAGE_UPLOAD, RESET, …

### Content: tags, tag categories, goals (editors)
Codes are set on creation and never change. Deleting something that profiles use hides it (active = false) instead.
Text fields are trimmed. Bean validation errors: 400 VALIDATION_ERROR with fieldErrors.
AdminTag      = { code, category, categoryLabel, label, description, sortOrder, active, usageCount, scalePosition: number|null }
AdminCategory = { code, label, sortOrder, tagCount, scale: boolean, scaleMinLabel: string|null, scaleMaxLabel: string|null }
AdminGoal     = { code, label, sortOrder, active, usageCount }
DeleteResult  = { result: 'DELETED'|'DEACTIVATED' }   // DEACTIVATED: in use, hidden; show again with PUT active=true
GET    /admin/tags                 200 AdminTag[] (all, incl. inactive; by sortOrder, code)
POST   /admin/tags                 body { code: ^[A-Z][A-Z0-9_]{1,39}$, category (existing category code), label (1..60), description (1..255),
                                          sortOrder? 0..100000 (0), active? (true) }
                                   201 AdminTag ; 400 VALIDATION_ERROR | UNKNOWN_CATEGORY (fieldErrors.category) ; 409 TAG_EXISTS
PUT    /admin/tags/{code}          body { category, label, description, sortOrder?, active? (true) } (full replace)  200 AdminTag ;
                                   404 TAG_NOT_FOUND ; 400 VALIDATION_ERROR | UNKNOWN_CATEGORY
DELETE /admin/tags/{code}          200 DeleteResult ; 404 TAG_NOT_FOUND
GET    /admin/tag-categories       200 AdminCategory[] (by sortOrder, code)
POST   /admin/tag-categories       body { code: ^[a-z][a-z0-9_]{1,29}$, label (1..50), sortOrder? 0..100000 (0) }  201 AdminCategory ; 409 CATEGORY_EXISTS
PUT    /admin/tag-categories/{code}  body { label, sortOrder? }  200 AdminCategory ; 404 CATEGORY_NOT_FOUND
DELETE /admin/tag-categories/{code}  204 ; 404 CATEGORY_NOT_FOUND ; 409 CATEGORY_IN_USE (has tags, active or not)
PUT    /admin/tag-categories/{code}/scale  body { scale: boolean, scaleMinLabel?: <= 60, scaleMaxLabel?: <= 60,
                                       positions?: { [tagCode]: 1..100 } }  — replaces the whole scale; unlisted cards lose
                                       their place; scale=false clears it. 200 AdminCategory ; 404 CATEGORY_NOT_FOUND ;
                                       400 VALIDATION_ERROR (a scale needs both labels and 2+ cards of THIS category).
                                       Audited (TAG_CATEGORY, SET_SCALE). Defaults: pace (slow → live chat), calls (text → calls).
GET    /admin/goals                200 AdminGoal[] (all, by sortOrder, code)
POST   /admin/goals                body { code: ^[A-Z][A-Z0-9_]{1,39}$, label (1..60), sortOrder? 0..100000 (0), active? (true) }  201 AdminGoal ; 409 GOAL_EXISTS
PUT    /admin/goals/{code}         body { label, sortOrder?, active? (true) }  200 AdminGoal ; 404 GOAL_NOT_FOUND
DELETE /admin/goals/{code}         200 DeleteResult (used in profiles or saved excluded goals → DEACTIVATED) ; 404 GOAL_NOT_FOUND

### Catalog items and authors
Items are never deleted (set active = false; owned copies stay with users). code, kind, category and equipSlot are fixed after creation.
CatalogEntry = { code, kind, category, equipSlot, title, rarity, attributes, active, availableFrom, availableUntil,
                 authorId: uuid|null, authorName: string|null, createdAt, updatedAt }
Author       = { id, displayName, accountId: uuid|null, revenueSharePercent: 0..100, contactNote: string|null (private),
                 createdAt, updatedAt, itemCount }
GET  /admin/catalog/items?kind=&category=   (editors) 200 CatalogEntry[] (incl. inactive; by kind, category, code)
GET  /admin/catalog/items/{code}            (editors) 200 CatalogEntry ; 404 ITEM_NOT_FOUND
POST /admin/catalog/items                   (editors)
  body { code: ^[A-Z][A-Z0-9_]{1,49}$, kind: 'CONSUMABLE'|'COSMETIC', category: ^[A-Z][A-Z0-9_]{1,29}$, equipSlot?: 'PHOTO_FRAME'|
         'PROFILE_STATUS'|'CHAT_WALLPAPER' (default: the slot named like the category, if any), title (1..80), rarity?: 'COMMON'|'RARE'|
         'EPIC'|'LEGENDARY', attributes? ({}), active? (true), availableFrom?, availableUntil? (> availableFrom), authorId? }
  201 CatalogEntry ; 409 ITEM_EXISTS ; 400 VALIDATION_ERROR (consumables have no slot; slot categories only in their own slot;
  STICKER_PACK is not equipped; unknown rarity/author) | INVALID_ATTRIBUTES (only attributes.* errors, fieldErrors keyed "attributes.<key>")
  Attribute rules: consumables need icon (^[a-z0-9_-]{1,40}$); PHOTO_FRAME borderColor #RRGGBB, borderWidth 1..20, glow? boolean;
  PROFILE_STATUS emoji (1..16), label (1..30), color; CHAT_WALLPAPER background, accent, pattern? token; STICKER_PACK emoji,
  stickers: 1..100 token codes; imageUrl? must be "/api/v1/catalog/images/<uuid>"; stub? boolean; other categories: any object.
PUT  /admin/catalog/items/{code}            (editors) body { title, rarity?, attributes (required), active (required), availableFrom?,
                                            availableUntil?, authorId? } (full replace) 200 CatalogEntry ; 404 ITEM_NOT_FOUND ; 400 as above
POST /admin/catalog/items/{code}/image      (editors) multipart `file`: JPEG/PNG/WebP (by content) <= 5 MB, metadata stripped;
                                            sets attributes.imageUrl. 200 CatalogEntry ; 404 ITEM_NOT_FOUND ; 400 EMPTY_FILE ;
                                            413 FILE_TOO_LARGE ; 415 UNSUPPORTED_MEDIA_TYPE
GET    /admin/authors          (editors) 200 Author[] (by displayName)
POST   /admin/authors          (ADMIN) body { displayName (1..80), accountId?, revenueSharePercent? 0..100 (default app.authors.default-revenue-share-percent = 30),
                               contactNote? (<= 500, blank = none) }  201 Author ; 400 VALIDATION_ERROR (fieldErrors.accountId: unknown account)
PUT    /admin/authors/{id}     (ADMIN) same body, full replace (absent revenueSharePercent keeps the current one)  200 Author ; 404 AUTHOR_NOT_FOUND
DELETE /admin/authors/{id}     (ADMIN) 204 ; 404 AUTHOR_NOT_FOUND ; 409 AUTHOR_IN_USE (has catalog items)

### Achievements (editors) and premium composition (ADMIN)
AchievementDefinition = { code, title, description: string|null, rewards: Reward[], active, completionCount }
Reward = { type: 'ITEM'|'PREMIUM_DAYS', itemCode?, quantity?, days? }   (same as Achievement.rewards)
GET /admin/achievements           200 AchievementDefinition[]
PUT /admin/achievements/{code}    body { title (1..100), description? (<= 300, blank = none), rewards: Reward[] (required, <= 10), active (required) }
  ITEM: an active catalog item, quantity 1..1000 (cosmetics exactly 1); PREMIUM_DAYS: days 1..3650. Codes are fixed (no create/delete).
  200 AchievementDefinition ; 404 ACHIEVEMENT_NOT_FOUND ; 400 VALIDATION_ERROR (fieldErrors e.g. "rewards[0].quantity")
  A changed reward applies to completions from now on.
Composition = { features: string[], allowances: [ { itemCode, quantity, period: 'DAILY'|'WEEKLY'|'MONTHLY', mode: 'TOP_UP'|'ADD' } ],
                featuresOverridden: boolean, allowancesOverridden: boolean }   // false = value comes from config
PremiumView = { current: Composition, defaults: Composition (config values a reset returns to) }
GET    /admin/premium   200 PremiumView
PUT    /admin/premium   body { features?: string[] (<= 50, ^[A-Z][A-Z0-9_]{1,49}$, unique), allowances?: [ { itemCode (active consumable),
                        quantity 1..1000, period, mode } ] (<= 20, one per item+period) }   null/absent list = keep current
                        200 PremiumView ; 400 VALIDATION_ERROR (fieldErrors.features / "allowances[i].itemCode" …)
DELETE /admin/premium   back to app.economy.* config for both lists. 200 PremiumView
  Applies to Wallet.premiumFeatures and premium checks (in-memory cache, reloaded periodically on other instances).

## Testing: social (part of the Testing API: test accounts only, app.testing.enabled=true; else 403 TEST_ACCOUNT_REQUIRED)
Seed profiles cannot log in, so these act on their behalf. {seedAccountId} = SeedProfile.accountId; not an active seed → 404 SEED_PROFILE_NOT_FOUND.
POST   /testing/social/seed/{seedAccountId}/like-me   body { superlike?: boolean } (body optional)  the seed likes me (no feed checks,
                                                      no spending) 200 ReactionResult (matched if I already liked it)
POST   /testing/social/seed/{seedAccountId}/match     both like each other; an active match exists afterwards (a new one even if an
                                                      earlier one was closed) 200 { matched: true, matchId }
POST   /testing/social/matches/{matchId}/messages     the seed of my match writes to me. Body as POST /matches/{id}/messages, but
                                                      clientMessageId optional (generated) and type defaults to TEXT.
                                                      200 Message (as I see it: mine false) ; 404 MATCH_NOT_FOUND | SEED_PROFILE_NOT_FOUND
                                                      (other side is not a seed) ; chat errors as above
DELETE /testing/social/passes       200 { deleted: number }   forget my passes (profiles come back to my feed)
DELETE /testing/social/reactions    200 { deleted: number }   forget all my likes/super likes/passes (matches stay)
With app.testing.seed-auto-reply=true a seed answers each message sent to it with a canned TEXT after app.testing.seed-auto-reply-delay.

## Testing: roles (part of the Testing API: test accounts only, app.testing.enabled=true)
A test account gives itself a staff role to try the admin API. Audited as TESTING_GRANT / TESTING_REVOKE.
POST   /testing/roles/{role}    200 Account (as GET /me, with roles) ; 400 UNKNOWN_ROLE ; 403 TEST_ACCOUNT_REQUIRED
DELETE /testing/roles/{role}    200 Account ; 400 UNKNOWN_ROLE ; 403 TEST_ACCOUNT_REQUIRED   (no last-admin guard)
