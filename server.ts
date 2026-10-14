import express, { Request, Response, NextFunction } from 'express';
import { createServer as createViteServer } from 'vite';
import crypto from 'crypto';

interface SessionRecord {
  id: string;
  accountId: string;
  tokenHash: string;
  expiresAt: number;
}

interface AccountRecord {
  id: string;
  phoneHash: string;
  phoneMasked: string;
  status: 'ACTIVE' | 'DELETED';
  createdAt: string;
}

interface TagRecord {
  code: string;
  category: string;
  categoryLabel: string;
  label: string;
  description: string;
  sortOrder: number;
}

interface PhotoRecord {
  id: string;
  profileId: string;
  url: string;
  mimeType: string;
  sizeBytes: number;
  sortOrder: number;
  isPrimary: boolean;
  createdAt: string;
  deletedAt?: string | null;
}

interface ProfileRecord {
  id: string;
  accountId: string;
  displayName: string;
  age: number;
  city: string;
  aboutText: string;
  publicationStatus: 'DRAFT' | 'PUBLISHED' | 'HIDDEN';
  visibilityStatus: 'PUBLIC' | 'HIDDEN';
  dailyStatusText?: string | null;
  dailyStatusExpiresAt?: number | null;
  tagCodes: string[];
  photos: PhotoRecord[];
  createdAt: string;
  updatedAt: string;
}

const TAGS: TagRecord[] = [
  { code: 'PACE_ASYNCHRONOUS', category: 'pace', categoryLabel: 'Темп переписки', label: 'Асинхронный ответ', description: 'Отвечаю в течение дня без спешки и не жду мгновенных ответов', sortOrder: 1 },
  { code: 'PACE_FAST_CHAT', category: 'pace', categoryLabel: 'Темп переписки', label: 'Люблю живой диалог', description: 'Когда свободен(-на), переписываюсь в моменте', sortOrder: 2 },
  { code: 'PACE_LOW_BATTERY', category: 'pace', categoryLabel: 'Темп переписки', label: 'Иногда беру паузу', description: 'Могу на пару дней уйти в офлайн, это не значит потерю интереса', sortOrder: 3 },
  { code: 'FORMAT_WALK_FIRST', category: 'format', categoryLabel: 'Формат первой встречи', label: 'Прогулка на воздухе', description: 'Спокойная прогулка по парку или городу без шума заведений', sortOrder: 10 },
  { code: 'FORMAT_COFFEE_SHORT', category: 'format', categoryLabel: 'Формат первой встречи', label: 'Кофе на 30–40 минут', description: 'Короткая встреча без давления, чтобы познакомиться вживую', sortOrder: 11 },
  { code: 'FORMAT_TEXT_BEFORE_MEET', category: 'format', categoryLabel: 'Формат первой встречи', label: 'Сначала переписка', description: 'Предпочитаю пообщаться несколько дней перед первой встречей', sortOrder: 12 },
  { code: 'FORMAT_SHARED_ACTIVITY', category: 'format', categoryLabel: 'Формат первой встречи', label: 'Совместное занятие', description: 'Выставка, настолки или музей вместо сидения друг напротив друга', sortOrder: 13 },
  { code: 'CALLS_TEXT_ONLY', category: 'calls', categoryLabel: 'Звонки и сообщения', label: 'Только текст', description: 'Без неожиданных звонков и голосовых сообщений', sortOrder: 20 },
  { code: 'CALLS_VOICE_WELCOME', category: 'calls', categoryLabel: 'Звонки и сообщения', label: 'Голосовые ок', description: 'Удобно слушать и отправлять аудиосообщения', sortOrder: 21 },
  { code: 'CALLS_PRE_CALL_CHECK', category: 'calls', categoryLabel: 'Звонки и сообщения', label: 'Звонок по согласованию', description: 'Не против созвониться, если заранее договорились о времени', sortOrder: 22 },
  { code: 'BOUND_NO_SMALLTALK', category: 'boundaries', categoryLabel: 'Комфорт и темы', label: 'Сразу к интересным темам', description: 'Меньше протокольных «как дела», больше разговоров о том, что вдохновляет', sortOrder: 30 },
  { code: 'BOUND_RESPECT_LIMITS', category: 'boundaries', categoryLabel: 'Комфорт и темы', label: 'Без настойчивости', description: 'Ценю, когда уважают ответ «нет» или просьбу сменить тему', sortOrder: 31 },
  { code: 'BOUND_DIRECT_COMMUNICATION', category: 'boundaries', categoryLabel: 'Комфорт и темы', label: 'Прямота без намеков', description: 'Предпочитаю ясные слова догадкам и полунамекам', sortOrder: 32 },
  { code: 'SOC_QUIET_SPACES', category: 'social', categoryLabel: 'Социальная среда', label: 'Тихие места', description: 'Быстро утомляюсь в шумных толпах и клубах, выбираю уютные пространства', sortOrder: 40 },
  { code: 'SOC_DEEP_TALKS', category: 'social', categoryLabel: 'Социальная среда', label: 'Глубокие разговоры', description: 'Интереснее обсудить идеи, книги, взгляды на мир, чем светскую суету', sortOrder: 41 },
];

