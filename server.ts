/**
 * DEV-ONLY in-memory mock of the Dating API v1 (docs/api-contract.md).
 *
 * Started by `npm run dev:mock`: serves /api/v1 from memory plus the SPA through Vite middleware.
 * Everything is lost on restart. OTP codes are NOT sent anywhere: they are returned as `devMockCode`
 * and printed to the console. The real backend is the Java service in backend/.
 *
 * Test accounts (MOCK_TEST_PHONES, default +7 900 000-00-01..03) log in with the fixed code
 * MOCK_TEST_OTP_CODE (default 123456), see only each other in discovery and may call /api/v1/testing.
 */
import 'dotenv/config';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import express, { type NextFunction, type Request, type Response } from 'express';
import multer from 'multer';

if (process.env.NODE_ENV === 'production') {
  console.error(
    '[dev-mock] server.ts is an in-memory development mock and must not run with NODE_ENV=production. ' +
      'Deploy the Java backend (backend/) and serve the built SPA (dist/) instead.'
  );
  process.exit(1);
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const OTP_TTL_MS = 5 * 60 * 1000;
const OTP_MAX_ATTEMPTS = 5;
const OTP_MIN_INTERVAL_MS = 60 * 1000;
const OTP_MAX_PER_HOUR = 5;
const HOUR_MS = 60 * 60 * 1000;
const SESSION_TTL_MS = 30 * 24 * HOUR_MS;
const DAILY_STATUS_TTL_MS = 24 * HOUR_MS;
const PHOTO_URL_TTL_S = 60 * 60;
const PHOTO_MAX_BYTES = 5 * 1024 * 1024;
const PHOTO_MAX_COUNT = 6;
const MAX_TAGS = 10;
const MAX_EXCLUSION_WORDS = 20;
const MAX_PROFILE_GOALS = 3;
const MAX_EXCLUDED_GOALS_REQUEST = 20;
const HEIGHT_MIN_CM = 120;
const HEIGHT_MAX_CM = 230;
const EXCLUSION_WORD_MIN_LENGTH = 2;
const EXCLUSION_WORD_MAX_LENGTH = 30;
const CLEANUP_INTERVAL_MS = 60 * 1000;

/** Per-process secrets: signatures and hashes become invalid after a restart (as does all data). */
const MEDIA_SIGNING_SECRET = crypto.randomBytes(32);
const HASH_PEPPER = crypto.randomBytes(32);

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

type AccountStatus = 'ACTIVE' | 'SUSPENDED' | 'DELETED';
type PublicationStatus = 'DRAFT' | 'PUBLISHED' | 'HIDDEN';
type TagCategory = 'pace' | 'format' | 'calls' | 'boundaries' | 'social';
type ImageMime = 'image/jpeg' | 'image/png' | 'image/webp';
type PublishField = 'displayName' | 'age' | 'city' | 'photos';

interface AccountRecord {
  id: string;
  phoneHash: string;
  phoneMasked: string;
  status: AccountStatus;
  createdAt: string;
  /** QA account (phone in the test list): isolated from real users in discovery, may use /testing. */
  testAccount: boolean;
}

interface SessionRecord {
  tokenHash: string;
  accountId: string;
  expiresAt: number;
}

interface ChallengeRecord {
  id: string;
  phoneHash: string;
  phoneMasked: string;
  codeHash: Buffer;
  expiresAt: number;
  attempts: number;
  usedAt: number | null;
}

interface ProfileRecord {
  id: string;
  accountId: string;
  displayName: string | null;
  age: number | null;
  city: string | null;
  aboutText: string | null;
  publicationStatus: PublicationStatus;
  dailyStatusText: string | null;
  dailyStatusExpiresAt: number | null;
  tagCodes: string[];
  heightCm: number | null;
  goalCodes: string[];
  createdAt: string;
  updatedAt: string;
}

interface PhotoRecord {
  id: string;
  accountId: string;
  data: Buffer;
  mimeType: ImageMime;
  sortOrder: number;
  isPrimary: boolean;
  createdAt: string;
}

interface TagDto {
  code: string;
  category: TagCategory;
  categoryLabel: string;
  label: string;
  description: string;
  sortOrder: number;
}

// ---------------------------------------------------------------------------
// Dictionary
// ---------------------------------------------------------------------------

const CATEGORY_LABELS: Record<TagCategory, string> = {
  pace: 'Темп переписки',
  format: 'Формат первой встречи',
  calls: 'Звонки и сообщения',
  boundaries: 'Комфорт и темы',
  social: 'Социальная среда',
};

function tag(code: string, category: TagCategory, label: string, description: string, sortOrder: number): TagDto {
  return { code, category, categoryLabel: CATEGORY_LABELS[category], label, description, sortOrder };
}

const TAGS: TagDto[] = [
  tag('PACE_ASYNCHRONOUS', 'pace', 'Асинхронный ответ', 'Отвечаю в течение дня без спешки и не жду мгновенных ответов', 1),
  tag('PACE_FAST_CHAT', 'pace', 'Люблю живой диалог', 'Когда свободен(-на), переписываюсь в моменте', 2),
  tag('PACE_LOW_BATTERY', 'pace', 'Иногда беру паузу', 'Могу на пару дней уйти в офлайн, это не значит потерю интереса', 3),
  tag('FORMAT_WALK_FIRST', 'format', 'Прогулка на воздухе', 'Спокойная прогулка по парку или городу без шума заведений', 10),
  tag('FORMAT_COFFEE_SHORT', 'format', 'Кофе на 30–40 минут', 'Короткая встреча без давления, чтобы познакомиться вживую', 11),
  tag('FORMAT_TEXT_BEFORE_MEET', 'format', 'Сначала переписка', 'Предпочитаю пообщаться несколько дней перед первой встречей', 12),
  tag('FORMAT_SHARED_ACTIVITY', 'format', 'Совместное занятие', 'Выставка, настолки или музей вместо сидения друг напротив друга', 13),
  tag('CALLS_TEXT_ONLY', 'calls', 'Только текст', 'Без неожиданных звонков и голосовых сообщений', 20),
  tag('CALLS_VOICE_WELCOME', 'calls', 'Голосовые ок', 'Удобно слушать и отправлять аудиосообщения', 21),
  tag('CALLS_PRE_CALL_CHECK', 'calls', 'Звонок по согласованию', 'Не против созвониться, если заранее договорились о времени', 22),
  tag('BOUND_NO_SMALLTALK', 'boundaries', 'Сразу к интересным темам', 'Меньше протокольных «как дела», больше разговоров о том, что вдохновляет', 30),
  tag('BOUND_RESPECT_LIMITS', 'boundaries', 'Без настойчивости', 'Ценю, когда уважают ответ «нет» или просьбу сменить тему', 31),
  tag('BOUND_DIRECT_COMMUNICATION', 'boundaries', 'Прямота без намеков', 'Предпочитаю ясные слова догадкам и полунамекам', 32),
  tag('SOC_QUIET_SPACES', 'social', 'Тихие места', 'Быстро утомляюсь в шумных толпах и клубах, выбираю уютные пространства', 40),
  tag('SOC_DEEP_TALKS', 'social', 'Глубокие разговоры', 'Интереснее обсудить идеи, книги, взгляды на мир, чем светскую суету', 41),
].sort((a, b) => a.sortOrder - b.sortOrder);

const TAGS_BY_CODE = new Map(TAGS.map((t) => [t.code, t]));

interface GoalDto {
  code: string;
  label: string;
  sortOrder: number;
}

/** Mirror of relationship_goals (migration 008). */
const GOALS: GoalDto[] = [
  { code: 'SERIOUS_RELATIONSHIP', label: 'Серьёзные отношения', sortOrder: 10 },
  { code: 'FAMILY', label: 'Создание семьи', sortOrder: 20 },
  { code: 'DATING', label: 'Свидания', sortOrder: 30 },
  { code: 'FRIENDSHIP', label: 'Дружба', sortOrder: 40 },
  { code: 'CHAT', label: 'Общение', sortOrder: 50 },
  { code: 'NOT_SURE', label: 'Пока не знаю', sortOrder: 60 },
];

const GOALS_BY_CODE = new Map(GOALS.map((g) => [g.code, g]));

// ---------------------------------------------------------------------------
// In-memory storage
// ---------------------------------------------------------------------------

const accounts = new Map<string, AccountRecord>(); // accountId -> account
const sessions = new Map<string, SessionRecord>(); // tokenHash -> session
const challenges = new Map<string, ChallengeRecord>(); // challengeId -> challenge
const profiles = new Map<string, ProfileRecord>(); // accountId -> profile
const photos = new Map<string, PhotoRecord>(); // photoId -> photo
const otpRequests = new Map<string, number[]>(); // phoneHash -> request timestamps (ms)
const exclusionWords = new Map<string, string[]>(); // accountId -> normalized words (private)
const excludedGoals = new Map<string, string[]>(); // accountId -> excluded goal codes (private)

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const nowIso = () => new Date().toISOString();

function keyedHash(value: string): string {
  return crypto.createHmac('sha256', HASH_PEPPER).update(value).digest('hex');
}

function sha256Hex(value: string): string {
  return crypto.createHash('sha256').update(value).digest('hex');
}

function safeEqual(a: Buffer, b: Buffer): boolean {
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

/** Strips formatting, maps 8XXXXXXXXXX -> 7XXXXXXXXXX and 10-digit numbers -> 7 + number. */
function normalizePhone(raw: string): string | null {
  let digits = raw.replace(/\D/g, '');
  if (digits.length === 11 && digits.startsWith('8')) {
    digits = '7' + digits.slice(1);
  } else if (digits.length === 10) {
    digits = '7' + digits;
  }
  if (digits.length < 11 || digits.length > 15) return null;
  return digits;
}

/** "+7 *** ***-**-67" */
function maskPhone(digits: string): string {
  const countryCode = digits.slice(0, digits.length - 10);
  return `+${countryCode} *** ***-**-${digits.slice(-2)}`;
}

class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly fieldErrors?: Record<string, string>,
    readonly retryAfterSeconds?: number,
    readonly details?: Record<string, unknown>
  ) {
    super(message);
  }
}

function sendError(res: Response, err: HttpError): void {
  if (err.retryAfterSeconds !== undefined) {
    res.setHeader('Retry-After', String(err.retryAfterSeconds));
  }
  res.status(err.status).json({
    code: err.code,
    message: err.message,
    traceId: res.locals.traceId as string,
    ...(err.fieldErrors ? { fieldErrors: err.fieldErrors } : {}),
    ...(err.details ? { details: err.details } : {}),
  });
}

type Handler = (req: Request, res: Response) => void | Promise<void>;

/** Wraps a handler so thrown HttpErrors become contract error bodies and anything else a 500. */
function route(handler: Handler) {
  return (req: Request, res: Response, next: NextFunction) => {
    Promise.resolve()
      .then(() => handler(req, res))
      .catch(next);
  };
}

const methodNotAllowed = route(() => {
  throw new HttpError(405, 'METHOD_NOT_ALLOWED', 'Метод не поддерживается');
});

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

// ---------------------------------------------------------------------------
// Images: type detection by magic bytes + metadata stripping
// ---------------------------------------------------------------------------

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

function detectImageType(buf: Buffer): ImageMime | null {
  if (buf.length >= 3 && buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) return 'image/jpeg';
  if (buf.length >= 8 && buf.subarray(0, 8).equals(PNG_SIGNATURE)) return 'image/png';
  if (buf.length >= 12 && buf.toString('latin1', 0, 4) === 'RIFF' && buf.toString('latin1', 8, 12) === 'WEBP') {
    return 'image/webp';
  }
  return null;
}

/** Drops APP1 (EXIF/XMP), APP3–APP13, APP15 and COM segments. Keeps JFIF, ICC (APP2), Adobe (APP14). */
function stripJpeg(buf: Buffer): Buffer | null {
  const parts: Buffer[] = [buf.subarray(0, 2)];
  let i = 2;
  while (i + 1 < buf.length) {
    if (buf[i] !== 0xff) return null;
    const marker = buf[i + 1];
    if (marker === 0xff) {
      i += 1; // fill byte
      continue;
    }
    if (marker === 0xda || marker === 0xd9) {
      parts.push(buf.subarray(i)); // start of scan / end of image: copy the rest verbatim
      return Buffer.concat(parts);
    }
    if ((marker >= 0xd0 && marker <= 0xd7) || marker === 0x01) {
      parts.push(buf.subarray(i, i + 2));
      i += 2;
      continue;
    }
    if (i + 4 > buf.length) return null;
    const length = buf.readUInt16BE(i + 2);
    const end = i + 2 + length;
    if (length < 2 || end > buf.length) return null;
    const isMetadata =
      marker === 0xe1 || (marker >= 0xe3 && marker <= 0xed) || marker === 0xef || marker === 0xfe;
    if (!isMetadata) parts.push(buf.subarray(i, end));
    i = end;
  }
  return null;
}

