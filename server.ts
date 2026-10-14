/**
 * DEV-ONLY in-memory mock of the Dating API v1 (docs/api-contract.md).
 *
 * Started by `npm run dev:mock`: serves /api/v1 from memory plus the SPA through Vite middleware.
 * Everything is lost on restart. OTP codes are NOT sent anywhere: they are returned as `devMockCode`
 * and printed to the console. The real backend is the Java service in backend/.
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

// ---------------------------------------------------------------------------
// In-memory storage
// ---------------------------------------------------------------------------

const accounts = new Map<string, AccountRecord>(); // accountId -> account
const sessions = new Map<string, SessionRecord>(); // tokenHash -> session
const challenges = new Map<string, ChallengeRecord>(); // challengeId -> challenge
const profiles = new Map<string, ProfileRecord>(); // accountId -> profile
const photos = new Map<string, PhotoRecord>(); // photoId -> photo
const otpRequests = new Map<string, number[]>(); // phoneHash -> request timestamps (ms)

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
    readonly retryAfterSeconds?: number
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
  };
}

function toAccountDto(a: AccountRecord) {
  return { id: a.id, phoneMasked: a.phoneMasked, status: a.status, createdAt: a.createdAt };
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
      createdAt: ts,
      updatedAt: ts,
    };
    profiles.set(accountId, profile);
  }
  return profile;
}

// ---------------------------------------------------------------------------
// Seed data: a few PUBLISHED demo profiles with generated placeholder photos
// ---------------------------------------------------------------------------

function seed(): void {
  const demo = [
    { name: 'Анна', age: 26, city: 'Москва', colors: [[244, 114, 182], [251, 191, 36]], photoCount: 2,
      about: 'Архитектор городской среды. Люблю долгие пешие прогулки по переулкам, кофе без сиропа и плёночную фотографию.',
      tags: ['PACE_ASYNCHRONOUS', 'FORMAT_WALK_FIRST', 'CALLS_TEXT_ONLY', 'BOUND_DIRECT_COMMUNICATION', 'SOC_QUIET_SPACES'],
      daily: 'В субботу иду на выставку современной графики' },
    { name: 'Михаил', age: 29, city: 'Санкт-Петербург', colors: [[56, 189, 248], [30, 64, 175]], photoCount: 1,
      about: 'Разрабатываю инди-игры, читаю научпоп и играю на акустической гитаре. Ищу человека, с которым комфортно молчать и обсуждать книги.',
      tags: ['PACE_LOW_BATTERY', 'FORMAT_COFFEE_SHORT', 'CALLS_TEXT_ONLY', 'SOC_DEEP_TALKS'], daily: null },
    { name: 'Елена', age: 24, city: 'Москва', colors: [[167, 243, 208], [5, 150, 105]], photoCount: 1,
      about: 'Книжный редактор и любительница керамики. Рада неспешному знакомству и общению на равных.',
      tags: ['PACE_ASYNCHRONOUS', 'FORMAT_TEXT_BEFORE_MEET', 'BOUND_NO_SMALLTALK', 'SOC_QUIET_SPACES'],
      daily: 'Заварила зелёный чай, читаю новую главу' },
    { name: 'Дмитрий', age: 31, city: 'Екатеринбург', colors: [[196, 181, 253], [91, 33, 182]], photoCount: 1,
      about: 'Биофизик, увлекаюсь треккингом на Урале и астрономией. Предпочитаю сначала содержательно поговорить в чате.',
      tags: ['PACE_FAST_CHAT', 'FORMAT_SHARED_ACTIVITY', 'CALLS_PRE_CALL_CHECK', 'BOUND_DIRECT_COMMUNICATION'], daily: null },
    { name: 'София', age: 27, city: 'Казань', colors: [[253, 186, 116], [194, 65, 12]], photoCount: 1,
      about: 'Преподаватель лингвистики, фанат чая и настольных стратегий. Легко общаюсь текстом и голосовыми.',
      tags: ['PACE_FAST_CHAT', 'FORMAT_SHARED_ACTIVITY', 'CALLS_VOICE_WELCOME', 'SOC_DEEP_TALKS'],
      daily: 'Собираю команду на партию в Catan в эти выходные' },
  ];

  demo.forEach((d, i) => {
    // Fictitious numbers +7 999 000-00-0X: logging in with one of them opens the demo account.
    const phone = `7999000000${i}`;
    const accountId = crypto.randomUUID();
    const ts = nowIso();
    accounts.set(accountId, {
      id: accountId,
      phoneHash: keyedHash(phone),
      phoneMasked: maskPhone(phone),
      status: 'ACTIVE',
      createdAt: ts,
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
      createdAt: ts,
      updatedAt: ts,
    });
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
  });
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

  if (Object.keys(errors).length > 0) {
    throw new HttpError(400, 'VALIDATION_ERROR', 'Проверьте правильность заполнения полей', errors);
  }
  if (unknownTags.length > 0) {
    throw new HttpError(400, 'UNKNOWN_TAG', `Неизвестные карточки: ${unknownTags.join(', ')}`, {
      tagCodes: `Неизвестные карточки: ${unknownTags.join(', ')}`,
    });
  }
  return patch;
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

  const code = crypto.randomInt(0, 1_000_000).toString().padStart(6, '0');
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
    };
    accounts.set(account.id, account);
  }

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
    const { accountId } = authOf(res);
    profiles.delete(accountId);
    for (const [id, photo] of photos) if (photo.accountId === accountId) photos.delete(id);
    for (const [hash, s] of sessions) if (s.accountId === accountId) sessions.delete(hash);
    // Removing the account frees the phone: the next login creates a fresh account.
    accounts.delete(accountId);
    res.status(204).end();
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
    if (patch.displayName !== undefined) profile.displayName = patch.displayName;
    if (patch.age !== undefined) profile.age = patch.age;
    if (patch.city !== undefined) profile.city = patch.city;
    if (patch.aboutText !== undefined) profile.aboutText = patch.aboutText;
    if (patch.tagCodes !== undefined) profile.tagCodes = patch.tagCodes;
    if (patch.dailyStatusText !== undefined) {
      profile.dailyStatusText = patch.dailyStatusText;
      profile.dailyStatusExpiresAt = patch.dailyStatusText === null ? null : Date.now() + DAILY_STATUS_TTL_MS;
    }
    profile.updatedAt = nowIso();
    res.json(toMyProfile(profile));
  }))
  .all(methodNotAllowed);

const PUBLISH_FIELD_MESSAGES: Record<PublishField, string> = {
  displayName: 'Укажите имя',
  age: 'Укажите возраст',
  city: 'Укажите город',
  photos: 'Загрузите хотя бы одну фотографию',
};

api.route('/me/profile/publish').post(auth, route((_req, res) => {
  const profile = getOrCreateProfile(authOf(res).accountId);
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
    const file = req.file;
    if (!file) {
      throw new HttpError(400, 'VALIDATION_ERROR', 'Файл не передан', { file: 'Передайте файл в поле «file»' });
    }
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
    res.json(toPhotoDto(photo));
  }))
  .all(methodNotAllowed);

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
  let tagLogic: 'AND' | 'OR' = 'OR';
  if (q.tagLogic !== undefined && q.tagLogic !== '') {
    const v = typeof q.tagLogic === 'string' ? q.tagLogic.toUpperCase() : '';
    if (v === 'AND' || v === 'OR') tagLogic = v;
    else errors.tagLogic = 'Допустимо AND или OR';
  }
  if (q.city !== undefined && typeof q.city !== 'string') errors.city = 'Должно быть строкой';
  if (q.tagCodes !== undefined && typeof q.tagCodes !== 'string') errors.tagCodes = 'Перечислите коды через запятую';
  if (Object.keys(errors).length > 0) {
    throw new HttpError(400, 'VALIDATION_ERROR', 'Некорректные параметры поиска', errors);
  }

  const city = typeof q.city === 'string' ? q.city.trim().toLocaleLowerCase('ru') : '';
  const tagCodes =
    typeof q.tagCodes === 'string'
      ? [...new Set(q.tagCodes.split(',').map((c) => c.trim()).filter(Boolean))]
      : [];

  // All filters are applied BEFORE pagination, so totals are exact.
  const matched = [...profiles.values()]
    .filter((p) => {
      if (p.accountId === accountId || p.publicationStatus !== 'PUBLISHED') return false;
      if (accounts.get(p.accountId)?.status !== 'ACTIVE') return false;
      if (city && (p.city ?? '').toLocaleLowerCase('ru') !== city) return false;
      if (minAge !== undefined && (p.age === null || p.age < minAge)) return false;
      if (maxAge !== undefined && (p.age === null || p.age > maxAge)) return false;
      if (tagCodes.length > 0) {
        const has = (c: string) => p.tagCodes.includes(c);
        if (tagLogic === 'AND' ? !tagCodes.every(has) : !tagCodes.some(has)) return false;
      }
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
  });
}

start().catch((err) => {
  console.error('[dev-mock] failed to start', err);
  process.exit(1);
});