// In-memory repositories initialized with realistic initial data
const accounts: Map<string, AccountRecord> = new Map();
const sessions: Map<string, SessionRecord> = new Map(); // tokenHash -> SessionRecord
const challenges: Map<string, { id: string; phoneHash: string; codeHash: string; expiresAt: number; attempts: number }> = new Map();
const profiles: Map<string, ProfileRecord> = new Map(); // accountId -> ProfileRecord

// Helper to seed initial profiles
function seedInitialData() {
  const seedUsers = [
    {
      name: 'Анна', age: 26, city: 'Москва',
      about: 'Архитектор городской среды. Люблю долгие пешие прогулки по переулкам, кофе без сиропа и плёночную фотографию.',
      tags: ['PACE_ASYNCHRONOUS', 'FORMAT_WALK_FIRST', 'CALLS_TEXT_ONLY', 'BOUND_DIRECT_COMMUNICATION', 'SOC_QUIET_SPACES'],
      photo: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=800&q=80',
      daily: 'В субботу иду на выставку современной графики',
    },
    {
      name: 'Михаил', age: 29, city: 'Санкт-Петербург',
      about: 'Разрабатываю инди-игры, читаю научпоп и играю на акустической гитаре. Ищу человека, с которым комфортно молчать и обсуждать книги.',
      tags: ['PACE_LOW_BATTERY', 'FORMAT_COFFEE_SHORT', 'CALLS_TEXT_ONLY', 'SOC_DEEP_TALKS'],
      photo: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?auto=format&fit=crop&w=800&q=80',
      daily: null,
    },
    {
      name: 'Елена', age: 24, city: 'Москва',
      about: 'Книжный редактор и любительница керамики. Рада неспешному знакомству и общению на равных.',
      tags: ['PACE_ASYNCHRONOUS', 'FORMAT_TEXT_BEFORE_MEET', 'BOUND_NO_SMALLTALK', 'SOC_QUIET_SPACES'],
      photo: 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?auto=format&fit=crop&w=800&q=80',
      daily: 'Заварила зеленый чай, читаю новую главу',
    },
    {
      name: 'Дмитрий', age: 31, city: 'Екатеринбург',
      about: 'Биофизик, увлекаюсь треккингом на Урале и астрономией. Предпочитаю сначала содержательно поговорить в чате.',
      tags: ['PACE_FAST_CHAT', 'FORMAT_SHARED_ACTIVITY', 'CALLS_PRE_CALL_CHECK', 'BOUND_DIRECT_COMMUNICATION'],
      photo: 'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?auto=format&fit=crop&w=800&q=80',
      daily: null,
    },
    {
      name: 'София', age: 27, city: 'Казань',
      about: 'Преподаватель лингвистики, фанат чая и настольных стратегий. Легко общаюсь текстом и голосовыми.',
      tags: ['PACE_FAST_CHAT', 'FORMAT_SHARED_ACTIVITY', 'CALLS_VOICE_WELCOME', 'SOC_DEEP_TALKS'],
      photo: 'https://images.unsplash.com/photo-1524504388940-b1c1722653e1?auto=format&fit=crop&w=800&q=80',
      daily: 'Собираю команду на партию в Catan в эти выходные',
    },
  ];

  seedUsers.forEach((u, i) => {
    const accId = `acc-seed-${i + 1}`;
    const profId = `prof-seed-${i + 1}`;
    accounts.set(accId, {
      id: accId,
      phoneHash: crypto.createHash('sha256').update(`+7999000000${i}`).digest('hex'),
      phoneMasked: `+7 (***) ***-**-0${i}`,
      status: 'ACTIVE',
      createdAt: new Date().toISOString(),
    });

    profiles.set(accId, {
      id: profId,
      accountId: accId,
      displayName: u.name,
      age: u.age,
      city: u.city,
      aboutText: u.about,
      publicationStatus: 'PUBLISHED',
      visibilityStatus: 'PUBLIC',
      dailyStatusText: u.daily,
      dailyStatusExpiresAt: u.daily ? Date.now() + 24 * 3600 * 1000 : null,
      tagCodes: u.tags,
      photos: [
        {
          id: `photo-seed-${i + 1}-1`,
          profileId: profId,
          url: u.photo,
          mimeType: 'image/jpeg',
          sizeBytes: 420000,
          sortOrder: 0,
          isPrimary: true,
          createdAt: new Date().toISOString(),
        },
      ],
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    });
  });
}
seedInitialData();