const PNG_METADATA_CHUNKS = new Set(['eXIf', 'tEXt', 'zTXt', 'iTXt', 'tIME']);

function stripPng(buf: Buffer): Buffer | null {
  const parts: Buffer[] = [buf.subarray(0, 8)];
  let i = 8;
  while (i + 12 <= buf.length) {
    const length = buf.readUInt32BE(i);
    const type = buf.toString('latin1', i + 4, i + 8);
    const end = i + 12 + length;
    if (end > buf.length) return null;
    if (!PNG_METADATA_CHUNKS.has(type)) parts.push(buf.subarray(i, end));
    i = end;
    if (type === 'IEND') return Buffer.concat(parts);
  }
  return null;
}

function stripWebp(buf: Buffer): Buffer | null {
  const chunks: Buffer[] = [];
  let i = 12;
  while (i + 8 <= buf.length) {
    const fourcc = buf.toString('latin1', i, i + 4);
    const size = buf.readUInt32LE(i + 4);
    const end = i + 8 + size + (size % 2);
    if (i + 8 + size > buf.length) return null;
    const chunk = Buffer.from(buf.subarray(i, Math.min(end, buf.length)));
    if (fourcc === 'VP8X' && size >= 1) {
      chunk[8] &= ~(0x08 | 0x04); // clear EXIF and XMP flags
    }
    if (fourcc !== 'EXIF' && fourcc !== 'XMP ') chunks.push(chunk);
    i = end;
  }
  if (chunks.length === 0) return null;
  const body = Buffer.concat(chunks);
  const header = Buffer.alloc(12);
  header.write('RIFF', 0, 'latin1');
  header.writeUInt32LE(body.length + 4, 4);
  header.write('WEBP', 8, 'latin1');
  return Buffer.concat([header, body]);
}

function stripMetadata(buf: Buffer, mime: ImageMime): Buffer | null {
  if (mime === 'image/jpeg') return stripJpeg(buf);
  if (mime === 'image/png') return stripPng(buf);
  return stripWebp(buf);
}

