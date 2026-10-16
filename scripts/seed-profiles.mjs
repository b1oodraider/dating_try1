#!/usr/bin/env node
/**
 * Bulk loader of QA seed profiles (Testing API, docs/api-contract.md, "Testing: seed profiles").
 *
 *   node scripts/seed-profiles.mjs [--api URL] [--phone PHONE] [--code OTP] [--dir DIR] [--reset] [--dry-run]
 *
 * Reads <dir>/profiles.json (or profiles.example.json), photos from <dir>/photos/<folder>/, logs in as a test
 * account and creates each profile: create (DRAFT) -> upload photos one by one -> publish.
 * Plain Node 18+, no dependencies (built-in fetch, FormData, Blob). See seed/README.md.
 */
import { readFile, readdir, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const DEFAULTS = {
  api: 'http://localhost:8080/api/v1',
  phone: '+7 900 000-00-01',
  code: '123456',
  dir: path.join(REPO_ROOT, 'seed'),
};

const PHOTO_EXTENSIONS = new Map([
  ['.jpg', 'image/jpeg'],
  ['.jpeg', 'image/jpeg'],
  ['.png', 'image/png'],
  ['.webp', 'image/webp'],
]);
const PHOTO_MAX_BYTES = 5 * 1024 * 1024;
const PHOTO_MAX_COUNT = 6;
const MAX_TAGS = 10;
const MAX_GOALS = 3;
const MAX_COSMETICS = 10;
const MAX_PREMIUM_DAYS = 3650;
/** Wait for the OTP rate limit (1 request per minute per phone) at most this long. */
const MAX_RATE_LIMIT_WAIT_S = 90;

/** Stub catalog of migration 007; other codes only produce a warning (the server has the final say). */
const KNOWN_COSMETICS = new Set([
  'FRAME_STUB_GOLD', 'FRAME_STUB_NEON', 'STATUS_STUB_EARLY_BIRD', 'STATUS_STUB_BOOKWORM',
  'WALLPAPER_STUB_NIGHT', 'WALLPAPER_STUB_SUNSET', 'STICKERS_STUB_CATS', 'STICKERS_STUB_SPACE',
]);

/** Fields of PUT /me/profile that an entry may carry. */
const PROFILE_FIELDS = ['displayName', 'age', 'city', 'aboutText', 'tagCodes', 'heightCm', 'goalCodes', 'dailyStatusText'];
const ENTRY_FIELDS = new Set(['folder', ...PROFILE_FIELDS, 'premiumDays', 'cosmetics', 'publish']);

const HELP = `Массовая загрузка тестовых анкет (seed profiles) с фотографиями.

Использование:
  node scripts/seed-profiles.mjs [параметры]

Параметры:
  --api URL       базовый адрес API (по умолчанию ${DEFAULTS.api};
                  для мок-сервера: http://localhost:3000/api/v1)
  --phone PHONE   тестовый номер для входа (по умолчанию "${DEFAULTS.phone}")
  --code CODE     код OTP (по умолчанию ${DEFAULTS.code}; если сервер вернул devMockCode, берётся он)
  --dir DIR       папка с анкетами (по умолчанию папка seed в корне репозитория)
  --reset         сначала удалить все ранее созданные тестовые анкеты
  --dry-run       только проверить папку, ничего не отправлять
  --help          эта справка

Структура папки: <DIR>/profiles.json (если его нет — profiles.example.json)
и <DIR>/photos/<folder>/*.jpg|jpeg|png|webp. Подробнее: seed/README.md.`;

// ---------------------------------------------------------------------------
// Console helpers
// ---------------------------------------------------------------------------

const log = (...args) => console.log(...args);
const warn = (msg) => console.log(`  [!] ${msg}`);

class Fatal extends Error {}

// ---------------------------------------------------------------------------
// Arguments
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const opts = { ...DEFAULTS, reset: false, dryRun: false, help: false, dirGiven: false };
  const valueOptions = new Set(['api', 'phone', 'code', 'dir']);
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith('--')) throw new Fatal(`Неизвестный аргумент: ${arg}. Справка: --help`);
    const eq = arg.indexOf('=');
    const name = arg.slice(2, eq === -1 ? undefined : eq);
    if (name === 'help' || name === 'h') opts.help = true;
    else if (name === 'reset') opts.reset = true;
    else if (name === 'dry-run') opts.dryRun = true;
    else if (valueOptions.has(name)) {
      let value;
      if (eq !== -1) value = arg.slice(eq + 1);
      else {
        value = argv[i + 1];
        i++;
      }
      if (value === undefined || value === '') throw new Fatal(`Параметр --${name} требует значение`);
      opts[name] = value;
      if (name === 'dir') opts.dirGiven = true;
    } else {
      throw new Fatal(`Неизвестный параметр: --${name}. Справка: --help`);
    }
  }
  opts.api = opts.api.replace(/\/+$/, '');
  opts.dir = path.resolve(opts.dir);
  return opts;
}