function sha256(text: string): string {
  return crypto.createHash('sha256').update(text).digest('hex');
}

function calculateCompleteness(p: ProfileRecord): number {
  let score = 0;
  if (p.displayName && p.displayName.trim().length > 1) score += 20;
  if (p.age >= 18) score += 15;
  if (p.city && p.city.trim().length > 0) score += 15;
  if (p.aboutText && p.aboutText.trim().length >= 10) score += 25;
  if (p.tagCodes && p.tagCodes.length >= 3) score += 25;
  return Math.min(100, score);
}

function formatProfileResponse(p: ProfileRecord) {
  const isDailyExpired = p.dailyStatusExpiresAt && Date.now() > p.dailyStatusExpiresAt;
  const tagObjects = p.tagCodes
    .map((code) => TAGS.find((t) => t.code === code))
    .filter(Boolean);

  return {
    id: p.id,
    accountId: p.accountId,
    displayName: p.displayName,
    age: p.age,
    city: p.city,
    aboutText: p.aboutText,
    publicationStatus: p.publicationStatus,
    visibilityStatus: p.visibilityStatus,
    dailyStatusText: isDailyExpired ? null : p.dailyStatusText,
    tags: tagObjects,
    photos: p.photos.filter((ph) => !ph.deletedAt),
    completenessPercent: calculateCompleteness(p),
    createdAt: p.createdAt,
    updatedAt: p.updatedAt,
  };
}

// Authentication middleware
interface AuthenticatedRequest extends Request {
  accountId?: string;
}

function authenticateToken(req: AuthenticatedRequest, res: Response, next: NextFunction): void {
  const authHeader = req.headers['authorization'];
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    res.status(401).json({
      code: 'UNAUTHORIZED',
      message: 'Требуется авторизация',
      traceId: crypto.randomUUID().substring(0, 8),
    });
    return;
  }

  const token = authHeader.substring(7).trim();
  const tokenHash = sha256(token);
  const session = sessions.get(tokenHash);

  if (!session || Date.now() > session.expiresAt) {
    res.status(401).json({
      code: 'SESSION_EXPIRED',
      message: 'Сессия истекла или недействительна',
      traceId: crypto.randomUUID().substring(0, 8),
    });
    return;
  }

  req.accountId = session.accountId;
  next();
}