// --- Tiny PNG encoder for seed placeholders (no external image URLs in the mock) ---

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(buf: Buffer): number {
  let c = 0xffffffff;
  for (const byte of buf) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function pngChunk(type: string, data: Buffer): Buffer {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const typeAndData = Buffer.concat([Buffer.from(type, 'latin1'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(typeAndData));
  return Buffer.concat([length, typeAndData, crc]);
}

/** Vertical gradient placeholder, RGB 8-bit. */
function placeholderPng(width: number, height: number, from: number[], to: number[]): Buffer {
  const raw = Buffer.alloc((width * 3 + 1) * height);
  for (let y = 0; y < height; y++) {
    const t = y / (height - 1);
    const rowStart = y * (width * 3 + 1);
    raw[rowStart] = 0; // filter: none
    for (let x = 0; x < width; x++) {
      const vignette = 1 - 0.15 * Math.abs(x / (width - 1) - 0.5);
      for (let ch = 0; ch < 3; ch++) {
        raw[rowStart + 1 + x * 3 + ch] = Math.round((from[ch] + (to[ch] - from[ch]) * t) * vignette);
      }
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // colour type: truecolour
  return Buffer.concat([
    PNG_SIGNATURE,
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw)),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

// ---------------------------------------------------------------------------
// Signed media URLs
// ---------------------------------------------------------------------------

function signPhoto(photoId: string, exp: number): string {
  return crypto.createHmac('sha256', MEDIA_SIGNING_SECRET).update(`${photoId}.${exp}`).digest('hex');
}

function photoUrl(photoId: string): string {
  // Round the expiry to 10-minute buckets so URLs are stable (cacheable) for a while; valid ~1h.
  const bucket = 600;
  const exp = Math.ceil((Date.now() / 1000 + PHOTO_URL_TTL_S) / bucket) * bucket;
  return `/api/v1/media/photos/${encodeURIComponent(photoId)}?exp=${exp}&sig=${signPhoto(photoId, exp)}`;
}

function isValidPhotoSignature(photoId: string, expRaw: unknown, sigRaw: unknown): boolean {
  if (typeof expRaw !== 'string' || typeof sigRaw !== 'string') return false;
  if (!/^\d{1,12}$/.test(expRaw) || !/^[0-9a-f]{64}$/i.test(sigRaw)) return false;
  const exp = Number(expRaw);
  if (exp * 1000 <= Date.now()) return false;
  const expected = Buffer.from(signPhoto(photoId, exp), 'hex');
  return safeEqual(expected, Buffer.from(sigRaw, 'hex'));
}

// ---------------------------------------------------------------------------
// DTO mapping
// ---------------------------------------------------------------------------

function activePhotosOf(accountId: string): PhotoRecord[] {
  return [...photos.values()]
    .filter((p) => p.accountId === accountId)
    .sort((a, b) => a.sortOrder - b.sortOrder);
}

function toPhotoDto(p: PhotoRecord) {
  return {
    id: p.id,
    url: photoUrl(p.id),
    mimeType: p.mimeType,
    sizeBytes: p.data.length,
    sortOrder: p.sortOrder,
    isPrimary: p.isPrimary,
    createdAt: p.createdAt,
  };
}

function expireDailyStatus(p: ProfileRecord): void {
  if (p.dailyStatusExpiresAt !== null && p.dailyStatusExpiresAt <= Date.now()) {
    p.dailyStatusText = null;
    p.dailyStatusExpiresAt = null;
  }
}

function missingForPublish(p: ProfileRecord): PublishField[] {
  const missing: PublishField[] = [];
  if (!p.displayName) missing.push('displayName');
  if (p.age === null) missing.push('age');
  if (!p.city) missing.push('city');
  if (activePhotosOf(p.accountId).length === 0) missing.push('photos');
  return missing;
}

function completenessPercent(p: ProfileRecord): number {
  let score = 0;
  if (p.displayName) score += 20;
  if (p.age !== null) score += 15;
  if (p.city) score += 15;
  if (activePhotosOf(p.accountId).length > 0) score += 20;
  if (p.aboutText) score += 15;
  if (p.tagCodes.length > 0) score += 15;
  return score;
}

function tagsOf(p: ProfileRecord): TagDto[] {
  return p.tagCodes
    .map((code) => TAGS_BY_CODE.get(code))
    .filter((t): t is TagDto => t !== undefined)
    .sort((a, b) => a.sortOrder - b.sortOrder);
}

function goalsOf(codes: string[]): GoalDto[] {
  return codes
    .map((code) => GOALS_BY_CODE.get(code))
    .filter((g): g is GoalDto => g !== undefined)
    .sort((a, b) => a.sortOrder - b.sortOrder);
}

function toMyProfile(p: ProfileRecord) {
  expireDailyStatus(p);
  return {
    id: p.id,
    displayName: p.displayName,
    age: p.age,
    city: p.city,
    aboutText: p.aboutText,
    publicationStatus: p.publicationStatus,
    dailyStatusText: p.dailyStatusText,
    dailyStatusExpiresAt: p.dailyStatusExpiresAt === null ? null : new Date(p.dailyStatusExpiresAt).toISOString(),
    tags: tagsOf(p),
    photos: activePhotosOf(p.accountId).map(toPhotoDto),
    completenessPercent: completenessPercent(p),
    missingForPublish: missingForPublish(p),
    decorations: publicDecorations(p.accountId),
    heightCm: p.heightCm,
    goals: goalsOf(p.goalCodes),
    premium: isPremium(p.accountId),
    createdAt: p.createdAt,
    updatedAt: p.updatedAt,
  };
}

function toPublicProfile(p: ProfileRecord) {
  expireDailyStatus(p);
  return {
    id: p.id,
    displayName: p.displayName,
    age: p.age,
    city: p.city,
    aboutText: p.aboutText,
    dailyStatusText: p.dailyStatusText,
    tags: tagsOf(p),
    photos: activePhotosOf(p.accountId).map(toPhotoDto),
    decorations: publicDecorations(p.accountId),
    heightCm: p.heightCm,
    goals: goalsOf(p.goalCodes),
    premium: isPremium(p.accountId),
  };
}

function toAccountDto(a: AccountRecord) {
  return { id: a.id, phoneMasked: a.phoneMasked, status: a.status, createdAt: a.createdAt, testAccount: a.testAccount };
}

function getOrCreateProfile(accountId: string): ProfileRecord {
  let profile = profiles.get(accountId);
  if (!profile) {
    const ts = nowIso();
    profile = {
      id: crypto.randomUUID(),
      accountId,
      displayName: null,
      age: null,
      city: null,
      aboutText: null,
      publicationStatus: 'DRAFT',
      dailyStatusText: null,
      dailyStatusExpiresAt: null,
      tagCodes: [],
      heightCm: null,
      goalCodes: [],
      createdAt: ts,
      updatedAt: ts,
    };
    profiles.set(accountId, profile);
  }
  return profile;
}

// ---------------------------------------------------------------------------
// Test accounts (mirror of app.testing.* in the Java backend, dev profile)
// ---------------------------------------------------------------------------

/** Phones that become test accounts. Override with MOCK_TEST_PHONES="+7 900 000-00-01, ..." ("" = none). */
const TEST_PHONES_RAW =
  process.env.MOCK_TEST_PHONES ?? '+7 900 000-00-01, +7 900 000-00-02, +7 900 000-00-03';
/** Fixed OTP code for test phones (no SMS). */
const TEST_OTP_CODE = process.env.MOCK_TEST_OTP_CODE ?? '123456';
if (!/^\d{6}$/.test(TEST_OTP_CODE)) {
  console.error('[dev-mock] MOCK_TEST_OTP_CODE must be 6 digits');
  process.exit(1);
}

const TEST_PHONES: string[] = TEST_PHONES_RAW.split(',')
  .map((raw) => raw.trim())
  .filter(Boolean)
  .map((raw) => {
    const phone = normalizePhone(raw);
    if (!phone) {
      console.error(`[dev-mock] MOCK_TEST_PHONES: invalid phone ${raw}`);
      process.exit(1);
    }
    return phone;
  });
const TEST_PHONE_HASHES = new Set(TEST_PHONES.map(keyedHash));

const isTestPhoneHash = (phoneHash: string): boolean => TEST_PHONE_HASHES.has(phoneHash);

// ---------------------------------------------------------------------------
// Economy: catalog, wallet buckets, premium, allowances, cosmetics, achievements
// (mirror of migration 007 + EconomyService / AllowanceService / CosmeticsService / AchievementService)
// ---------------------------------------------------------------------------

const DAY_MS = 24 * HOUR_MS;
const MAX_TESTING_QUANTITY = 100_000;
const MAX_TESTING_DAYS = 3650;

type ItemKind = 'CONSUMABLE' | 'COSMETIC';
type Rarity = 'COMMON' | 'RARE' | 'EPIC' | 'LEGENDARY';
type Bucket = 'PERMANENT' | 'PERIODIC';
type CosmeticSlot = 'PHOTO_FRAME' | 'PROFILE_STATUS' | 'CHAT_WALLPAPER';

interface CatalogItem {
  code: string;
  kind: ItemKind;
  category: string;
  equipSlot: CosmeticSlot | null;
  title: string;
  rarity: Rarity | null;
  attributes: Record<string, unknown>;
}

/** Slot -> visible to other users (PublicProfile.decorations). */
const COSMETIC_SLOTS: Record<CosmeticSlot, boolean> = {
  PHOTO_FRAME: true,
  PROFILE_STATUS: true,
  CHAT_WALLPAPER: false,
};
const ALL_SLOTS = Object.keys(COSMETIC_SLOTS) as CosmeticSlot[];
const PUBLIC_SLOTS = ALL_SLOTS.filter((s) => COSMETIC_SLOTS[s]);

function catalogItem(
  code: string,
  kind: ItemKind,
  category: string,
  equipSlot: CosmeticSlot | null,
  title: string,
  rarity: Rarity | null,
  attributes: Record<string, unknown>
): CatalogItem {
  return { code, kind, category, equipSlot, title, rarity, attributes: { stub: true, ...attributes } };
}

/** Stub catalog: same codes, titles and attributes as changeset 007-stub-catalog-and-achievements. */
const CATALOG: CatalogItem[] = [
  catalogItem('SUPERLIKE', 'CONSUMABLE', 'SUPERLIKE', null, 'Суперлайк', null, { icon: 'star' }),
  catalogItem('GACHA_TICKET', 'CONSUMABLE', 'GACHA_TICKET', null, 'Билет удачи', null, { icon: 'ticket' }),
  catalogItem('BOOST', 'CONSUMABLE', 'BOOST', null, 'Буст анкеты', null, { icon: 'rocket', durationMinutes: 30 }),
  catalogItem('STICKERS_STUB_CATS', 'COSMETIC', 'STICKER_PACK', null, 'Котики (тест)', 'COMMON',
    { emoji: '🐱', stickers: ['cat_wave', 'cat_heart', 'cat_sleep'] }),
  catalogItem('STICKERS_STUB_SPACE', 'COSMETIC', 'STICKER_PACK', null, 'Космос (тест)', 'RARE',
    { emoji: '🚀', stickers: ['rocket', 'planet', 'alien'] }),
  catalogItem('WALLPAPER_STUB_NIGHT', 'COSMETIC', 'CHAT_WALLPAPER', 'CHAT_WALLPAPER', 'Ночной город (тест)', 'COMMON',
    { background: '#1E1B4B', accent: '#A5B4FC', pattern: 'stars' }),
  catalogItem('WALLPAPER_STUB_SUNSET', 'COSMETIC', 'CHAT_WALLPAPER', 'CHAT_WALLPAPER', 'Закат (тест)', 'RARE',
    { background: '#FDBA74', accent: '#DB2777', pattern: 'waves' }),
  catalogItem('STATUS_STUB_EARLY_BIRD', 'COSMETIC', 'PROFILE_STATUS', 'PROFILE_STATUS', 'Ранняя пташка (тест)', 'RARE',
    { emoji: '🐦', label: 'Ранняя пташка', color: '#0EA5E9' }),
  catalogItem('STATUS_STUB_BOOKWORM', 'COSMETIC', 'PROFILE_STATUS', 'PROFILE_STATUS', 'Книжный червь (тест)', 'EPIC',
    { emoji: '📚', label: 'Книжный червь', color: '#7C3AED' }),
  catalogItem('FRAME_STUB_GOLD', 'COSMETIC', 'PHOTO_FRAME', 'PHOTO_FRAME', 'Золотая рамка (тест)', 'EPIC',
    { borderColor: '#D4AF37', borderWidth: 4 }),
  catalogItem('FRAME_STUB_NEON', 'COSMETIC', 'PHOTO_FRAME', 'PHOTO_FRAME', 'Неоновая рамка (тест)', 'LEGENDARY',
    { borderColor: '#22D3EE', borderWidth: 3, glow: true }),
];
const CATALOG_BY_CODE = new Map(CATALOG.map((i) => [i.code, i]));

type Reward = { kind: 'item'; itemCode: string; quantity: number } | { kind: 'premium'; days: number };

interface AchievementDefinition {
  code: string;
  title: string;
  description: string;
  rewards: Reward[];
}

/** Same definitions and rewards as changeset 007; listed by code like the backend. */
const ACHIEVEMENTS: AchievementDefinition[] = [
  {
    code: 'STUB_FIRST_PHOTO',
    title: 'Первое фото (тест)',
    description: 'Загрузить первую фотографию',
    rewards: [{ kind: 'item', itemCode: 'SUPERLIKE', quantity: 1 }],
  },
  {
    code: 'STUB_PROFILE_PUBLISHED',
    title: 'Анкета опубликована (тест)',
    description: 'Опубликовать анкету',
    rewards: [{ kind: 'item', itemCode: 'FRAME_STUB_GOLD', quantity: 1 }, { kind: 'premium', days: 3 }],
  },
  {
    code: 'STUB_COLLECTOR',
    title: 'Коллекционер (тест)',
    description: 'Набор косметики для проверки витрины',
    rewards: [
      { kind: 'item', itemCode: 'GACHA_TICKET', quantity: 2 },
      { kind: 'item', itemCode: 'STATUS_STUB_EARLY_BIRD', quantity: 1 },
      { kind: 'item', itemCode: 'STICKERS_STUB_CATS', quantity: 1 },
      { kind: 'item', itemCode: 'WALLPAPER_STUB_NIGHT', quantity: 1 },
    ],
  },
];
ACHIEVEMENTS.sort((a, b) => (a.code < b.code ? -1 : a.code > b.code ? 1 : 0));
const ACHIEVEMENTS_BY_CODE = new Map(ACHIEVEMENTS.map((a) => [a.code, a]));

/** app.economy.premium-features (a feature not listed here is free). */
const PREMIUM_FEATURES: string[] = ['ADVANCED_FILTERS', 'EXCLUSION_WORDS'];

/** app.economy.premium-allowances (STUB values). WEEKLY = ISO week of the account's economy time (UTC). */
const PREMIUM_ALLOWANCES: { itemCode: string; quantity: number; period: 'WEEKLY'; mode: 'TOP_UP' | 'ADD' }[] = [
  { itemCode: 'SUPERLIKE', quantity: 5, period: 'WEEKLY', mode: 'TOP_UP' },
  { itemCode: 'GACHA_TICKET', quantity: 1, period: 'WEEKLY', mode: 'ADD' },
];

interface EconomyRecord {
  /** itemCode -> bucket -> quantity */
  balances: Map<string, Record<Bucket, number>>;
  premiumUntil: number | null;
  /** slot -> itemCode */
  equipped: Map<CosmeticSlot, string>;
  clockOffsetMs: number;
  /** Idempotency keys of applied grants (allowance periods, achievement rewards). */
  appliedKeys: Set<string>;
  /** achievementCode -> completedAt (real time, like DB now()) */
  achievements: Map<string, string>;
}

const economies = new Map<string, EconomyRecord>(); // accountId -> economy state

function economyOf(accountId: string): EconomyRecord {
  let e = economies.get(accountId);
  if (!e) {
    e = {
      balances: new Map(),
      premiumUntil: null,
      equipped: new Map(),
      clockOffsetMs: 0,
      appliedKeys: new Set(),
      achievements: new Map(),
    };
    economies.set(accountId, e);
  }
  return e;
}

/** "Now" for premium expiry and allowance periods of this account (test accounts may be shifted). */
function economyNow(accountId: string): number {
  return Date.now() + (economies.get(accountId)?.clockOffsetMs ?? 0);
}

/** ISO-8601 week key like "2026-W42" (UTC), same as AllowancePeriod.WEEKLY.keyFor. */
function isoWeekKey(epochMs: number): string {
  const d = new Date(epochMs);
  const date = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));
  const dayOfWeek = date.getUTCDay() || 7; // Mon=1 .. Sun=7
  date.setUTCDate(date.getUTCDate() + 4 - dayOfWeek); // the Thursday of this week decides the year
  const weekYear = date.getUTCFullYear();
  const week = Math.ceil(((date.getTime() - Date.UTC(weekYear, 0, 1)) / DAY_MS + 1) / 7);
  return `${weekYear}-W${String(week).padStart(2, '0')}`;
}

function requireItem(itemCode: string): CatalogItem {
  const item = CATALOG_BY_CODE.get(itemCode);
  if (!item) throw new HttpError(404, 'ITEM_NOT_FOUND', `Предмет не найден: ${itemCode}`);
  return item;
}

function bucketsOf(e: EconomyRecord, itemCode: string): Record<Bucket, number> {
  let b = e.balances.get(itemCode);
  if (!b) {
    b = { PERMANENT: 0, PERIODIC: 0 };
    e.balances.set(itemCode, b);
  }
  return b;
}

function totalBalance(e: EconomyRecord, itemCode: string): number {
  const b = e.balances.get(itemCode);
  return b ? b.PERMANENT + b.PERIODIC : 0;
}

function isPremium(accountId: string): boolean {
  const until = economies.get(accountId)?.premiumUntil ?? null;
  return until !== null && until > economyNow(accountId);
}

/** FeatureAccess.isAllowed: the feature is free (not in PREMIUM_FEATURES) or the account has premium now. */
function isFeatureAllowed(accountId: string, feature: string): boolean {
  return !PREMIUM_FEATURES.includes(feature) || isPremium(accountId);
}

/** FeatureAccess.require: 403 PREMIUM_REQUIRED with details.feature. */
function requireFeature(accountId: string, feature: string): void {
  if (!isFeatureAllowed(accountId, feature)) {
    throw new HttpError(403, 'PREMIUM_REQUIRED', 'Эта функция доступна с премиумом', undefined, undefined, { feature });
  }
}

/**
 * Applies rewards once per idempotency key (null = always). Consumables go to PERMANENT; cosmetics
 * become owned (an owned one stays at 1); premium days extend from max(premiumUntil, economy now).
 * Items are validated first, so an unknown code changes nothing.
 */
function grantRewards(accountId: string, rewards: Reward[], idempotencyKey: string | null): boolean {
  for (const r of rewards) if (r.kind === 'item') requireItem(r.itemCode);
  const e = economyOf(accountId);
  if (idempotencyKey !== null) {
    if (e.appliedKeys.has(idempotencyKey)) return false;
    e.appliedKeys.add(idempotencyKey);
  }
  for (const reward of rewards) {
    if (reward.kind === 'premium') {
      const now = economyNow(accountId);
      e.premiumUntil = Math.max(e.premiumUntil ?? now, now) + reward.days * DAY_MS;
      continue;
    }
    const item = requireItem(reward.itemCode);
    const buckets = bucketsOf(e, item.code);
    if (item.kind === 'CONSUMABLE') buckets.PERMANENT += reward.quantity;
    else if (buckets.PERMANENT === 0) buckets.PERMANENT = 1;
  }
  return true;
}

/** Grants due premium allowances into PERIODIC, at most once per item and period. */
function ensureAllowances(accountId: string): void {
  if (!isPremium(accountId)) return;
  const e = economyOf(accountId);
  const periodKey = isoWeekKey(economyNow(accountId));
  for (const allowance of PREMIUM_ALLOWANCES) {
    const key = `allowance:${allowance.itemCode}:${allowance.period}:${periodKey}`;
    if (e.appliedKeys.has(key)) continue;
    e.appliedKeys.add(key);
    const buckets = bucketsOf(e, requireItem(allowance.itemCode).code);
    // TOP_UP refills the periodic units up to quantity (bought units are not counted); ADD adds.
    buckets.PERIODIC +=
      allowance.mode === 'ADD' ? allowance.quantity : Math.max(0, allowance.quantity - buckets.PERIODIC);
  }
}

function compareStrings(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/** GET /me/wallet body. Grants due allowances first (like WalletQueryService). */
function toWalletDto(accountId: string) {
  ensureAllowances(accountId);
  const e = economyOf(accountId);
  const until = e.premiumUntil;
  const items = [...e.balances.entries()]
    .map(([code, b]) => ({ item: requireItem(code), quantity: b.PERMANENT + b.PERIODIC, periodic: b.PERIODIC }))
    .filter((row) => row.quantity > 0)
    // ORDER BY kind, category, code: CONSUMABLE < COSMETIC, so consumables come first.
    .sort(
      (a, b) =>
        compareStrings(a.item.kind, b.item.kind) ||
        compareStrings(a.item.category, b.item.category) ||
        compareStrings(a.item.code, b.item.code)
    )
    .map(({ item, quantity, periodic }) => ({
      code: item.code,
      kind: item.kind,
      category: item.category,
      title: item.title,
      rarity: item.rarity,
      equipSlot: item.equipSlot,
      attributes: item.attributes,
      quantity,
      periodicQuantity: periodic,
    }));
  const equipped = Object.fromEntries([...e.equipped.entries()].sort(([a], [b]) => compareStrings(a, b)));
  return {
    premium: {
      active: until !== null && until > economyNow(accountId),
      until: until === null ? null : new Date(until).toISOString(),
    },
    premiumFeatures: PREMIUM_FEATURES,
    items,
    equipped,
  };
}

interface DecorationDto {
  code: string;
  title: string;
  rarity: Rarity | null;
  attributes: Record<string, unknown>;
}

/** Equipped, still owned cosmetics in public slots: what other users see on the profile. */
function publicDecorations(accountId: string): Record<string, DecorationDto> {
  const result: Record<string, DecorationDto> = {};
  const e = economies.get(accountId);
  if (!e) return result;
  for (const slot of PUBLIC_SLOTS) {
    const code = e.equipped.get(slot);
    const item = code ? CATALOG_BY_CODE.get(code) : undefined;
    if (!item || totalBalance(e, item.code) <= 0) continue;
    result[slot] = { code: item.code, title: item.title, rarity: item.rarity, attributes: item.attributes };
  }
  return result;
}

function parseSlot(raw: string): CosmeticSlot {
  const slot = ALL_SLOTS.find((s) => s === raw.toUpperCase());
  if (!slot) throw new HttpError(400, 'UNKNOWN_SLOT', `Неизвестный слот: ${raw}`);
  return slot;
}

function toAchievementDtos(accountId: string) {
  const completions = economies.get(accountId)?.achievements ?? new Map<string, string>();
  return ACHIEVEMENTS.map((a) => ({
    code: a.code,
    title: a.title,
    description: a.description,
    rewards: a.rewards.map((r) =>
      r.kind === 'item'
        ? { type: 'ITEM', itemCode: r.itemCode, quantity: r.quantity, days: null }
        : { type: 'PREMIUM_DAYS', itemCode: null, quantity: null, days: r.days }
    ),
    completed: completions.has(a.code),
    completedAt: completions.get(a.code) ?? null,
  }));
}

const achievementRewardKey = (code: string) => `achievement:${code}`;

// ---------------------------------------------------------------------------
// Seed data: a few PUBLISHED demo profiles with generated placeholder photos
// ---------------------------------------------------------------------------

interface SeedProfile {
  name: string;
  age: number;
  city: string;
  colors: number[][];
  photoCount: number;
  height: number | null;
  goals: string[];
  premium: boolean;
  about: string;
  tags: string[];
  daily: string | null;
}

function seed(): void {
  const demo: SeedProfile[] = [
    { name: 'Анна', age: 26, city: 'Москва', colors: [[244, 114, 182], [251, 191, 36]], photoCount: 2,
      height: 168, goals: ['SERIOUS_RELATIONSHIP', 'FAMILY'], premium: true,
      about: 'Архитектор городской среды. Люблю долгие пешие прогулки по переулкам, кофе без сиропа и плёночную фотографию.',
      tags: ['PACE_ASYNCHRONOUS', 'FORMAT_WALK_FIRST', 'CALLS_TEXT_ONLY', 'BOUND_DIRECT_COMMUNICATION', 'SOC_QUIET_SPACES'],
      daily: 'В субботу иду на выставку современной графики' },
    { name: 'Михаил', age: 29, city: 'Санкт-Петербург', colors: [[56, 189, 248], [30, 64, 175]], photoCount: 1,
      height: 183, goals: ['DATING', 'CHAT'], premium: false,
      about: 'Разрабатываю инди-игры, читаю научпоп и играю на акустической гитаре. Ищу человека, с которым комфортно молчать и обсуждать книги.',
      tags: ['PACE_LOW_BATTERY', 'FORMAT_COFFEE_SHORT', 'CALLS_TEXT_ONLY', 'SOC_DEEP_TALKS'], daily: null },
    { name: 'Елена', age: 24, city: 'Москва', colors: [[167, 243, 208], [5, 150, 105]], photoCount: 1,
      height: 162, goals: ['FRIENDSHIP', 'CHAT'], premium: false,
      about: 'Книжный редактор и любительница керамики. Рада неспешному знакомству и общению на равных.',
      tags: ['PACE_ASYNCHRONOUS', 'FORMAT_TEXT_BEFORE_MEET', 'BOUND_NO_SMALLTALK', 'SOC_QUIET_SPACES'],
      daily: 'Заварила зелёный чай, читаю новую главу' },
    { name: 'Дмитрий', age: 31, city: 'Екатеринбург', colors: [[196, 181, 253], [91, 33, 182]], photoCount: 1,
      height: 190, goals: ['SERIOUS_RELATIONSHIP'], premium: true,
      about: 'Биофизик, увлекаюсь треккингом на Урале и астрономией. Предпочитаю сначала содержательно поговорить в чате.',
      tags: ['PACE_FAST_CHAT', 'FORMAT_SHARED_ACTIVITY', 'CALLS_PRE_CALL_CHECK', 'BOUND_DIRECT_COMMUNICATION'], daily: null },
    { name: 'София', age: 27, city: 'Казань', colors: [[253, 186, 116], [194, 65, 12]], photoCount: 1,
      height: null, goals: ['NOT_SURE'], premium: false,
      about: 'Преподаватель лингвистики, фанат чая и настольных стратегий. Легко общаюсь текстом и голосовыми.',
      tags: ['PACE_FAST_CHAT', 'FORMAT_SHARED_ACTIVITY', 'CALLS_VOICE_WELCOME', 'SOC_DEEP_TALKS'],
      daily: 'Собираю команду на партию в Catan в эти выходные' },
  ];

  // Test accounts get their own PUBLISHED profiles: they see only each other in discovery
  // (MOCK ONLY convenience; the real backend does not seed them).
  const testDemo: SeedProfile[] = [
    { name: 'Тестер Один', age: 30, city: 'Москва', colors: [[148, 163, 184], [51, 65, 85]], photoCount: 1,
      height: 176, goals: ['SERIOUS_RELATIONSHIP'], premium: false,
      about: 'Тестовый аккаунт №1 для проверки экономики и косметики.', tags: ['PACE_FAST_CHAT', 'FORMAT_WALK_FIRST'],
      daily: null },
    { name: 'Тестер Два', age: 28, city: 'Москва', colors: [[252, 165, 165], [153, 27, 27]], photoCount: 1,
      height: 165, goals: ['DATING', 'FRIENDSHIP'], premium: false,
      about: 'Тестовый аккаунт №2: смотрю ленту других тестеров.', tags: ['PACE_ASYNCHRONOUS', 'SOC_DEEP_TALKS'],
      daily: null },
    { name: 'Тестер Три', age: 35, city: 'Казань', colors: [[134, 239, 172], [22, 101, 52]], photoCount: 1,
      height: 182, goals: ['SERIOUS_RELATIONSHIP', 'FAMILY'], premium: true,
      about: 'Тестовый аккаунт №3.', tags: ['CALLS_TEXT_ONLY'], daily: null },
  ];

  // Extra PUBLISHED test-only profiles (fictitious phones, not test phones: logging in with one of them
  // gives a regular account) so test accounts have a richer feed for advanced filters (MOCK ONLY).
  const testExtras: SeedProfile[] = [
    { name: 'Тестер Четыре', age: 26, city: 'Москва', colors: [[147, 197, 253], [30, 58, 138]], photoCount: 1,
      height: null, goals: ['CHAT'], premium: false,
      about: 'Тестовая анкета без роста.', tags: ['PACE_LOW_BATTERY'], daily: null },
    { name: 'Тестер Пять', age: 33, city: 'Санкт-Петербург', colors: [[253, 224, 71], [161, 98, 7]], photoCount: 1,
      height: 194, goals: ['NOT_SURE', 'DATING'], premium: false,
      about: 'Тестовая анкета: высокий рост.', tags: ['SOC_QUIET_SPACES'], daily: null },
  ];

  const seedAccount = (phone: string, d: SeedProfile, testAccount: boolean) => {
    const accountId = crypto.randomUUID();
    const ts = nowIso();
    accounts.set(accountId, {
      id: accountId,
      phoneHash: keyedHash(phone),
      phoneMasked: maskPhone(phone),
      status: 'ACTIVE',
      createdAt: ts,
      testAccount,
    });
    profiles.set(accountId, {
      id: crypto.randomUUID(),
      accountId,
      displayName: d.name,
      age: d.age,
      city: d.city,
      aboutText: d.about,
      publicationStatus: 'PUBLISHED',
      dailyStatusText: d.daily,
      dailyStatusExpiresAt: d.daily ? Date.now() + DAILY_STATUS_TTL_MS : null,
      tagCodes: d.tags,
      heightCm: d.height,
      goalCodes: d.goals,
      createdAt: ts,
      updatedAt: ts,
    });
    // Premium badge in the feed (MOCK ONLY seed: a year of premium).
    if (d.premium) economyOf(accountId).premiumUntil = Date.now() + 365 * DAY_MS;
    for (let n = 0; n < d.photoCount; n++) {
      const [from, to] = n === 0 ? d.colors : [d.colors[1], d.colors[0]];
      const id = crypto.randomUUID();
      photos.set(id, {
        id,
        accountId,
        data: placeholderPng(240, 300, from, to),
        mimeType: 'image/png',
        sortOrder: n,
        isPrimary: n === 0,
        createdAt: ts,
      });
    }
  };

  // Fictitious numbers +7 999 000-00-0X: logging in with one of them opens the demo account.
  demo.forEach((d, i) => seedAccount(`7999000000${i}`, d, isTestPhoneHash(keyedHash(`7999000000${i}`))));
  TEST_PHONES.forEach((phone, i) => {
    if ([...accounts.values()].some((a) => a.phoneHash === keyedHash(phone))) return;
    seedAccount(phone, testDemo[i % testDemo.length], true);
  });
  testExtras.forEach((d, i) => seedAccount(`7900000009${i}`, d, true));
}

seed();

// ---------------------------------------------------------------------------
// Auth middleware
// ---------------------------------------------------------------------------

interface AuthContext {
  accountId: string;
  tokenHash: string;
}

function auth(req: Request, res: Response, next: NextFunction): void {
  const header = req.headers.authorization;
  const token = header?.startsWith('Bearer ') ? header.slice(7).trim() : '';
  if (!token) {
    sendError(res, new HttpError(401, 'UNAUTHORIZED', 'Требуется авторизация'));
    return;
  }
  const tokenHash = sha256Hex(token);
  const session = sessions.get(tokenHash);
  if (!session || session.expiresAt <= Date.now()) {
    if (session) sessions.delete(tokenHash);
    sendError(res, new HttpError(401, 'UNAUTHORIZED', 'Сессия истекла или недействительна. Войдите снова.'));
    return;
  }
  const account = accounts.get(session.accountId);
  if (!account || account.status !== 'ACTIVE') {
    sendError(res, new HttpError(401, 'UNAUTHORIZED', 'Аккаунт недоступен'));
    return;
  }
  res.locals.auth = { accountId: account.id, tokenHash } satisfies AuthContext;
  next();
}

const authOf = (res: Response): AuthContext => res.locals.auth as AuthContext;

// ---------------------------------------------------------------------------
// Validation of PUT /me/profile
// ---------------------------------------------------------------------------

interface ProfilePatch {
  displayName?: string;
  age?: number;
  city?: string;
  aboutText?: string | null;
  tagCodes?: string[];
  dailyStatusText?: string | null;
  heightCm?: number;
  clearHeight?: boolean;
  goalCodes?: string[];
}

function validateProfileUpdate(body: unknown): ProfilePatch {
  if (!isPlainObject(body)) {
    throw new HttpError(400, 'VALIDATION_ERROR', 'Тело запроса должно быть JSON-объектом');
  }
  const errors: Record<string, string> = {};
  const patch: ProfilePatch = {};

  if (body.displayName !== undefined) {
    if (typeof body.displayName !== 'string') {
      errors.displayName = 'Имя должно быть строкой';
    } else {
      const v = body.displayName.trim();
      if (v.length < 2 || v.length > 50) errors.displayName = 'Имя должно содержать от 2 до 50 символов';
      else patch.displayName = v;
    }
  }

  if (body.age !== undefined) {
    if (typeof body.age !== 'number' || !Number.isInteger(body.age) || body.age < 18 || body.age > 100) {
      errors.age = 'Возраст должен быть целым числом от 18 до 100';
    } else {
      patch.age = body.age;
    }
  }

  if (body.city !== undefined) {
    if (typeof body.city !== 'string') {
      errors.city = 'Город должен быть строкой';
    } else {
      const v = body.city.trim();
      if (v.length < 1 || v.length > 60) errors.city = 'Город должен содержать от 1 до 60 символов';
      else patch.city = v;
    }
  }

  if (body.aboutText !== undefined) {
    if (typeof body.aboutText !== 'string') {
      errors.aboutText = 'Текст «О себе» должен быть строкой';
    } else {
      const v = body.aboutText.trim();
      if (v.length > 1000) errors.aboutText = 'Текст «О себе» — не длиннее 1000 символов';
      else patch.aboutText = v === '' ? null : v;
    }
  }

  let unknownTags: string[] = [];
  if (body.tagCodes !== undefined) {
    if (!Array.isArray(body.tagCodes) || !body.tagCodes.every((c): c is string => typeof c === 'string')) {
      errors.tagCodes = 'tagCodes должен быть массивом строк';
    } else {
      const codes = [...new Set(body.tagCodes.map((c) => c.trim()))];
      if (codes.length > MAX_TAGS) {
        errors.tagCodes = `Можно выбрать не больше ${MAX_TAGS} карточек`;
      } else {
        unknownTags = codes.filter((c) => !TAGS_BY_CODE.has(c));
        patch.tagCodes = codes;
      }
    }
  }

  if (body.dailyStatusText !== undefined) {
    if (typeof body.dailyStatusText !== 'string') {
      errors.dailyStatusText = 'Статус должен быть строкой';
    } else {
      const v = body.dailyStatusText.trim();
      if (v.length > 200) errors.dailyStatusText = 'Статус — не длиннее 200 символов';
      else patch.dailyStatusText = v === '' ? null : v;
    }
  }

  // null = unchanged (like the Java DTO): the height cannot be cleared.
  if (body.heightCm !== undefined && body.heightCm !== null) {
    if (typeof body.heightCm !== 'number' || !Number.isInteger(body.heightCm)) {
      errors.heightCm = 'Рост должен быть целым числом';
    } else if (body.heightCm < HEIGHT_MIN_CM || body.heightCm > HEIGHT_MAX_CM) {
      errors.heightCm = `Рост должен быть от ${HEIGHT_MIN_CM} до ${HEIGHT_MAX_CM} см`;
    } else {
      patch.heightCm = body.heightCm;
    }
  }
  // clearHeight: true removes the height; together with a heightCm it is a contradiction (ProfileService).
  if (body.clearHeight !== undefined && body.clearHeight !== null) {
    if (typeof body.clearHeight !== 'boolean') {
      errors.clearHeight = 'clearHeight должен быть true или false';
    } else if (body.clearHeight) {
      if (body.heightCm !== undefined && body.heightCm !== null) {
        errors.heightCm = 'Нельзя одновременно указать и удалить рост';
      } else {
        patch.clearHeight = true;
      }
    }
  }

  let unknownGoals: string[] = [];
  if (body.goalCodes !== undefined && body.goalCodes !== null) {
    if (!Array.isArray(body.goalCodes) || !body.goalCodes.every((c): c is string => typeof c === 'string')) {
      errors.goalCodes = 'goalCodes должен быть массивом строк';
    } else if (body.goalCodes.length > MAX_PROFILE_GOALS) {
      errors.goalCodes = `Можно выбрать не более ${MAX_PROFILE_GOALS} целей`;
    } else {
      const codes = [...new Set(body.goalCodes.map((c) => c.trim()).filter(Boolean))];
      unknownGoals = codes.filter((c) => !GOALS_BY_CODE.has(c));
      patch.goalCodes = codes;
    }
  }

  if (Object.keys(errors).length > 0) {
    throw new HttpError(400, 'VALIDATION_ERROR', 'Проверьте правильность заполнения полей', errors);
  }
  // Like ProfileService: unknown tags and goals are reported together; UNKNOWN_TAG wins over UNKNOWN_GOAL.
  const unknownErrors: Record<string, string> = {};
  if (unknownTags.length > 0) unknownErrors.tagCodes = `Неизвестные карточки: ${unknownTags.join(', ')}`;
  if (unknownGoals.length > 0) unknownErrors.goalCodes = `Неизвестные цели: ${unknownGoals.join(', ')}`;
  if (unknownTags.length > 0) {
    throw new HttpError(400, 'UNKNOWN_TAG', `Неизвестные карточки: ${unknownTags.join(', ')}`, unknownErrors);
  }
  if (unknownGoals.length > 0) {
    throw new HttpError(400, 'UNKNOWN_GOAL', 'Ошибка валидации входных данных', unknownErrors);
  }
  return patch;
}

// ---------------------------------------------------------------------------
// Exclusion words: validation + crude Russian stemming
// ---------------------------------------------------------------------------

const EXCLUSION_WORD_RE = /^[\p{L}\p{N}]+$/u;

/** PUT /me/exclusion-words body -> normalized list (trim, lowercase, dedupe keeping order). */
function validateExclusionWords(body: unknown): string[] {
  const raw = isPlainObject(body) ? body.words : undefined;
  if (!Array.isArray(raw) || !raw.every((w): w is string => typeof w === 'string')) {
    throw new HttpError(400, 'VALIDATION_ERROR', 'Проверьте список слов-исключений', {
      words: 'Передайте список слов (массив строк)',
    });
  }
  const words: string[] = [];
  for (const item of raw) {
    const word = item.normalize('NFC').trim().toLowerCase();
    const lengthError = `«${word}»: от ${EXCLUSION_WORD_MIN_LENGTH} до ${EXCLUSION_WORD_MAX_LENGTH} символов`;
    let error: string | null = null;
    if (word === '') {
      error = lengthError;
    } else if (/\s/.test(word)) {
      error = `«${word}»: только одно слово, без пробелов`;
    } else if (!EXCLUSION_WORD_RE.test(word)) {
      error = `«${word}»: допустимы только буквы и цифры`;
    } else if (word.length < EXCLUSION_WORD_MIN_LENGTH || word.length > EXCLUSION_WORD_MAX_LENGTH) {
      error = lengthError;
    }
    if (error) {
      throw new HttpError(400, 'VALIDATION_ERROR', 'Проверьте список слов-исключений', { words: error });
    }
    if (!words.includes(word)) words.push(word);
  }
  if (words.length > MAX_EXCLUSION_WORDS) {
    throw new HttpError(400, 'VALIDATION_ERROR', 'Проверьте список слов-исключений', {
      words: `Можно добавить не больше ${MAX_EXCLUSION_WORDS} слов`,
    });
  }
  return words;
}

/**
 * MOCK ONLY. The real backend matches with PostgreSQL full-text search (Russian snowball stemmer).
 * Here: lowercase, ё -> е, then strip a common ending (longest first) while the stem stays >= 3 chars,
 * at most two passes ("курить" -> "кури" -> "кур", "курю" -> "кур", "курит" -> "кур").
 */
const RU_ENDINGS = [
  'ешь', 'ете', 'ите',
  'ая', 'яя', 'ое', 'ее', 'ой', 'ий', 'ый', 'ую', 'юю', 'ть', 'ти', 'ет', 'ем', 'ют', 'ут', 'ит', 'им', 'ят', 'ат',
  'ю', 'у', 'а', 'я', 'о', 'е', 'ы', 'и', 'ь',
].sort((a, b) => b.length - a.length);

function crudeStem(word: string): string {
  let stem = word.toLowerCase().replace(/ё/g, 'е');
  for (let pass = 0; pass < 2; pass++) {
    const ending = RU_ENDINGS.find((e) => stem.endsWith(e) && stem.length - e.length >= 3);
    if (!ending) break;
    stem = stem.slice(0, -ending.length);
  }
  return stem;
}

function textStems(text: string | null): Set<string> {
  if (!text) return new Set();
  return new Set(text.split(/[^\p{L}\p{N}]+/u).filter(Boolean).map(crudeStem));
}

/** True when aboutText or the active daily status contains any excluded word (stem equality). */
function matchesExclusion(p: ProfileRecord, wordStems: string[]): boolean {
  if (wordStems.length === 0) return false;
  expireDailyStatus(p);
  const stems = textStems(p.aboutText);
  for (const s of textStems(p.dailyStatusText)) stems.add(s);
  return wordStems.some((w) => stems.has(w));
}

// ---------------------------------------------------------------------------
// OTP rate limiting
// ---------------------------------------------------------------------------

function checkOtpRateLimit(phoneHash: string): void {
  const now = Date.now();
  const recent = (otpRequests.get(phoneHash) ?? []).filter((t) => now - t < HOUR_MS);
  otpRequests.set(phoneHash, recent);
  const last = recent[recent.length - 1];
  if (last !== undefined && now - last < OTP_MIN_INTERVAL_MS) {
    const retryAfter = Math.ceil((last + OTP_MIN_INTERVAL_MS - now) / 1000);
    throw new HttpError(429, 'RATE_LIMITED', 'Код уже отправлен. Запросить новый можно чуть позже.', undefined, retryAfter);
  }
  if (recent.length >= OTP_MAX_PER_HOUR) {
    const retryAfter = Math.ceil((recent[0] + HOUR_MS - now) / 1000);
    throw new HttpError(429, 'RATE_LIMITED', 'Слишком много запросов кода. Попробуйте позже.', undefined, retryAfter);
  }
  recent.push(now);
}

// ---------------------------------------------------------------------------
// Upload (multer, in-memory)
// ---------------------------------------------------------------------------

const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: PHOTO_MAX_BYTES, files: 1, fields: 5, parts: 6 },
}).single('file');

function runUpload(req: Request, res: Response): Promise<void> {
  return new Promise((resolve, reject) => {
    upload(req, res, (err: unknown) => {
      if (!err) return resolve();
      if (err instanceof multer.MulterError) {
        if (err.code === 'LIMIT_FILE_SIZE') {
          return reject(new HttpError(413, 'FILE_TOO_LARGE', 'Размер файла превышает 5 МБ'));
        }
        return reject(
          new HttpError(400, 'VALIDATION_ERROR', 'Передайте один файл в поле «file»', { file: 'Передайте один файл' })
        );
      }
      reject(err);
    });
  });
}

// ---------------------------------------------------------------------------
// Routes
// ---------------------------------------------------------------------------

const api = express.Router();

api.route('/health').get(route((_req, res) => {
  res.json({ status: 'UP', mock: true, timestamp: nowIso() });
})).all(methodNotAllowed);

api.route('/tags').get(route((_req, res) => {
  res.json(TAGS);
})).all(methodNotAllowed);

api.route('/goals').get(route((_req, res) => {
  res.json(GOALS);
})).all(methodNotAllowed);

// --- AUTH ---

api.route('/auth/otp/request').post(route((req, res) => {
  const phoneRaw = isPlainObject(req.body) ? req.body.phone : undefined;
  if (typeof phoneRaw !== 'string' || phoneRaw.trim() === '') {
    throw new HttpError(400, 'VALIDATION_ERROR', 'Укажите номер телефона', { phone: 'Укажите номер телефона' });
  }
  const phone = normalizePhone(phoneRaw);
  if (!phone) {
    throw new HttpError(400, 'INVALID_PHONE', 'Некорректный номер телефона', { phone: 'Некорректный номер телефона' });
  }
  const phoneHash = keyedHash(phone);
  checkOtpRateLimit(phoneHash);

  // Test phones log in with the fixed code (like app.testing.otp-code); everyone else gets a random one.
  const code = isTestPhoneHash(phoneHash)
    ? TEST_OTP_CODE
    : crypto.randomInt(0, 1_000_000).toString().padStart(6, '0');
  const challenge: ChallengeRecord = {
    id: crypto.randomUUID(),
    phoneHash,
    phoneMasked: maskPhone(phone),
    codeHash: crypto.createHmac('sha256', HASH_PEPPER).update(`${phoneHash}:${code}`).digest(),
    expiresAt: Date.now() + OTP_TTL_MS,
    attempts: 0,
    usedAt: null,
  };
  challenges.set(challenge.id, challenge);
  console.log(`[dev-mock] MOCK OTP (no SMS sent) for ${challenge.phoneMasked}: ${code}`);

  res.json({
    challengeId: challenge.id,
    expiresAt: new Date(challenge.expiresAt).toISOString(),
    maskedPhone: challenge.phoneMasked,
    devMockCode: code,
  });
})).all(methodNotAllowed);

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

api.route('/auth/otp/verify').post(route((req, res) => {
  const body = isPlainObject(req.body) ? req.body : {};
  const { challengeId, code } = body;
  const errors: Record<string, string> = {};
  if (typeof challengeId !== 'string' || !UUID_RE.test(challengeId)) errors.challengeId = 'Некорректный идентификатор запроса';
  if (typeof code !== 'string' || !/^\d{6}$/.test(code)) errors.code = 'Код должен состоять из 6 цифр';
  if (Object.keys(errors).length > 0) {
    throw new HttpError(400, 'VALIDATION_ERROR', 'Проверьте код подтверждения', errors);
  }

  const challenge = challenges.get(challengeId as string);
  if (!challenge) {
    throw new HttpError(400, 'INVALID_CHALLENGE', 'Запрос кода не найден. Запросите новый код.');
  }
  if (challenge.usedAt !== null) {
    throw new HttpError(400, 'CHALLENGE_ALREADY_USED', 'Этот код уже использован. Запросите новый код.');
  }
  if (challenge.expiresAt <= Date.now()) {
    throw new HttpError(400, 'CHALLENGE_EXPIRED', 'Срок действия кода истёк. Запросите новый код.');
  }
  if (challenge.attempts >= OTP_MAX_ATTEMPTS) {
    throw new HttpError(400, 'MAX_ATTEMPTS_EXCEEDED', 'Превышено число попыток. Запросите новый код.');
  }

  challenge.attempts += 1;
  const candidate = crypto.createHmac('sha256', HASH_PEPPER).update(`${challenge.phoneHash}:${code}`).digest();
  if (!safeEqual(candidate, challenge.codeHash)) {
    const left = OTP_MAX_ATTEMPTS - challenge.attempts;
    if (left <= 0) {
      throw new HttpError(400, 'MAX_ATTEMPTS_EXCEEDED', 'Превышено число попыток. Запросите новый код.');
    }
    throw new HttpError(400, 'INVALID_CODE', `Неверный код. Осталось попыток: ${left}.`);
  }
  challenge.usedAt = Date.now();

  // The account is identified ONLY by the phone stored in the challenge.
  let account = [...accounts.values()].find((a) => a.phoneHash === challenge.phoneHash);
  if (account && account.status !== 'ACTIVE') {
    throw new HttpError(403, 'FORBIDDEN', 'Аккаунт заблокирован');
  }
  if (!account) {
    account = {
      id: crypto.randomUUID(),
      phoneHash: challenge.phoneHash,
      phoneMasked: challenge.phoneMasked,
      status: 'ACTIVE',
      createdAt: nowIso(),
      testAccount: false,
    };
    accounts.set(account.id, account);
  }
  // The flag follows the configured test phones on every login (like AccountService.updateTestAccountFlag).
  account.testAccount = isTestPhoneHash(challenge.phoneHash);

  const token = crypto.randomBytes(32).toString('base64url');
  const session: SessionRecord = {
    tokenHash: sha256Hex(token),
    accountId: account.id,
    expiresAt: Date.now() + SESSION_TTL_MS,
  };
  sessions.set(session.tokenHash, session);

  res.json({ token, expiresAt: new Date(session.expiresAt).toISOString(), accountId: account.id });
})).all(methodNotAllowed);

api.route('/auth/logout').post(auth, route((_req, res) => {
  sessions.delete(authOf(res).tokenHash);
  res.status(204).end();
})).all(methodNotAllowed);

// --- ACCOUNT ---

api.route('/me')
  .get(auth, route((_req, res) => {
    res.json(toAccountDto(accounts.get(authOf(res).accountId)!));
  }))
  .delete(auth, route((_req, res) => {
    deleteAccountData(authOf(res).accountId);
    res.status(204).end();
  }))
  .all(methodNotAllowed);

/** DELETE /me: wipes profile, photos, private filters, economy and sessions, then the account itself. */
function deleteAccountData(accountId: string): void {
  profiles.delete(accountId);
  exclusionWords.delete(accountId);
  excludedGoals.delete(accountId);
  economies.delete(accountId);
  for (const [id, photo] of photos) if (photo.accountId === accountId) photos.delete(id);
  for (const [hash, s] of sessions) if (s.accountId === accountId) sessions.delete(hash);
  // Removing the account frees the phone: the next login creates a fresh account.
  accounts.delete(accountId);
}

// --- EXCLUSION WORDS (private; never exposed in any public DTO) ---

// Premium EXCLUSION_WORDS: the list is kept when premium ends, but not applied (active: false) and not editable.
api.route('/me/exclusion-words')
  .get(auth, route((_req, res) => {
    const { accountId } = authOf(res);
    res.json({
      words: exclusionWords.get(accountId) ?? [],
      maxWords: MAX_EXCLUSION_WORDS,
      active: isFeatureAllowed(accountId, 'EXCLUSION_WORDS'),
    });
  }))
  .put(auth, route((req, res) => {
    const { accountId } = authOf(res);
    requireFeature(accountId, 'EXCLUSION_WORDS');
    const words = validateExclusionWords(req.body);
    if (words.length === 0) exclusionWords.delete(accountId);
    else exclusionWords.set(accountId, words);
    res.json({ words, maxWords: MAX_EXCLUSION_WORDS, active: isFeatureAllowed(accountId, 'EXCLUSION_WORDS') });
  }))
  .all(methodNotAllowed);

// --- EXCLUDED GOALS (premium ADVANCED_FILTERS; private hard filter, same lifecycle as exclusion words) ---

function excludedGoalsDto(accountId: string) {
  return {
    goalCodes: excludedGoals.get(accountId) ?? [],
    active: isFeatureAllowed(accountId, 'ADVANCED_FILTERS'),
  };
}

api.route('/me/excluded-goals')
  .get(auth, route((_req, res) => {
    res.json(excludedGoalsDto(authOf(res).accountId));
  }))
  .put(auth, route((req, res) => {
    const { accountId } = authOf(res);
    // Bean validation of the body runs first in the backend, then the premium check, then unknown codes.
    const raw = isPlainObject(req.body) ? req.body.goalCodes : undefined;
    if (!Array.isArray(raw) || !raw.every((c): c is string => typeof c === 'string')) {
      throw new HttpError(400, 'VALIDATION_ERROR', VALIDATION_MESSAGE, {
        goalCodes: 'Передайте список целей (может быть пустым)',
      });
    }
    if (raw.length > MAX_EXCLUDED_GOALS_REQUEST) {
      throw new HttpError(400, 'VALIDATION_ERROR', VALIDATION_MESSAGE, { goalCodes: 'Слишком длинный список' });
    }
    requireFeature(accountId, 'ADVANCED_FILTERS');
    const codes = [...new Set(raw.map((c) => c.trim()).filter(Boolean))];
    const unknown = codes.filter((c) => !GOALS_BY_CODE.has(c));
    if (unknown.length > 0) {
      throw new HttpError(400, 'UNKNOWN_GOAL', VALIDATION_MESSAGE, { goalCodes: `Неизвестные цели: ${unknown.join(', ')}` });
    }
    const sorted = goalsOf(codes).map((g) => g.code);
    if (sorted.length === 0) excludedGoals.delete(accountId);
    else excludedGoals.set(accountId, sorted);
    res.json(excludedGoalsDto(accountId));
  }))
  .all(methodNotAllowed);

// --- MY PROFILE ---

api.route('/me/profile')
  .get(auth, route((_req, res) => {
    res.json(toMyProfile(getOrCreateProfile(authOf(res).accountId)));
  }))
  .put(auth, route((req, res) => {
    const patch = validateProfileUpdate(req.body);
    const profile = getOrCreateProfile(authOf(res).accountId);
    applyProfilePatch(profile, patch);
    res.json(toMyProfile(profile));
  }))
  .all(methodNotAllowed);

/** Applies a validated PUT /me/profile patch (shared with the seed-profile endpoints). */
function applyProfilePatch(profile: ProfileRecord, patch: ProfilePatch): void {
  if (patch.displayName !== undefined) profile.displayName = patch.displayName;
  if (patch.age !== undefined) profile.age = patch.age;
  if (patch.city !== undefined) profile.city = patch.city;
  if (patch.aboutText !== undefined) profile.aboutText = patch.aboutText;
  if (patch.tagCodes !== undefined) profile.tagCodes = patch.tagCodes;
  if (patch.clearHeight) profile.heightCm = null;
  else if (patch.heightCm !== undefined) profile.heightCm = patch.heightCm;
  if (patch.goalCodes !== undefined) profile.goalCodes = patch.goalCodes;
  if (patch.dailyStatusText !== undefined) {
    profile.dailyStatusText = patch.dailyStatusText;
    profile.dailyStatusExpiresAt = patch.dailyStatusText === null ? null : Date.now() + DAILY_STATUS_TTL_MS;
  }
  profile.updatedAt = nowIso();
}

const PUBLISH_FIELD_MESSAGES: Record<PublishField, string> = {
  displayName: 'Укажите имя',
  age: 'Укажите возраст',
  city: 'Укажите город',
  photos: 'Загрузите хотя бы одну фотографию',
};

/** POST /me/profile/publish logic: 400 PROFILE_INCOMPLETE keyed by the missing fields. */
function publishProfile(profile: ProfileRecord): void {
  const missing = missingForPublish(profile);
  if (missing.length > 0) {
    throw new HttpError(
      400,
      'PROFILE_INCOMPLETE',
      'Анкета заполнена не полностью',
      Object.fromEntries(missing.map((f) => [f, PUBLISH_FIELD_MESSAGES[f]]))
    );
  }
  profile.publicationStatus = 'PUBLISHED';
  profile.updatedAt = nowIso();
}

api.route('/me/profile/publish').post(auth, route((_req, res) => {
  const profile = getOrCreateProfile(authOf(res).accountId);
  publishProfile(profile);
  res.json(toMyProfile(profile));
})).all(methodNotAllowed);

api.route('/me/profile/hide').post(auth, route((_req, res) => {
  const profile = getOrCreateProfile(authOf(res).accountId);
  profile.publicationStatus = 'HIDDEN';
  profile.updatedAt = nowIso();
  res.json(toMyProfile(profile));
})).all(methodNotAllowed);

// --- PHOTOS ---

api.route('/me/profile/photos')
  .get(auth, route((_req, res) => {
    res.json(activePhotosOf(authOf(res).accountId).map(toPhotoDto));
  }))
  .post(auth, route(async (req, res) => {
    const { accountId } = authOf(res);
    await runUpload(req, res);
    res.json(toPhotoDto(addPhoto(accountId, requireUploadedFile(req))));
  }))
  .all(methodNotAllowed);

function requireUploadedFile(req: Request): Express.Multer.File {
  if (!req.file) {
    throw new HttpError(400, 'VALIDATION_ERROR', 'Файл не передан', { file: 'Передайте файл в поле «file»' });
  }
  return req.file;
}

/** POST /me/profile/photos logic after the multipart parsing (shared with the seed-profile endpoints). */
function addPhoto(accountId: string, file: Express.Multer.File): PhotoRecord {
  const mime = detectImageType(file.buffer);
  if (!mime) {
    throw new HttpError(415, 'UNSUPPORTED_MEDIA_TYPE', 'Поддерживаются только JPEG, PNG и WebP');
  }
  const existing = activePhotosOf(accountId);
  if (existing.length >= PHOTO_MAX_COUNT) {
    throw new HttpError(400, 'PHOTO_LIMIT_EXCEEDED', `Можно загрузить не больше ${PHOTO_MAX_COUNT} фотографий`);
  }
  const data = stripMetadata(file.buffer, mime);
  if (!data) {
    throw new HttpError(415, 'UNSUPPORTED_MEDIA_TYPE', 'Файл повреждён или не является изображением');
  }
  getOrCreateProfile(accountId);
  const photo: PhotoRecord = {
    id: crypto.randomUUID(),
    accountId,
    data,
    mimeType: mime,
    sortOrder: existing.length === 0 ? 0 : existing[existing.length - 1].sortOrder + 1,
    isPrimary: !existing.some((p) => p.isPrimary),
    createdAt: nowIso(),
  };
  photos.set(photo.id, photo);
  profiles.get(accountId)!.updatedAt = nowIso();
  return photo;
}

function ownPhotoOr404(accountId: string, photoId: string): PhotoRecord {
  const photo = photos.get(photoId);
  if (!photo || photo.accountId !== accountId) {
    throw new HttpError(404, 'PHOTO_NOT_FOUND', 'Фотография не найдена');
  }
  return photo;
}

api.route('/me/profile/photos/:photoId')
  .patch(auth, route((req, res) => {
    const { accountId } = authOf(res);
    const target = ownPhotoOr404(accountId, req.params.photoId);
    for (const p of activePhotosOf(accountId)) p.isPrimary = p.id === target.id;
    const profile = profiles.get(accountId);
    if (profile) profile.updatedAt = nowIso();
    res.status(204).end();
  }))
  .delete(auth, route((req, res) => {
    const { accountId } = authOf(res);
    const target = ownPhotoOr404(accountId, req.params.photoId);
    photos.delete(target.id);
    const remaining = activePhotosOf(accountId);
    if (target.isPrimary && remaining.length > 0) remaining[0].isPrimary = true;
    const profile = profiles.get(accountId);
    if (profile) profile.updatedAt = nowIso();
    res.status(204).end();
  }))
  .all(methodNotAllowed);

// --- MEDIA (public, signature-checked) ---

api.route('/media/photos/:photoId').get(route((req, res) => {
  const { photoId } = req.params;
  const photo = photos.get(photoId);
  const owner = photo ? accounts.get(photo.accountId) : undefined;
  if (!photo || !owner || owner.status !== 'ACTIVE' || !isValidPhotoSignature(photoId, req.query.exp, req.query.sig)) {
    throw new HttpError(404, 'PHOTO_NOT_FOUND', 'Фотография не найдена');
  }
  const maxAge = Math.max(0, Number(req.query.exp) - Math.floor(Date.now() / 1000));
  res.setHeader('Content-Type', photo.mimeType);
  res.setHeader('Content-Length', String(photo.data.length));
  res.setHeader('Cache-Control', `private, max-age=${maxAge}`);
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.end(photo.data);
})).all(methodNotAllowed);

// --- DISCOVERY ---

function parseIntParam(
  value: unknown,
  name: string,
  errors: Record<string, string>,
  min: number,
  max: number
): number | undefined {
  if (value === undefined || value === '') return undefined;
  if (typeof value !== 'string' || !/^-?\d+$/.test(value)) {
    errors[name] = 'Должно быть целым числом';
    return undefined;
  }
  const n = Number(value);
  if (n < min || n > max) {
    errors[name] = `Допустимо от ${min} до ${max}`;
    return undefined;
  }
  return n;
}

api.route('/discovery/profiles').get(auth, route((req, res) => {
  const { accountId } = authOf(res);
  const q = req.query;
  const errors: Record<string, string> = {};
  const page = parseIntParam(q.page, 'page', errors, 0, 1_000_000) ?? 0;
  const limit = parseIntParam(q.limit, 'limit', errors, 1, 50) ?? 20;
  const minAge = parseIntParam(q.minAge, 'minAge', errors, 18, 100);
  const maxAge = parseIntParam(q.maxAge, 'maxAge', errors, 18, 100);
  const minHeight = parseIntParam(q.minHeight, 'minHeight', errors, 0, 1000);
  const maxHeight = parseIntParam(q.maxHeight, 'maxHeight', errors, 0, 1000);
  let tagLogic: 'AND' | 'OR' = 'OR';
  if (q.tagLogic !== undefined && q.tagLogic !== '') {
    const v = typeof q.tagLogic === 'string' ? q.tagLogic.toUpperCase() : '';
    if (v === 'AND' || v === 'OR') tagLogic = v;
    else errors.tagLogic = 'Допустимо AND или OR';
  }
  if (q.city !== undefined && typeof q.city !== 'string') errors.city = 'Должно быть строкой';
  if (q.tagCodes !== undefined && typeof q.tagCodes !== 'string') errors.tagCodes = 'Перечислите коды через запятую';
  if (q.goalCodes !== undefined && typeof q.goalCodes !== 'string') errors.goalCodes = 'Перечислите коды через запятую';
  if (Object.keys(errors).length > 0) {
    throw new HttpError(400, 'VALIDATION_ERROR', 'Некорректные параметры поиска', errors);
  }
  if (minAge !== undefined && maxAge !== undefined && minAge > maxAge) {
    throw new HttpError(400, 'VALIDATION_ERROR', 'Минимальный возраст больше максимального');
  }
  if (minHeight !== undefined && maxHeight !== undefined && minHeight > maxHeight) {
    throw new HttpError(400, 'VALIDATION_ERROR', 'Минимальный рост больше максимального');
  }

  const city = typeof q.city === 'string' ? q.city.trim().toLocaleLowerCase('ru') : '';
  const tagCodes =
    typeof q.tagCodes === 'string'
      ? [...new Set(q.tagCodes.split(',').map((c) => c.trim()).filter(Boolean))]
      : [];

  const goalCodes =
    typeof q.goalCodes === 'string'
      ? [...new Set(q.goalCodes.split(',').map((c) => c.trim()).filter(Boolean))]
      : [];
  // Premium ADVANCED_FILTERS: height range and wanted goals.
  if (minHeight !== undefined || maxHeight !== undefined || goalCodes.length > 0) {
    requireFeature(accountId, 'ADVANCED_FILTERS');
  }

  // Saved hard filters are kept when premium ends, but applied only while the feature is available.
  const excludedStems = isFeatureAllowed(accountId, 'EXCLUSION_WORDS')
    ? (exclusionWords.get(accountId) ?? []).map(crudeStem)
    : [];
  const goalsToExclude = isFeatureAllowed(accountId, 'ADVANCED_FILTERS') ? (excludedGoals.get(accountId) ?? []) : [];
  // Test accounts and real users never see each other.
  const viewerIsTestAccount = accounts.get(accountId)?.testAccount === true;

  // All filters (including the caller's exclusion words) are applied BEFORE pagination, so totals are exact.
  const matched = [...profiles.values()]
    .filter((p) => {
      if (p.accountId === accountId || p.publicationStatus !== 'PUBLISHED') return false;
      const owner = accounts.get(p.accountId);
      if (owner?.status !== 'ACTIVE' || owner.testAccount !== viewerIsTestAccount) return false;
      if (city && (p.city ?? '').toLocaleLowerCase('ru') !== city) return false;
      if (minAge !== undefined && (p.age === null || p.age < minAge)) return false;
      if (maxAge !== undefined && (p.age === null || p.age > maxAge)) return false;
      if (tagCodes.length > 0) {
        const has = (c: string) => p.tagCodes.includes(c);
        if (tagLogic === 'AND' ? !tagCodes.every(has) : !tagCodes.some(has)) return false;
      }
      // Profiles without height never match a height filter.
      if (minHeight !== undefined && (p.heightCm === null || p.heightCm < minHeight)) return false;
      if (maxHeight !== undefined && (p.heightCm === null || p.heightCm > maxHeight)) return false;
      if (goalCodes.length > 0 && !goalCodes.some((c) => p.goalCodes.includes(c))) return false;
      if (goalsToExclude.some((c) => p.goalCodes.includes(c))) return false;
      if (matchesExclusion(p, excludedStems)) return false;
      return true;
    })
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt) || a.id.localeCompare(b.id));

  const totalElements = matched.length;
  res.json({
    items: matched.slice(page * limit, page * limit + limit).map(toPublicProfile),
    page,
    limit,
    totalElements,
    totalPages: Math.ceil(totalElements / limit),
  });
})).all(methodNotAllowed);

