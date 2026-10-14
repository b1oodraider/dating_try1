# Dating API contract v1 (source of truth = Java backend)

Base path: /api/v1. JSON, camelCase. Timestamps ISO-8601 UTC strings.
Auth: header `Authorization: Bearer <token>`.

## Errors
Every error body: { code: string, message: string (Russian, user-facing), traceId: string, fieldErrors?: { [field]: message } }
Response header `X-Trace-Id` on every response.
Status codes: 400 validation/business, 401 UNAUTHORIZED (missing/invalid/expired token, or account not ACTIVE),
403 FORBIDDEN, 404 *_NOT_FOUND, 405, 413/400 FILE_TOO_LARGE, 415 UNSUPPORTED_MEDIA_TYPE, 429 RATE_LIMITED (+ `Retry-After` seconds header), 503 OTP_DELIVERY_UNAVAILABLE, 500 INTERNAL_ERROR.

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
               createdAt, updatedAt }
PublicProfile = { id, displayName, age, city, aboutText: string|null, dailyStatusText: string|null, tags: Tag[], photos: Photo[] }
             (no accountId, no statuses)
Account    = { id, phoneMasked, status: 'ACTIVE'|'SUSPENDED'|'DELETED', createdAt }

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
  200 { items: PublicProfile[], page, limit, totalElements, totalPages }