async function startServer() {
  const app = express();
  app.use(express.json({ limit: '10mb' }));

  // API router
  const apiRouter = express.Router();

  // --- 1. HEALTH ---
  apiRouter.get('/health', (_req, res) => {
    res.json({ status: 'UP', timestamp: new Date().toISOString() });
  });

  // --- 2. TAGS ---
  apiRouter.get('/tags', (_req, res) => {
    res.json(TAGS);
  });

  // --- 3. AUTH (OTP) ---
  apiRouter.post('/auth/otp/request', (req, res) => {
    const { phone } = req.body;
    if (!phone || typeof phone !== 'string' || phone.trim().length < 10) {
      res.status(400).json({
        code: 'VALIDATION_ERROR',
        message: 'Укажите корректный номер телефона',
        traceId: crypto.randomUUID().substring(0, 8),
      });
      return;
    }

    const cleaned = phone.replace(/[^\d+]/g, '');
    const phoneHash = sha256(cleaned);
    const last2 = cleaned.slice(-2);
    const masked = `+7 (***) ***-**-${last2}`;

    const challengeId = crypto.randomUUID();
    const mockCode = '123456';
    const codeHash = sha256(mockCode);

    challenges.set(challengeId, {
      id: challengeId,
      phoneHash,
      codeHash,
      expiresAt: Date.now() + 5 * 60 * 1000,
      attempts: 0,
    });

    res.json({
      challengeId,
      expiresAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
      maskedPhone: masked,
      devMockCode: mockCode,
    });
  });

  apiRouter.post('/auth/otp/verify', (req, res) => {
    const { challengeId, code } = req.body;
    const phone = req.query.phone as string;

    const ch = challenges.get(challengeId);
    if (!ch) {
      res.status(400).json({
        code: 'INVALID_CHALLENGE',
        message: 'Запрос подтверждения не найден',
        traceId: crypto.randomUUID().substring(0, 8),
      });
      return;
    }

    if (Date.now() > ch.expiresAt) {
      res.status(400).json({
        code: 'CHALLENGE_EXPIRED',
        message: 'Срок действия кода истёк',
        traceId: crypto.randomUUID().substring(0, 8),
      });
      return;
    }

    ch.attempts++;
    if (ch.attempts > 5) {
      challenges.delete(challengeId);
      res.status(400).json({
        code: 'MAX_ATTEMPTS_EXCEEDED',
        message: 'Превышено максимальное количество попыток ввода кода',
        traceId: crypto.randomUUID().substring(0, 8),
      });
      return;
    }

    if (sha256(code) !== ch.codeHash) {
      res.status(400).json({
        code: 'INVALID_CODE',
        message: 'Неверный код подтверждения (для теста используйте 123456)',
        traceId: crypto.randomUUID().substring(0, 8),
      });
      return;
    }

    // Success: consume challenge
    challenges.delete(challengeId);

    // Find or create account
    let account = Array.from(accounts.values()).find((a) => a.phoneHash === ch.phoneHash);
    if (!account) {
      const accId = 'acc-' + crypto.randomUUID().substring(0, 8);
      const digits = phone ? phone.replace(/[^\d+]/g, '') : '';
      const masked = digits.length >= 2 ? `+7 (***) ***-**-${digits.slice(-2)}` : '+7 (***) ***-**-**';
      account = {
        id: accId,
        phoneHash: ch.phoneHash,
        phoneMasked: masked,
        status: 'ACTIVE',
        createdAt: new Date().toISOString(),
      };
      accounts.set(accId, account);

      // Create default profile
      const profId = 'prof-' + accId;
      profiles.set(accId, {
        id: profId,
        accountId: accId,
        displayName: 'Новая анкета',
        age: 24,
        city: 'Москва',
        aboutText: 'Привет! Заполняю анкету.',
        publicationStatus: 'DRAFT',
        visibilityStatus: 'PUBLIC',
        tagCodes: ['PACE_ASYNCHRONOUS', 'FORMAT_WALK_FIRST'],
        photos: [],
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      });
    }

    // Issue session token
    const rawToken = crypto.randomBytes(32).toString('hex');
    const tokenHash = sha256(rawToken);
    const expiresAt = Date.now() + 30 * 24 * 3600 * 1000;

    sessions.set(tokenHash, {
      id: crypto.randomUUID(),
      accountId: account.id,
      tokenHash,
      expiresAt,
    });

    res.json({
      token: rawToken,
      expiresAt: new Date(expiresAt).toISOString(),
      accountId: account.id,
      account: {
        id: account.id,
        phoneMasked: account.phoneMasked,
        status: account.status,
      },
    });
  });

  apiRouter.post('/auth/logout', authenticateToken, (req: AuthenticatedRequest, res) => {
    const authHeader = req.headers['authorization'];
    if (authHeader && authHeader.startsWith('Bearer ')) {
      const token = authHeader.substring(7).trim();
      sessions.delete(sha256(token));
    }
    res.status(204).send();
  });

  // --- 4. ACCOUNT ---
  apiRouter.get('/me', authenticateToken, (req: AuthenticatedRequest, res) => {
    const account = accounts.get(req.accountId!);
    if (!account) {
      res.status(404).json({ code: 'NOT_FOUND', message: 'Аккаунт не найден' });
      return;
    }
    res.json(account);
  });

  apiRouter.delete('/me', authenticateToken, (req: AuthenticatedRequest, res) => {
    const account = accounts.get(req.accountId!);
    if (account) {
      account.status = 'DELETED';
      profiles.delete(req.accountId!);
      // Revoke all sessions for this account
      for (const [hash, s] of sessions.entries()) {
        if (s.accountId === req.accountId) {
          sessions.delete(hash);
        }
      }
    }
    res.status(204).send();
  });

  // --- 5. PROFILE ---
  apiRouter.get('/me/profile', authenticateToken, (req: AuthenticatedRequest, res) => {
    const profile = profiles.get(req.accountId!);
    if (!profile) {
      res.status(404).json({ code: 'PROFILE_NOT_FOUND', message: 'Профиль не найден' });
      return;
    }
    res.json(formatProfileResponse(profile));
  });

  apiRouter.put('/me/profile', authenticateToken, (req: AuthenticatedRequest, res) => {
    let profile = profiles.get(req.accountId!);
    if (!profile) {
      profile = {
        id: 'prof-' + req.accountId,
        accountId: req.accountId!,
        displayName: 'Пользователь',
        age: 20,
        city: 'Москва',
        aboutText: '',
        publicationStatus: 'DRAFT',
        visibilityStatus: 'PUBLIC',
        tagCodes: [],
        photos: [],
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      profiles.set(req.accountId!, profile);
    }

    const { displayName, age, city, aboutText, tagCodes, dailyStatusText } = req.body;
    if (displayName !== undefined) profile.displayName = String(displayName).trim();
    if (age !== undefined) profile.age = Number(age);
    if (city !== undefined) profile.city = String(city).trim();
    if (aboutText !== undefined) profile.aboutText = String(aboutText).trim();
    if (tagCodes !== undefined && Array.isArray(tagCodes)) profile.tagCodes = tagCodes;
    if (dailyStatusText !== undefined) {
      if (!dailyStatusText) {
        profile.dailyStatusText = null;
        profile.dailyStatusExpiresAt = null;
      } else {
        profile.dailyStatusText = String(dailyStatusText).trim();
        profile.dailyStatusExpiresAt = Date.now() + 24 * 3600 * 1000;
      }
    }
    profile.updatedAt = new Date().toISOString();

    res.json(formatProfileResponse(profile));
  });

  apiRouter.post('/me/profile/publish', authenticateToken, (req: AuthenticatedRequest, res) => {
    const profile = profiles.get(req.accountId!);
    if (!profile) {
      res.status(404).json({ code: 'NOT_FOUND', message: 'Профиль не найден' });
      return;
    }
    profile.publicationStatus = 'PUBLISHED';
    profile.updatedAt = new Date().toISOString();
    res.json(formatProfileResponse(profile));
  });

  apiRouter.post('/me/profile/hide', authenticateToken, (req: AuthenticatedRequest, res) => {
    const profile = profiles.get(req.accountId!);
    if (!profile) {
      res.status(404).json({ code: 'NOT_FOUND', message: 'Профиль не найден' });
      return;
    }
    profile.publicationStatus = 'HIDDEN';
    profile.updatedAt = new Date().toISOString();
    res.json(formatProfileResponse(profile));
  });

  // --- 6. PHOTOS ---
  apiRouter.get('/me/profile/photos', authenticateToken, (req: AuthenticatedRequest, res) => {
    const profile = profiles.get(req.accountId!);
    if (!profile) {
      res.json([]);
      return;
    }
    res.json(profile.photos.filter((p) => !p.deletedAt));
  });

  apiRouter.post('/me/profile/photos', authenticateToken, (req: AuthenticatedRequest, res) => {
    const profile = profiles.get(req.accountId!);
    if (!profile) {
      res.status(404).json({ code: 'NOT_FOUND', message: 'Профиль не найден' });
      return;
    }

    const { dataUrl, mimeType, sizeBytes } = req.body;
    if (!dataUrl || !mimeType) {
      res.status(400).json({ code: 'INVALID_FILE', message: 'Файл не передан' });
      return;
    }

    const allowed = ['image/jpeg', 'image/png', 'image/webp'];
    if (!allowed.includes(mimeType)) {
      res.status(400).json({ code: 'UNSUPPORTED_MIME', message: 'Разрешены только форматы JPEG, PNG и WebP' });
      return;
    }

    if (sizeBytes && sizeBytes > 5 * 1024 * 1024) {
      res.status(400).json({ code: 'FILE_TOO_LARGE', message: 'Размер файла превышает 5 МБ' });
      return;
    }

    const activePhotos = profile.photos.filter((p) => !p.deletedAt);
    if (activePhotos.length >= 6) {
      res.status(400).json({ code: 'LIMIT_EXCEEDED', message: 'Максимум 6 фотографий в профиле' });
      return;
    }

    const newPhoto: PhotoRecord = {
      id: 'photo-' + crypto.randomUUID().substring(0, 8),
      profileId: profile.id,
      url: dataUrl,
      mimeType,
      sizeBytes: sizeBytes || 300000,
      sortOrder: activePhotos.length,
      isPrimary: activePhotos.length === 0,
      createdAt: new Date().toISOString(),
    };

    profile.photos.push(newPhoto);
    profile.updatedAt = new Date().toISOString();

    res.json(newPhoto);
  });

  apiRouter.patch('/me/profile/photos/:photoId', authenticateToken, (req: AuthenticatedRequest, res) => {
    const profile = profiles.get(req.accountId!);
    if (!profile) {
      res.status(404).json({ code: 'NOT_FOUND', message: 'Профиль не найден' });
      return;
    }

    const { photoId } = req.params;
    let found = false;
    profile.photos.forEach((p) => {
      if (p.id === photoId) {
        p.isPrimary = true;
        found = true;
      } else {
        p.isPrimary = false;
      }
    });

    if (!found) {
      res.status(404).json({ code: 'PHOTO_NOT_FOUND', message: 'Фотография не найдена' });
      return;
    }

    profile.updatedAt = new Date().toISOString();
    res.status(204).send();
  });

  apiRouter.delete('/me/profile/photos/:photoId', authenticateToken, (req: AuthenticatedRequest, res) => {
    const profile = profiles.get(req.accountId!);
    if (!profile) {
      res.status(404).json({ code: 'NOT_FOUND', message: 'Профиль не найден' });
      return;
    }

    const { photoId } = req.params;
    const photo = profile.photos.find((p) => p.id === photoId);
    if (photo) {
      photo.deletedAt = new Date().toISOString();
      const remaining = profile.photos.filter((p) => !p.deletedAt);
      if (photo.isPrimary && remaining.length > 0) {
        remaining[0].isPrimary = true;
      }
      profile.updatedAt = new Date().toISOString();
    }

    res.status(204).send();
  });

  // --- 7. DISCOVERY ---
  apiRouter.get('/discovery/profiles', (req: AuthenticatedRequest, res) => {
    // Optional caller identity to exclude own profile
    let callerAccountId: string | undefined;
    const authHeader = req.headers['authorization'];
    if (authHeader && authHeader.startsWith('Bearer ')) {
      const token = authHeader.substring(7).trim();
      const s = sessions.get(sha256(token));
      if (s) callerAccountId = s.accountId;
    }

    const { city, minAge, maxAge, tagCodes, tagLogic, page = '0', limit = '20' } = req.query;

    let items = Array.from(profiles.values()).filter(
      (p) => p.publicationStatus === 'PUBLISHED' && p.visibilityStatus === 'PUBLIC'
    );

    // Exclude own profile
    if (callerAccountId) {
      items = items.filter((p) => p.accountId !== callerAccountId);
    }

    // City filter
    if (city && typeof city === 'string' && city.toUpperCase() !== 'ALL') {
      items = items.filter((p) => p.city.toLowerCase() === city.toLowerCase());
    }

    // Age range
    if (minAge) items = items.filter((p) => p.age >= Number(minAge));
    if (maxAge) items = items.filter((p) => p.age <= Number(maxAge));

    // Tag filtering with AND / OR logic
    if (tagCodes) {
      const requestedTags = (Array.isArray(tagCodes) ? tagCodes : String(tagCodes).split(',')) as string[];
      const isAnd = String(tagLogic).toUpperCase() === 'AND';

      items = items.filter((p) => {
        if (isAnd) {
          return requestedTags.every((t) => p.tagCodes.includes(t));
        } else {
          return requestedTags.some((t) => p.tagCodes.includes(t));
        }
      });
    }

    const pageNum = Math.max(0, parseInt(String(page), 10) || 0);
    const limitNum = Math.min(50, Math.max(1, parseInt(String(limit), 10) || 20));
    const totalElements = items.length;
    const totalPages = Math.ceil(totalElements / limitNum);

    const start = pageNum * limitNum;
    const pagedItems = items.slice(start, start + limitNum).map(formatProfileResponse);

    res.json({
      items: pagedItems,
      page: pageNum,
      limit: limitNum,
      totalElements,
      totalPages,
    });
  });

  // Mount API router
  app.use('/api/v1', apiRouter);

  // Mount Vite development middlewares in dev
  const isProd = process.env.NODE_ENV === 'production';
  if (!isProd) {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    app.use(express.static('dist'));
    app.get('*', (_req, res) => {
      res.sendFile('dist/index.html', { root: '.' });
    });
  }

  const port = process.env.PORT || 3000;
  app.listen(port, () => {
    console.log(`Dating full-stack server running on http://0.0.0.0:${port}`);
  });
}

startServer().catch((err) => {
  console.error('Failed to start server:', err);
  process.exit(1);
});