// --- WALLET & COSMETICS ---

api.route('/me/wallet').get(auth, route((_req, res) => {
  res.json(toWalletDto(authOf(res).accountId));
})).all(methodNotAllowed);

const VALIDATION_MESSAGE = 'Ошибка валидации входных данных';

function requireJsonObject(body: unknown): Record<string, unknown> {
  if (!isPlainObject(body)) throw new HttpError(400, 'MALFORMED_REQUEST', 'Некорректный запрос');
  return body;
}

/** Integer field of a JSON body; absent = 0 (Java primitive), wrong type = MALFORMED_REQUEST. */
function intField(body: Record<string, unknown>, name: string, min: number, max: number): number {
  const raw = body[name] ?? 0;
  if (typeof raw !== 'number' || !Number.isFinite(raw)) {
    throw new HttpError(400, 'MALFORMED_REQUEST', 'Некорректный запрос');
  }
  if (!Number.isInteger(raw) || raw < min || raw > max) {
    throw new HttpError(400, 'VALIDATION_ERROR', VALIDATION_MESSAGE, {
      [name]: `Должно быть целым числом от ${min} до ${max}`,
    });
  }
  return raw;
}

api.route('/me/cosmetics/equipped/:slot')
  .put(auth, route((req, res) => {
    const { accountId } = authOf(res);
    const body = requireJsonObject(req.body);
    const itemCode = body.itemCode;
    if (typeof itemCode !== 'string' || itemCode.trim() === '') {
      throw new HttpError(400, 'VALIDATION_ERROR', VALIDATION_MESSAGE, { itemCode: 'Укажите предмет' });
    }
    const slot = parseSlot(req.params.slot);
    const item = requireItem(itemCode);
    if (item.equipSlot !== slot) {
      throw new HttpError(400, 'WRONG_SLOT', `Этот предмет нельзя надеть в слот ${slot}`);
    }
    const e = economyOf(accountId);
    if (totalBalance(e, item.code) <= 0) {
      throw new HttpError(403, 'ITEM_NOT_OWNED', 'У вас нет этого предмета');
    }
    e.equipped.set(slot, item.code);
    res.json(toWalletDto(accountId));
  }))
  .delete(auth, route((req, res) => {
    const { accountId } = authOf(res);
    economyOf(accountId).equipped.delete(parseSlot(req.params.slot));
    res.json(toWalletDto(accountId));
  }))
  .all(methodNotAllowed);