// ---------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------

class ApiError extends Error {
  constructor(status, body) {
    const code = body?.code ?? `HTTP_${status}`;
    let text = `${status} ${code}: ${body?.message ?? 'ошибка сервера'}`;
    if (body?.fieldErrors && Object.keys(body.fieldErrors).length > 0) {
      text += ` (${Object.entries(body.fieldErrors).map(([k, v]) => `${k}: ${v}`).join('; ')})`;
    }
    super(text);
    this.status = status;
    this.code = code;
    this.body = body;
  }
}

function createClient(api) {
  let token = null;
  async function request(method, urlPath, { json, form, headers } = {}) {
    const init = { method, headers: { Accept: 'application/json', ...headers } };
    if (token) init.headers.Authorization = `Bearer ${token}`;
    if (json !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(json);
    } else if (form) {
      init.body = form;
    }
    let res;
    try {
      res = await fetch(api + urlPath, init);
    } catch (err) {
      throw new Fatal(
        `Не удалось подключиться к ${api} (${err.cause?.code ?? err.message}). ` +
          'Проверьте, что сервер запущен и адрес --api указан верно.'
      );
    }
    const text = await res.text();
    let body = null;
    if (text) {
      try {
        body = JSON.parse(text);
      } catch {
        body = { message: text.slice(0, 200) };
      }
    }
    if (!res.ok) {
      const error = new ApiError(res.status, body);
      error.retryAfter = Number(res.headers.get('Retry-After')) || null;
      throw error;
    }
    return body;
  }
  return {
    request,
    setToken: (t) => {
      token = t;
    },
  };
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function login(client, opts) {
  let challenge;
  for (let attempt = 0; ; attempt++) {
    try {
      challenge = await client.request('POST', '/auth/otp/request', { json: { phone: opts.phone } });
      break;
    } catch (err) {
      if (err instanceof ApiError && err.status === 429 && attempt === 0 && err.retryAfter
          && err.retryAfter <= MAX_RATE_LIMIT_WAIT_S) {
        log(`Код для этого номера недавно уже запрашивали, жду ${err.retryAfter} с...`);
        await sleep((err.retryAfter + 1) * 1000);
        continue;
      }
      if (err instanceof ApiError) throw new Fatal(`Не удалось запросить код входа: ${err.message}`);
      throw err;
    }
  }
  const code = challenge.devMockCode ?? opts.code;
  let session;
  try {
    session = await client.request('POST', '/auth/otp/verify', { json: { challengeId: challenge.challengeId, code } });
  } catch (err) {
    if (err instanceof ApiError) {
      throw new Fatal(`Не удалось войти с номером ${opts.phone}: ${err.message}. Проверьте --phone и --code.`);
    }
    throw err;
  }
  client.setToken(session.token);

  try {
    await client.request('GET', '/testing/state');
  } catch (err) {
    if (err instanceof ApiError && err.status === 403) {
      throw new Fatal(
        `Номер ${opts.phone} не тестовый аккаунт (${err.code}). Укажите номер из списка тестовых ` +
          '(APP_TESTING_TEST_PHONES на сервере) через --phone.'
      );
    }
    if (err instanceof ApiError && err.status === 404) {
      throw new Fatal(
        'Тестовый API выключен на сервере (GET /testing/state вернул 404). ' +
          'Запустите backend с APP_TESTING_ENABLED=true.'
      );
    }
    if (err instanceof ApiError) throw new Fatal(`Тестовый API недоступен: ${err.message}`);
    throw err;
  }
}

// ---------------------------------------------------------------------------
// Seed folder: reading and validation
// ---------------------------------------------------------------------------

async function exists(p) {
  try {
    await stat(p);
    return true;
  } catch {
    return false;
  }
}

async function readProfilesFile(dir) {
  if (!(await exists(dir))) throw new Fatal(`Папка не найдена: ${dir}`);
  let file = path.join(dir, 'profiles.json');
  if (!(await exists(file))) {
    const example = path.join(dir, 'profiles.example.json');
    if (!(await exists(example))) {
      throw new Fatal(`В папке ${dir} нет ни profiles.json, ни profiles.example.json`);
    }
    log(`profiles.json не найден, использую пример: ${example}`);
    log('(скопируйте его в profiles.json и отредактируйте под себя)\n');
    file = example;
  }
  let text = await readFile(file, 'utf8');
  if (text.charCodeAt(0) === 0xfeff) text = text.slice(1); // BOM from Notepad / PowerShell
  let data;
  try {
    data = JSON.parse(text);
  } catch (err) {
    throw new Fatal(`Ошибка в JSON файла ${file}: ${err.message}`);
  }
  if (!Array.isArray(data)) throw new Fatal(`${file}: ожидается массив анкет [ {...}, {...} ]`);
  return { file, entries: data };
}

/** Image files of a profile folder: by name (natural order), oversized ones skipped, at most 6. */
async function collectPhotos(photosDir, folder, warnings) {
  const dir = path.join(photosDir, folder);
  const shown = `photos/${folder}`;
  let names;
  try {
    names = await readdir(dir);
  } catch {
    warnings.push(`папка с фото не найдена: ${shown} — анкета останется черновиком`);
    return [];
  }
  const images = names
    .filter((name) => PHOTO_EXTENSIONS.has(path.extname(name).toLowerCase()))
    .sort((a, b) => a.localeCompare(b, 'ru', { numeric: true, sensitivity: 'base' }));
  const result = [];
  for (const name of images) {
    const file = path.join(dir, name);
    const info = await stat(file);
    if (!info.isFile()) continue;
    if (info.size > PHOTO_MAX_BYTES) {
      warnings.push(`${name}: ${(info.size / 1024 / 1024).toFixed(2)} МБ — больше 5 МБ, пропускаю`);
      continue;
    }
    if (info.size === 0) {
      warnings.push(`${name}: пустой файл, пропускаю`);
      continue;
    }
    result.push({ name, file, size: info.size, mimeType: PHOTO_EXTENSIONS.get(path.extname(name).toLowerCase()) });
  }
  if (result.length > PHOTO_MAX_COUNT) {
    warnings.push(`фото больше ${PHOTO_MAX_COUNT} — загружу первые ${PHOTO_MAX_COUNT}, пропущено: ` +
      result.slice(PHOTO_MAX_COUNT).map((p) => p.name).join(', '));
    result.length = PHOTO_MAX_COUNT;
  }
  if (result.length === 0) {
    warnings.push(`в ${shown} нет фото (jpg/jpeg/png/webp) — анкета останется черновиком`);
  }
  return result;
}

const isInt = (v) => typeof v === 'number' && Number.isInteger(v);
const isStringArray = (v) => Array.isArray(v) && v.every((x) => typeof x === 'string');

/** Returns { errors, warnings, plan } for one entry. `dictionaries` is null when the API was not reachable. */
async function validateEntry(entry, index, photosDir, dictionaries) {
  const errors = [];
  const warnings = [];
  if (entry === null || typeof entry !== 'object' || Array.isArray(entry)) {
    return { errors: ['анкета должна быть объектом { ... }'], warnings, plan: null };
  }
  for (const key of Object.keys(entry)) {
    if (!ENTRY_FIELDS.has(key)) warnings.push(`неизвестное поле "${key}" — будет проигнорировано`);
  }

  const name = typeof entry.displayName === 'string' ? entry.displayName.trim() : '';
  if (entry.displayName === undefined) errors.push('нет displayName (имя)');
  else if (typeof entry.displayName !== 'string' || name.length < 2 || name.length > 50) {
    errors.push('displayName: строка от 2 до 50 символов');
  }
  if (entry.age === undefined) errors.push('нет age (возраст)');
  else if (!isInt(entry.age) || entry.age < 18 || entry.age > 100) errors.push('age: целое число от 18 до 100');
  if (entry.city === undefined) errors.push('нет city (город)');
  else if (typeof entry.city !== 'string' || entry.city.trim().length < 1 || entry.city.trim().length > 60) {
    errors.push('city: строка от 1 до 60 символов');
  }
  if (entry.aboutText !== undefined && (typeof entry.aboutText !== 'string' || entry.aboutText.length > 1000)) {
    errors.push('aboutText: строка до 1000 символов');
  }
  if (entry.dailyStatusText !== undefined &&
      (typeof entry.dailyStatusText !== 'string' || entry.dailyStatusText.length > 200)) {
    errors.push('dailyStatusText: строка до 200 символов');
  }
  if (entry.heightCm !== undefined && entry.heightCm !== null &&
      (!isInt(entry.heightCm) || entry.heightCm < 120 || entry.heightCm > 230)) {
    errors.push('heightCm: целое число от 120 до 230 (или не указывать)');
  }
  const checkCodes = (field, max, known, label) => {
    const value = entry[field];
    if (value === undefined) return;
    if (!isStringArray(value)) {
      errors.push(`${field}: массив строк`);
      return;
    }
    if (value.length > max) errors.push(`${field}: не больше ${max}`);
    if (known) {
      const unknown = value.filter((c) => !known.has(c));
      if (unknown.length > 0) errors.push(`${field}: неизвестные ${label}: ${unknown.join(', ')}`);
    }
  };
  checkCodes('tagCodes', MAX_TAGS, dictionaries?.tags, 'карточки');
  checkCodes('goalCodes', MAX_GOALS, dictionaries?.goals, 'цели');
  if (entry.premiumDays !== undefined &&
      (!isInt(entry.premiumDays) || entry.premiumDays < 0 || entry.premiumDays > MAX_PREMIUM_DAYS)) {
    errors.push(`premiumDays: целое число от 0 до ${MAX_PREMIUM_DAYS}`);
  }
  if (entry.cosmetics !== undefined) {
    if (!isStringArray(entry.cosmetics)) errors.push('cosmetics: массив кодов предметов');
    else {
      if (entry.cosmetics.length > MAX_COSMETICS) errors.push(`cosmetics: не больше ${MAX_COSMETICS}`);
      const unknown = entry.cosmetics.filter((c) => !KNOWN_COSMETICS.has(c));
      if (unknown.length > 0) warnings.push(`cosmetics: незнакомые коды ${unknown.join(', ')} (сервер может отклонить)`);
    }
  }
  if (entry.publish !== undefined && typeof entry.publish !== 'boolean') errors.push('publish: true или false');

  let photos = [];
  if (entry.folder === undefined || entry.folder === null || entry.folder === '') {
    warnings.push('не указан folder — фото не будет, анкета останется черновиком');
  } else if (typeof entry.folder !== 'string' || /[\\/]|^\.\.?$/.test(entry.folder)) {
    errors.push('folder: имя подпапки внутри photos/ (без / и \\)');
  } else {
    photos = await collectPhotos(photosDir, entry.folder, warnings);
  }

  const profile = {};
  for (const field of PROFILE_FIELDS) {
    if (entry[field] !== undefined && entry[field] !== null) profile[field] = entry[field];
  }
  const label = `${name || '(без имени)'}, ${entry.age ?? '?'}, ${entry.city ?? '?'}`;
  return {
    errors,
    warnings,
    plan: {
      index,
      label,
      folder: entry.folder,
      profile,
      premiumDays: entry.premiumDays,
      cosmetics: entry.cosmetics,
      publish: entry.publish !== false,
      photos,
    },
  };
}

async function loadDictionaries(client, api) {
  try {
    const [tags, goals] = await Promise.all([client.request('GET', '/tags'), client.request('GET', '/goals')]);
    return { tags: new Set(tags.map((t) => t.code)), goals: new Set(goals.map((g) => g.code)) };
  } catch (err) {
    if (err instanceof ApiError) {
      throw new Fatal(`Не удалось получить справочники ${api}/tags и /goals: ${err.message}. Проверьте --api.`);
    }
    throw err;
  }
}

// ---------------------------------------------------------------------------
// Upload
// ---------------------------------------------------------------------------

async function seedOne(client, plan, total) {
  const prefix = `[${plan.index + 1}/${total}] ${plan.label}`;
  const notes = [];
  const body = { profile: plan.profile };
  if (plan.premiumDays !== undefined) body.premiumDays = plan.premiumDays;
  if (plan.cosmetics !== undefined) body.cosmetics = plan.cosmetics;

  let created;
  try {
    created = await client.request('POST', '/testing/profiles', { json: body });
  } catch (err) {
    if (!(err instanceof ApiError)) throw err;
    log(`${prefix} — ОШИБКА при создании: ${err.message}`);
    return 'failed';
  }
  const id = created.accountId;

  let uploaded = 0;
  for (const photo of plan.photos) {
    try {
      const data = await readFile(photo.file);
      const form = new FormData();
      form.append('file', new Blob([data], { type: photo.mimeType }), photo.name);
      await client.request('POST', `/testing/profiles/${id}/photos`, { form });
      uploaded++;
    } catch (err) {
      if (err instanceof Fatal) throw err;
      notes.push(`фото ${photo.name} не загружено: ${err.message}`);
    }
  }

  let outcome;
  if (!plan.publish) {
    log(`${prefix} — черновик (publish: false), фото: ${uploaded}`);
    outcome = 'draft';
  } else if (uploaded === 0) {
    log(`${prefix} — черновик: нет загруженных фото, публикация пропущена`);
    outcome = 'draft';
  } else {
    try {
      await client.request('POST', `/testing/profiles/${id}/publish`);
      log(`${prefix} — опубликована, фото: ${uploaded}`);
      outcome = 'published';
    } catch (err) {
      if (!(err instanceof ApiError)) throw err;
      log(`${prefix} — ОШИБКА публикации (анкета осталась черновиком, id ${id}): ${err.message}`);
      outcome = 'failed';
    }
  }
  notes.forEach(warn);
  return outcome;
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

async function main() {
  if (typeof fetch !== 'function' || typeof FormData !== 'function' || typeof Blob !== 'function') {
    throw new Fatal(`Нужен Node.js 18 или новее (сейчас ${process.version}).`);
  }
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    log(HELP);
    return 0;
  }

  const client = createClient(opts.api);
  log(`Папка: ${opts.dir}`);
  const { entries } = await readProfilesFile(opts.dir);
  if (entries.length === 0) {
    log('Список анкет пуст — нечего загружать.');
    return 0;
  }

  let dictionaries = null;
  try {
    dictionaries = await loadDictionaries(client, opts.api);
  } catch (err) {
    if (!opts.dryRun || !(err instanceof Fatal)) throw err;
    log(`${err.message}\nКоды карточек и целей не проверены.\n`);
  }

  // Validate everything before sending anything.
  const photosDir = path.join(opts.dir, 'photos');
  const plans = [];
  let errorCount = 0;
  const seenFolders = new Set();
  for (let i = 0; i < entries.length; i++) {
    const { errors, warnings, plan } = await validateEntry(entries[i], i, photosDir, dictionaries);
    if (plan?.folder && typeof plan.folder === 'string') {
      if (seenFolders.has(plan.folder)) warnings.push(`папка "${plan.folder}" уже используется другой анкетой`);
      seenFolders.add(plan.folder);
    }
    const title = `[${i + 1}/${entries.length}] ${plan?.label ?? ''}${plan?.folder ? ` (${plan.folder})` : ''}`;
    if (errors.length > 0 || warnings.length > 0 || opts.dryRun) {
      const summary = plan && errors.length === 0
        ? ` — фото: ${plan.photos.length}, публикация: ${plan.publish && plan.photos.length > 0 ? 'да' : 'нет'}`
        : '';
      log(`${title}${summary}`);
      errors.forEach((e) => log(`  [ОШИБКА] ${e}`));
      warnings.forEach(warn);
    }
    errorCount += errors.length;
    if (plan) plans.push(plan);
  }
  if (errorCount > 0) {
    log(`\nНайдено ошибок: ${errorCount}. Исправьте profiles.json — ничего не отправлено.`);
    return 1;
  }
  if (opts.dryRun) {
    log(`\nПроверка пройдена: анкет ${plans.length}, фото ${plans.reduce((n, p) => n + p.photos.length, 0)}.` +
      ` Это был --dry-run: ничего не отправлено${opts.reset ? ' (--reset тоже пропущен)' : ''}.`);
    return 0;
  }

  log(`\nВход: ${opts.phone} → ${opts.api}`);
  await login(client, opts);

  if (opts.reset) {
    const result = await client.request('DELETE', '/testing/profiles');
    log(`--reset: удалено ранее созданных тестовых анкет: ${result.deleted}`);
  }

  log(`Загружаю анкеты: ${plans.length}\n`);
  const counts = { published: 0, draft: 0, failed: 0 };
  for (const plan of plans) {
    counts[await seedOne(client, plan, plans.length)]++;
  }
  log(`\nИтого: опубликовано ${counts.published}, черновиков ${counts.draft}, ошибок ${counts.failed} ` +
    `(всего ${plans.length}).`);
  log('Анкеты видны в ленте только тестовым аккаунтам.');
  return counts.failed > 0 ? 1 : 0;
}

main().then(
  (code) => {
    process.exitCode = code;
  },
  (err) => {
    if (err instanceof Fatal) console.error(`\nОшибка: ${err.message}`);
    else if (err instanceof ApiError) console.error(`\nОшибка сервера: ${err.message}`);
    else console.error('\nНепредвиденная ошибка:', err);
    process.exitCode = 1;
  }
);