// --- ACHIEVEMENTS ---

api.route('/me/achievements').get(auth, route((_req, res) => {
  res.json(toAchievementDtos(authOf(res).accountId));
})).all(methodNotAllowed);

// --- TESTING (test accounts only; the mock always runs with testing enabled) ---

function requireTestAccount(res: Response): string {
  const { accountId } = authOf(res);
  if (accounts.get(accountId)?.testAccount !== true) {
    throw new HttpError(403, 'TEST_ACCOUNT_REQUIRED', 'Доступно только тестовым аккаунтам');
  }
  return accountId;
}

function toTestingState(accountId: string) {
  const wallet = toWalletDto(accountId);
  const offsetMs = economies.get(accountId)?.clockOffsetMs ?? 0;
  return {
    accountId,
    clockOffsetDays: offsetMs / DAY_MS,
    economyNow: new Date(economyNow(accountId)).toISOString(),
    wallet,
    achievements: toAchievementDtos(accountId),
  };
}

api.route('/testing/state').get(auth, route((_req, res) => {
  res.json(toTestingState(requireTestAccount(res)));
})).all(methodNotAllowed);

api.route('/testing/achievements/:code/complete').post(auth, route((req, res) => {
  const accountId = requireTestAccount(res);
  const { code } = req.params;
  const definition = ACHIEVEMENTS_BY_CODE.get(code);
  if (!definition) throw new HttpError(404, 'ACHIEVEMENT_NOT_FOUND', `Достижение не найдено: ${code}`);
  const e = economyOf(accountId);
  if (!e.achievements.has(code)) {
    grantRewards(accountId, definition.rewards, achievementRewardKey(code));
    e.achievements.set(code, nowIso());
  }
  res.json(toTestingState(accountId));
})).all(methodNotAllowed);

api.route('/testing/achievements/:code').delete(auth, route((req, res) => {
  const accountId = requireTestAccount(res);
  const e = economyOf(accountId);
  // Forget the completion and the reward transaction; already granted rewards are kept.
  e.achievements.delete(req.params.code);
  e.appliedKeys.delete(achievementRewardKey(req.params.code));
  res.json(toTestingState(accountId));
})).all(methodNotAllowed);

api.route('/testing/wallet/items/:itemCode')
  .post(auth, route((req, res) => {
    const quantity = intField(requireJsonObject(req.body), 'quantity', 0, MAX_TESTING_QUANTITY);
    const accountId = requireTestAccount(res);
    if (quantity < 1) throw new HttpError(400, 'VALIDATION_ERROR', 'Количество должно быть больше нуля');
    grantRewards(accountId, [{ kind: 'item', itemCode: req.params.itemCode, quantity }], null);
    res.json(toTestingState(accountId));
  }))
  .put(auth, route((req, res) => {
    const quantity = intField(requireJsonObject(req.body), 'quantity', 0, MAX_TESTING_QUANTITY);
    const accountId = requireTestAccount(res);
    const item = requireItem(req.params.itemCode);
    if (item.kind !== 'CONSUMABLE' && quantity > 1) {
      throw new HttpError(400, 'VALIDATION_ERROR', `Некорректное количество для ${item.code}`);
    }
    bucketsOf(economyOf(accountId), item.code).PERMANENT = quantity;
    res.json(toTestingState(accountId));
  }))
  .all(methodNotAllowed);

api.route('/testing/premium')
  .post(auth, route((req, res) => {
    const days = intField(requireJsonObject(req.body), 'days', 1, MAX_TESTING_DAYS);
    const accountId = requireTestAccount(res);
    grantRewards(accountId, [{ kind: 'premium', days }], null);
    res.json(toTestingState(accountId));
  }))
  .delete(auth, route((_req, res) => {
    const accountId = requireTestAccount(res);
    const e = economyOf(accountId);
    const now = economyNow(accountId);
    if (e.premiumUntil !== null && e.premiumUntil > now) e.premiumUntil = now;
    res.json(toTestingState(accountId));
  }))
  .all(methodNotAllowed);

api.route('/testing/clock/advance').post(auth, route((req, res) => {
  const days = intField(requireJsonObject(req.body), 'days', 1, MAX_TESTING_DAYS);
  const accountId = requireTestAccount(res);
  economyOf(accountId).clockOffsetMs += days * DAY_MS;
  res.json(toTestingState(accountId));
})).all(methodNotAllowed);

api.route('/testing/clock').delete(auth, route((_req, res) => {
  const accountId = requireTestAccount(res);
  economyOf(accountId).clockOffsetMs = 0;
  res.json(toTestingState(accountId));
})).all(methodNotAllowed);

// --- TESTING: SEED PROFILES (mirror of TestProfilesController / TestProfilesService) ---
// Phone-less test accounts with a ready profile: being test accounts, they are visible only to test accounts.
// Order of checks as in Spring: path id format and body binding/Bean Validation, then the test-account check,
// then the seed check, then the service validation (validateProfileUpdate = ProfileService).

/** phone_hash prefix of seed accounts (AccountService.SEED_PHONE_HASH_PREFIX); real hashes are hex, so no login. */
const SEED_PHONE_HASH_PREFIX = 'seed:';
const MAX_SEED_COSMETICS = 10;

function isActiveSeedAccount(accountId: string): boolean {
  const account = accounts.get(accountId);
  return account?.status === 'ACTIVE' && account.phoneHash.startsWith(SEED_PHONE_HASH_PREFIX);
}

/** Oldest first: the accounts map keeps insertion (= creation) order. */
function activeSeedAccountIds(): string[] {
  return [...accounts.keys()].filter(isActiveSeedAccount);
}

function requireSeed(accountId: string): void {
  if (!isActiveSeedAccount(accountId)) {
    throw new HttpError(404, 'SEED_PROFILE_NOT_FOUND', 'Тестовый профиль не найден');
  }
}

function toSeedProfile(accountId: string) {
  return { accountId, profile: toMyProfile(getOrCreateProfile(accountId)) };
}

/** {accountId} path variable: a non-UUID is a type mismatch in Spring -> 400 MALFORMED_REQUEST. */
function seedIdParam(raw: string): string {
  if (!UUID_RE.test(raw)) throw new HttpError(400, 'MALFORMED_REQUEST', 'Некорректный запрос');
  return raw.toLowerCase();
}

/**
 * Bean Validation of ProfileUpdateDto (@Size/@Min/@Max on the raw values, before trimming). Trimmed lengths,
 * unknown codes and the rest are checked later by validateProfileUpdate, like ProfileService does.
 */
function collectProfileConstraintErrors(
  body: Record<string, unknown>,
  prefix: string,
  errors: Record<string, string>
): void {
  const put = (name: string, message: string) => {
    errors[prefix + name] ??= message;
  };
  const maxLength = (name: string, max: number, message: string) => {
    const v = body[name];
    if ((typeof v === 'string' || Array.isArray(v)) && v.length > max) put(name, message);
  };
  maxLength('displayName', 50, 'Имя должно быть от 2 до 50 символов');
  if (typeof body.age === 'number') {
    if (body.age < 18) put('age', 'Возраст должен быть не менее 18 лет');
    else if (body.age > 100) put('age', 'Некорректный возраст');
  }
  maxLength('city', 60, 'Город не должен превышать 60 символов');
  maxLength('aboutText', 1000, 'Описание «О себе» не должно превышать 1000 символов');
  maxLength('tagCodes', MAX_TAGS, `Можно выбрать не более ${MAX_TAGS} карточек`);
  maxLength('dailyStatusText', 200, 'Статус на сегодня не должен превышать 200 символов');
  if (typeof body.heightCm === 'number' && (body.heightCm < HEIGHT_MIN_CM || body.heightCm > HEIGHT_MAX_CM)) {
    put('heightCm', `Рост должен быть от ${HEIGHT_MIN_CM} до ${HEIGHT_MAX_CM} см`);
  }
  maxLength('goalCodes', MAX_PROFILE_GOALS, `Можно выбрать не более ${MAX_PROFILE_GOALS} целей`);
}

function throwIfErrors(errors: Record<string, string>): void {
  if (Object.keys(errors).length > 0) throw new HttpError(400, 'VALIDATION_ERROR', VALIDATION_MESSAGE, errors);
}

const malformed = () => new HttpError(400, 'MALFORMED_REQUEST', 'Некорректный запрос');

/** TestProfilesService.CreateRequest: { profile (required), premiumDays 0..3650, cosmetics (<= 10) }. */
function parseSeedCreateRequest(raw: unknown) {
  const body = requireJsonObject(raw);
  const errors: Record<string, string> = {};
  let profile: Record<string, unknown> = {};
  if (body.profile === undefined || body.profile === null) {
    errors.profile = 'Заполните profile';
  } else if (!isPlainObject(body.profile)) {
    throw malformed();
  } else {
    profile = body.profile;
    collectProfileConstraintErrors(profile, 'profile.', errors);
  }
  let premiumDays = 0;
  if (body.premiumDays !== undefined && body.premiumDays !== null) {
    if (typeof body.premiumDays !== 'number' || !Number.isFinite(body.premiumDays)) throw malformed();
    if (!Number.isInteger(body.premiumDays) || body.premiumDays < 0 || body.premiumDays > MAX_TESTING_DAYS) {
      errors.premiumDays = `Должно быть целым числом от 0 до ${MAX_TESTING_DAYS}`;
    } else {
      premiumDays = body.premiumDays;
    }
  }
  let cosmetics: string[] = [];
  if (body.cosmetics !== undefined && body.cosmetics !== null) {
    if (!Array.isArray(body.cosmetics) || !body.cosmetics.every((c): c is string => typeof c === 'string')) {
      throw malformed();
    }
    if (body.cosmetics.length > MAX_SEED_COSMETICS) errors.cosmetics = `Не более ${MAX_SEED_COSMETICS} предметов`;
    else cosmetics = body.cosmetics;
  }
  throwIfErrors(errors);
  return { profile, premiumDays, cosmetics };
}

api.route('/testing/profiles')
  .get(auth, route((_req, res) => {
    requireTestAccount(res);
    res.json(activeSeedAccountIds().map(toSeedProfile));
  }))
  .post(auth, route((req, res) => {
    const request = parseSeedCreateRequest(req.body);
    requireTestAccount(res);
    // The backend creates the account first and rolls back on an error; validating everything up front
    // (profile, then items in list order) leaves nothing behind and keeps the same error order.
    const patch = validateProfileUpdate(request.profile);
    const items = request.cosmetics.map((code) => requireItem(code));

    const accountId = crypto.randomUUID();
    accounts.set(accountId, {
      id: accountId,
      phoneHash: SEED_PHONE_HASH_PREFIX + crypto.randomUUID(),
      phoneMasked: 'тестовый профиль',
      status: 'ACTIVE',
      createdAt: nowIso(),
      testAccount: true,
    });
    applyProfilePatch(getOrCreateProfile(accountId), patch);
    if (request.premiumDays > 0) {
      grantRewards(accountId, [{ kind: 'premium', days: request.premiumDays }], `testing:seed-premium:${accountId}`);
    }
    for (const item of items) {
      grantRewards(accountId, [{ kind: 'item', itemCode: item.code, quantity: 1 }],
        `testing:seed-item:${accountId}:${item.code}`);
      if (item.equipSlot !== null) economyOf(accountId).equipped.set(item.equipSlot, item.code);
    }
    res.status(201).json(toSeedProfile(accountId));
  }))
  .delete(auth, route((_req, res) => {
    requireTestAccount(res);
    const ids = activeSeedAccountIds();
    ids.forEach(deleteAccountData);
    res.json({ deleted: ids.length });
  }))
  .all(methodNotAllowed);

api.route('/testing/profiles/:accountId')
  .put(auth, route((req, res) => {
    const accountId = seedIdParam(req.params.accountId);
    const body = requireJsonObject(req.body);
    const errors: Record<string, string> = {};
    collectProfileConstraintErrors(body, '', errors);
    throwIfErrors(errors);
    requireTestAccount(res);
    requireSeed(accountId);
    applyProfilePatch(getOrCreateProfile(accountId), validateProfileUpdate(body));
    res.json(toSeedProfile(accountId));
  }))
  .delete(auth, route((req, res) => {
    const accountId = seedIdParam(req.params.accountId);
    requireTestAccount(res);
    requireSeed(accountId);
    deleteAccountData(accountId);
    res.status(204).end();
  }))
  .all(methodNotAllowed);

api.route('/testing/profiles/:accountId/photos').post(auth, route(async (req, res) => {
  await runUpload(req, res); // multipart is parsed before the controller runs (413 FILE_TOO_LARGE first)
  const accountId = seedIdParam(req.params.accountId);
  const file = requireUploadedFile(req);
  requireTestAccount(res);
  requireSeed(accountId);
  res.json(toPhotoDto(addPhoto(accountId, file)));
})).all(methodNotAllowed);

api.route('/testing/profiles/:accountId/publish').post(auth, route((req, res) => {
  const accountId = seedIdParam(req.params.accountId);
  requireTestAccount(res);
  requireSeed(accountId);
  publishProfile(getOrCreateProfile(accountId));
  res.json(toSeedProfile(accountId));
})).all(methodNotAllowed);

// ---------------------------------------------------------------------------
// App
// ---------------------------------------------------------------------------

async function start(): Promise<void> {
  const app = express();
  app.disable('x-powered-by');

  app.use((_req, res, next) => {
    const traceId = crypto.randomUUID().replace(/-/g, '').slice(0, 16);
    res.locals.traceId = traceId;
    res.setHeader('X-Trace-Id', traceId);
    next();
  });

  // Photos are multipart now, so JSON bodies stay small.
  app.use('/api', express.json({ limit: '100kb' }));
  app.use('/api/v1', api);
  app.use('/api', route(() => {
    throw new HttpError(404, 'NOT_FOUND', 'Ресурс не найден');
  }));

  app.use('/api', (err: unknown, _req: Request, res: Response, _next: NextFunction) => {
    if (err instanceof HttpError) {
      sendError(res, err);
      return;
    }
    const type = isPlainObject(err) ? err.type : undefined;
    if (type === 'entity.parse.failed') {
      sendError(res, new HttpError(400, 'VALIDATION_ERROR', 'Некорректный JSON в теле запроса'));
      return;
    }
    if (type === 'entity.too.large') {
      sendError(res, new HttpError(413, 'VALIDATION_ERROR', 'Слишком большой запрос'));
      return;
    }
    console.error(`[dev-mock] unhandled error (trace ${String(res.locals.traceId)})`, err);
    sendError(res, new HttpError(500, 'INTERNAL_ERROR', 'Внутренняя ошибка сервера'));
  });

  // SPA through Vite middleware (dev only).
  const { createServer: createViteServer } = await import('vite');
  const vite = await createViteServer({ server: { middlewareMode: true }, appType: 'spa' });
  app.use(vite.middlewares);

  const cleanup = setInterval(() => {
    const now = Date.now();
    for (const [id, c] of challenges) if (c.expiresAt <= now) challenges.delete(id);
    for (const [hash, s] of sessions) if (s.expiresAt <= now) sessions.delete(hash);
    for (const [key, times] of otpRequests) {
      const recent = times.filter((t) => now - t < HOUR_MS);
      if (recent.length === 0) otpRequests.delete(key);
      else otpRequests.set(key, recent);
    }
  }, CLEANUP_INTERVAL_MS);
  cleanup.unref();

  const port = Number(process.env.PORT) || 3000;
  app.listen(port, () => {
    console.log(`[dev-mock] In-memory MOCK API + SPA on http://localhost:${port} (data is lost on restart)`);
    console.log('[dev-mock] OTP codes are mocked: they are printed here and returned as devMockCode.');
    console.log(`[dev-mock] Test accounts (fixed OTP ${TEST_OTP_CODE}, /api/v1/testing): ${TEST_PHONES.map(maskPhone).join(', ') || 'none'}`);
  });
}

start().catch((err) => {
  console.error('[dev-mock] failed to start', err);
  process.exit(1);
});
