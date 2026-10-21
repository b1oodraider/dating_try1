import type {
  Account,
  Achievement,
  AchievementDefinition,
  AchievementReward,
  AdminAccount,
  AdminGoal,
  AdminTag,
  AdminTagCategory,
  Author,
  AuthorInput,
  CatalogEntry,
  CatalogEntryCreate,
  CatalogEntryUpdate,
  CatalogKind,
  DeleteResult,
  FaqInput,
  FaqItem,
  PremiumAllowance,
  PremiumView,
  AdminPage,
  AdminRestriction,
  AdminRoles,
  AuditEntry,
  Behavior,
  ModerationReport,
  ModerationStatus,
  ReportDecisionRequest,
  RestrictionKind,
  Risk,
  RiskQueueItem,
  StaffRole,
  SupportQueueItem,
  SupportStaffTicket,
  SupportStatus,
  VerificationReview,
  ApiErrorBody,
  AuthChallenge,
  AuthToken,
  Banner,
  BoostStatus,
  DiscoveryPage,
  DiscoveryQuery,
  ExcludedGoals,
  ExclusionWords,
  GachaHistoryItem,
  GachaPullResponse,
  GachaShardsInfo,
  Goal,
  Match,
  Message,
  MyProfile,
  Photo,
  PhotoReviewItem,
  ProfileUpdate,
  ReactionResult,
  ReadState,
  ReceivedLike,
  ReceivedLikesCount,
  ReportResponse,
  Restriction,
  ShardExchangeResponse,
  Tag,
  TestingState,
  VerificationRequest,
  Wallet,
} from '../types';

const API_BASE = '/api/v1';
const STORAGE_KEY_TOKEN = 'dating_mvp_token_v1';

const NETWORK_ERROR_MESSAGE =
  'Нет соединения с сервером. Проверьте подключение к интернету и попробуйте ещё раз.';

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

export class ApiError extends Error {
  readonly code: string;
  /** HTTP status; 0 means the request never reached the server (network error). */
  readonly status: number;
  readonly traceId?: string;
  readonly fieldErrors?: Record<string, string>;
  /** Seconds from the Retry-After header (429). */
  readonly retryAfterSeconds?: number;
  /** Machine-readable context, e.g. { feature } for PREMIUM_REQUIRED. */
  readonly details?: Record<string, unknown>;

  constructor(params: {
    code: string;
    message: string;
    status: number;
    traceId?: string;
    fieldErrors?: Record<string, string>;
    retryAfterSeconds?: number;
    details?: Record<string, unknown>;
  }) {
    super(params.message);
    this.name = 'ApiError';
    this.code = params.code;
    this.status = params.status;
    this.traceId = params.traceId;
    this.fieldErrors = params.fieldErrors;
    this.retryAfterSeconds = params.retryAfterSeconds;
    this.details = params.details;
  }

  get isNetworkError(): boolean {
    return this.status === 0;
  }

  get isUnauthorized(): boolean {
    return this.status === 401;
  }

  get isPremiumRequired(): boolean {
    return this.code === 'PREMIUM_REQUIRED';
  }

  /** details.feature of a PREMIUM_REQUIRED error (null for other errors). */
  get premiumFeature(): string | null {
    if (!this.isPremiumRequired) return null;
    const feature = this.details?.feature;
    return typeof feature === 'string' ? feature : null;
  }
}

export function isAbortError(e: unknown): boolean {
  return e instanceof DOMException && e.name === 'AbortError';
}

/** User-facing message for any thrown value. */
export function getErrorMessage(e: unknown, fallback = 'Что-то пошло не так. Попробуйте ещё раз.'): string {
  if (e instanceof ApiError) {
    if (e.code === 'ACCOUNT_RESTRICTED') {
      const until = typeof e.details?.until === 'string' ? e.details.until : null;
      return `${e.message} ${until ? `до ${formatDateTime(until)}` : 'до решения модератора'}.`;
    }
    if (e.status === 429 && e.retryAfterSeconds) {
      return `${e.message} Повторите через ${e.retryAfterSeconds} с.`;
    }
    return e.message || fallback;
  }
  if (e instanceof Error && e.message) return e.message;
  return fallback;
}

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('ru-RU', { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' });
}

function defaultMessageForStatus(status: number): string {
  if (status === 401) return 'Сессия истекла. Войдите снова.';
  if (status === 403) return 'Недостаточно прав для этого действия.';
  if (status === 404) return 'Не найдено.';
  if (status === 413) return 'Файл слишком большой.';
  if (status === 415) return 'Неподдерживаемый формат файла.';
  if (status === 429) return 'Слишком много запросов.';
  if (status >= 500) return 'Сервер временно недоступен. Попробуйте позже.';
  return 'Ошибка запроса.';
}

function isApiErrorBody(value: unknown): value is ApiErrorBody {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as ApiErrorBody).code === 'string' &&
    typeof (value as ApiErrorBody).message === 'string'
  );
}

async function parseError(res: Response): Promise<ApiError> {
  let body: unknown = null;
  try {
    body = await res.json();
  } catch {
    body = null;
  }
  const retryAfterRaw = res.headers.get('Retry-After');
  const retryAfter = retryAfterRaw ? Number.parseInt(retryAfterRaw, 10) : NaN;
  const traceId = res.headers.get('X-Trace-Id') ?? undefined;

  if (isApiErrorBody(body)) {
    return new ApiError({
      code: body.code,
      message: body.message,
      status: res.status,
      traceId: body.traceId ?? traceId,
      fieldErrors: body.fieldErrors,
      retryAfterSeconds: Number.isFinite(retryAfter) ? retryAfter : undefined,
      details:
        typeof body.details === 'object' && body.details !== null && !Array.isArray(body.details)
          ? body.details
          : undefined,
    });
  }
  return new ApiError({
    code: res.status >= 500 ? 'INTERNAL_ERROR' : 'HTTP_' + res.status,
    message: defaultMessageForStatus(res.status),
    status: res.status,
    traceId,
    retryAfterSeconds: Number.isFinite(retryAfter) ? retryAfter : undefined,
  });
}

// ---------------------------------------------------------------------------
// Token storage & unauthorized notifications
// ---------------------------------------------------------------------------

function getToken(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY_TOKEN);
  } catch {
    return null;
  }
}

function setToken(token: string | null): void {
  try {
    if (token) {
      localStorage.setItem(STORAGE_KEY_TOKEN, token);
    } else {
      localStorage.removeItem(STORAGE_KEY_TOKEN);
    }
  } catch {
    // Storage unavailable (private mode etc.) — the session simply won't survive a reload.
  }
}

type UnauthorizedListener = () => void;
const unauthorizedListeners = new Set<UnauthorizedListener>();

/** Subscribe to "token is no longer valid" events. Returns an unsubscribe function. */
export function onUnauthorized(listener: UnauthorizedListener): () => void {
  unauthorizedListeners.add(listener);
  return () => {
    unauthorizedListeners.delete(listener);
  };
}

/** Window event fired when the server refused an action because of a restriction (the banner reloads). */
export const RESTRICTIONS_CHANGED = 'dating:restrictions-changed';

function handleUnauthorized(): void {
  setToken(null);
  unauthorizedListeners.forEach((listener) => listener());
}

// ---------------------------------------------------------------------------
// Request helper
// ---------------------------------------------------------------------------

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  /** JSON-serialisable body or FormData (multipart; Content-Type is set by the browser). */
  body?: unknown;
  /** Whether to send the bearer token. Default: true. */
  auth?: boolean;
  signal?: AbortSignal;
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, auth = true, signal } = options;
  const headers: Record<string, string> = { Accept: 'application/json' };

  if (auth) {
    const token = getToken();
    if (!token) {
      throw new ApiError({ code: 'UNAUTHORIZED', message: 'Требуется авторизация.', status: 401 });
    }
    headers['Authorization'] = `Bearer ${token}`;
  }

  let requestBody: BodyInit | undefined;
  if (body instanceof FormData) {
    requestBody = body;
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    requestBody = JSON.stringify(body);
  }

  let res: Response;
  try {
    res = await fetch(API_BASE + path, { method, headers, body: requestBody, signal });
  } catch (e) {
    if (isAbortError(e)) throw e;
    // fetch() rejects with a TypeError for network failures in every browser.
    if (e instanceof TypeError) {
      throw new ApiError({ code: 'NETWORK_ERROR', message: NETWORK_ERROR_MESSAGE, status: 0 });
    }
    throw e;
  }

  if (!res.ok) {
    const error = await parseError(res);
    if (res.status === 401 && auth) {
      handleUnauthorized();
    }
    if (error.code === 'ACCOUNT_RESTRICTED') {
      window.dispatchEvent(new Event(RESTRICTIONS_CHANGED));
    }
    throw error;
  }

  if (res.status === 204) {
    return undefined as T;
  }
  const text = await res.text();
  if (!text) {
    return undefined as T;
  }
  const contentType = res.headers.get('Content-Type') || '';
  if (contentType && !contentType.includes('application/json') && (text.startsWith('<') || text.startsWith('<!'))) {
    throw new ApiError({
      code: 'INVALID_RESPONSE',
      message: 'Сервер вернул HTML-страницу вместо ответа API. Проверьте запуск сервера.',
      status: res.status,
    });
  }
  try {
    return JSON.parse(text) as T;
  } catch (err) {
    throw new ApiError({
      code: 'JSON_PARSE_ERROR',
      message: 'Ошибка обработки ответа сервера: ' + (err instanceof Error ? err.message : String(err)),
      status: res.status,
    });
  }
}

/** Binary GET with the bearer token (images that are not served by signed URLs). */
async function requestBlob(path: string, signal?: AbortSignal): Promise<Blob> {
  const token = getToken();
  if (!token) throw new ApiError({ code: 'UNAUTHORIZED', message: 'Требуется авторизация.', status: 401 });
  let res: Response;
  try {
    res = await fetch(API_BASE + path, { headers: { Authorization: `Bearer ${token}` }, signal, cache: 'no-store' });
  } catch (e) {
    if (e instanceof TypeError) throw new ApiError({ code: 'NETWORK_ERROR', message: NETWORK_ERROR_MESSAGE, status: 0 });
    throw e;
  }
  if (!res.ok) {
    const error = await parseError(res);
    if (res.status === 401) handleUnauthorized();
    throw error;
  }
  return res.blob();
}

const id = encodeURIComponent;

// ---------------------------------------------------------------------------
// API surface
// ---------------------------------------------------------------------------

export type SessionState =
  | { status: 'anonymous' }
  | { status: 'authenticated'; account: Account; profile: MyProfile }
  | { status: 'error'; error: ApiError };

export const api = {
  // --- AUTH ---
  requestOtp(phone: string): Promise<AuthChallenge> {
    return request<AuthChallenge>('/auth/otp/request', {
      method: 'POST',
      body: { phone },
      auth: false,
    });
  },

  async verifyOtp(challengeId: string, code: string): Promise<AuthToken> {
    const result = await request<AuthToken>('/auth/otp/verify', {
      method: 'POST',
      body: { challengeId, code },
      auth: false,
    });
    setToken(result.token);
    return result;
  },

  /**
   * Restores the session from the stored token.
   * 401 → token cleared, anonymous. Network/5xx → error state (the token is kept for a retry).
   */
  async getSession(): Promise<SessionState> {
    if (!getToken()) {
      return { status: 'anonymous' };
    }
    try {
      const [account, profile] = await Promise.all([
        request<Account>('/me'),
        request<MyProfile>('/me/profile'),
      ]);
      return { status: 'authenticated', account, profile };
    } catch (e) {
      if (e instanceof ApiError && e.isUnauthorized) {
        return { status: 'anonymous' };
      }
      const error =
        e instanceof ApiError
          ? e
          : new ApiError({ code: 'UNKNOWN', message: getErrorMessage(e), status: 0 });
      return { status: 'error', error };
    }
  },

  /** Revokes the session on the server; the local token is cleared even if the request fails. */
  async logout(): Promise<void> {
    if (!getToken()) return;
    try {
      await request<void>('/auth/logout', { method: 'POST' });
    } finally {
      setToken(null);
    }
  },

  /** Clears only the local token (e.g. when the server is unreachable and the user wants out). */
  forgetLocalSession(): void {
    setToken(null);
  },

  // --- ACCOUNT ---
  async deleteAccount(): Promise<void> {
    await request<void>('/me', { method: 'DELETE' });
    setToken(null);
  },

  // --- TAGS ---
  getTags(signal?: AbortSignal): Promise<Tag[]> {
    return request<Tag[]>('/tags', { auth: false, signal });
  },

  // --- GOALS (reference list) ---
  getGoals(signal?: AbortSignal): Promise<Goal[]> {
    return request<Goal[]>('/goals', { auth: false, signal });
  },

  // --- MY PROFILE ---
  getMyProfile(): Promise<MyProfile> {
    return request<MyProfile>('/me/profile');
  },

  updateMyProfile(update: ProfileUpdate): Promise<MyProfile> {
    return request<MyProfile>('/me/profile', { method: 'PUT', body: update });
  },

  publishProfile(): Promise<MyProfile> {
    return request<MyProfile>('/me/profile/publish', { method: 'POST' });
  },

  hideProfile(): Promise<MyProfile> {
    return request<MyProfile>('/me/profile/hide', { method: 'POST' });
  },

  // --- PHOTOS ---
  uploadPhoto(file: File): Promise<Photo> {
    const form = new FormData();
    form.append('file', file);
    return request<Photo>('/me/profile/photos', { method: 'POST', body: form });
  },

  deletePhoto(photoId: string): Promise<void> {
    return request<void>(`/me/profile/photos/${encodeURIComponent(photoId)}`, { method: 'DELETE' });
  },

  setPrimaryPhoto(photoId: string): Promise<void> {
    return request<void>(`/me/profile/photos/${encodeURIComponent(photoId)}`, { method: 'PATCH' });
  },

  // --- DISCOVERY ---
  getDiscoveryProfiles(query: DiscoveryQuery = {}, signal?: AbortSignal): Promise<DiscoveryPage> {
    const params = new URLSearchParams();
    if (query.city) params.set('city', query.city);
    if (query.minAge !== undefined) params.set('minAge', String(query.minAge));
    if (query.maxAge !== undefined) params.set('maxAge', String(query.maxAge));
    if (query.tagCodes && query.tagCodes.length > 0) {
      params.set('tagCodes', query.tagCodes.join(','));
      params.set('tagLogic', query.tagLogic ?? 'OR');
    }
    // Premium ADVANCED_FILTERS: any of these → 403 PREMIUM_REQUIRED for free users.
    if (query.minHeight !== undefined) params.set('minHeight', String(query.minHeight));
    if (query.maxHeight !== undefined) params.set('maxHeight', String(query.maxHeight));
    if (query.goalCodes && query.goalCodes.length > 0) params.set('goalCodes', query.goalCodes.join(','));
    params.set('page', String(query.page ?? 0));
    params.set('limit', String(query.limit ?? 20));
    return request<DiscoveryPage>(`/discovery/profiles?${params.toString()}`, { signal });
  },

  // --- EXCLUSION WORDS (private discovery filter) ---
  getExclusionWords(signal?: AbortSignal): Promise<ExclusionWords> {
    return request<ExclusionWords>('/me/exclusion-words', { signal });
  },

  /**
   * Replaces the whole list. 400 VALIDATION_ERROR → ApiError.fieldErrors.words;
   * 403 PREMIUM_REQUIRED (EXCLUSION_WORDS) for free users.
   */
  updateExclusionWords(words: string[]): Promise<ExclusionWords> {
    return request<ExclusionWords>('/me/exclusion-words', { method: 'PUT', body: { words } });
  },

  // --- EXCLUDED GOALS (private discovery filter, premium ADVANCED_FILTERS) ---
  getExcludedGoals(signal?: AbortSignal): Promise<ExcludedGoals> {
    return request<ExcludedGoals>('/me/excluded-goals', { signal });
  },

  /** Replaces the whole list. 403 PREMIUM_REQUIRED ; 400 UNKNOWN_GOAL → ApiError.fieldErrors.goalCodes. */
  updateExcludedGoals(goalCodes: string[]): Promise<ExcludedGoals> {
    return request<ExcludedGoals>('/me/excluded-goals', { method: 'PUT', body: { goalCodes } });
  },

  // --- WALLET & COSMETICS ---
  /** Also grants due premium allowances on the server (idempotent per period). */
  getWallet(signal?: AbortSignal): Promise<Wallet> {
    return request<Wallet>('/me/wallet', { signal });
  },

  /** 400 UNKNOWN_SLOT | WRONG_SLOT ; 403 ITEM_NOT_OWNED ; 404 ITEM_NOT_FOUND. */
  equipCosmetic(slot: string, itemCode: string): Promise<Wallet> {
    return request<Wallet>(`/me/cosmetics/equipped/${encodeURIComponent(slot)}`, {
      method: 'PUT',
      body: { itemCode },
    });
  },

  unequipCosmetic(slot: string): Promise<Wallet> {
    return request<Wallet>(`/me/cosmetics/equipped/${encodeURIComponent(slot)}`, { method: 'DELETE' });
  },

  // --- ACHIEVEMENTS ---
  getAchievements(signal?: AbortSignal): Promise<Achievement[]> {
    return request<Achievement[]>('/me/achievements', { signal });
  },

  // --- TESTING (test accounts only; 403 TEST_ACCOUNT_REQUIRED otherwise) ---
  testing: {
    getState(signal?: AbortSignal): Promise<TestingState> {
      return request<TestingState>('/testing/state', { signal });
    },
    completeAchievement(code: string): Promise<TestingState> {
      return request<TestingState>(`/testing/achievements/${encodeURIComponent(code)}/complete`, { method: 'POST' });
    },
    resetAchievement(code: string): Promise<TestingState> {
      return request<TestingState>(`/testing/achievements/${encodeURIComponent(code)}`, { method: 'DELETE' });
    },
    /** Adds consumables / grants a cosmetic (quantity >= 1). */
    grantItem(itemCode: string, quantity: number): Promise<TestingState> {
      return request<TestingState>(`/testing/wallet/items/${encodeURIComponent(itemCode)}`, {
        method: 'POST',
        body: { quantity },
      });
    },
    /** Sets the bought (PERMANENT) balance exactly (quantity >= 0; cosmetics 0 or 1). */
    setItemBalance(itemCode: string, quantity: number): Promise<TestingState> {
      return request<TestingState>(`/testing/wallet/items/${encodeURIComponent(itemCode)}`, {
        method: 'PUT',
        body: { quantity },
      });
    },
    grantPremium(days: number): Promise<TestingState> {
      return request<TestingState>('/testing/premium', { method: 'POST', body: { days } });
    },
    removePremium(): Promise<TestingState> {
      return request<TestingState>('/testing/premium', { method: 'DELETE' });
    },
    /** 7 days = "next premium week": allowances refill, premium may expire. */
    advanceClock(days: number): Promise<TestingState> {
      return request<TestingState>('/testing/clock/advance', { method: 'POST', body: { days } });
    },
    resetClock(): Promise<TestingState> {
      return request<TestingState>('/testing/clock', { method: 'DELETE' });
    },
    seedLikeMe(seedAccountId: string, superlike = false): Promise<ReactionResult> {
      return request<ReactionResult>(`/testing/social/seed/${encodeURIComponent(seedAccountId)}/like-me`, {
        method: 'POST',
        body: { superlike },
      });
    },
    seedMatch(seedAccountId: string): Promise<{ matched: boolean; matchId: string }> {
      return request<{ matched: boolean; matchId: string }>(
        `/testing/social/seed/${encodeURIComponent(seedAccountId)}/match`,
        { method: 'POST' }
      );
    },
    seedSendMessage(matchId: string, text = 'Привет! Как дела?'): Promise<Message> {
      return request<Message>(`/testing/social/matches/${encodeURIComponent(matchId)}/messages`, {
        method: 'POST',
        body: { text, type: 'TEXT' },
      });
    },
    clearPasses(): Promise<{ deleted: number }> {
      return request<{ deleted: number }>('/testing/social/passes', { method: 'DELETE' });
    },
    clearReactions(): Promise<{ deleted: number }> {
      return request<{ deleted: number }>('/testing/social/reactions', { method: 'DELETE' });
    },
  },

  // --- REACTIONS ---
  likeProfile(profileId: string): Promise<ReactionResult> {
    return request<ReactionResult>(`/discovery/profiles/${encodeURIComponent(profileId)}/like`, { method: 'POST' });
  },

  superlikeProfile(profileId: string, idempotencyKey: string): Promise<ReactionResult> {
    return request<ReactionResult>(`/discovery/profiles/${encodeURIComponent(profileId)}/superlike`, {
      method: 'POST',
      body: { idempotencyKey },
    });
  },

  passProfile(profileId: string): Promise<ReactionResult> {
    return request<ReactionResult>(`/discovery/profiles/${encodeURIComponent(profileId)}/pass`, { method: 'POST' });
  },

  sendProfileViews(views: Array<{ profileId: string; dwellMs: number; openedDetails?: boolean }>): Promise<void> {
    if (!views.length) return Promise.resolve();
    return request<void>('/discovery/views', { method: 'POST', body: { views } });
  },

  // --- MATCHES & CHAT ---
  listMatches(): Promise<Match[]> {
    return request<Match[]>('/matches');
  },

  unmatch(matchId: string): Promise<void> {
    return request<void>(`/matches/${encodeURIComponent(matchId)}`, { method: 'DELETE' });
  },

  getMessages(
    matchId: string,
    before?: string,
    limit = 30
  ): Promise<{ items: Message[]; nextBefore: string | null }> {
    const params = new URLSearchParams();
    if (before) params.set('before', before);
    params.set('limit', String(limit));
    return request<{ items: Message[]; nextBefore: string | null }>(
      `/matches/${encodeURIComponent(matchId)}/messages?${params.toString()}`
    );
  },

  sendMessage(
    matchId: string,
    payload: {
      clientMessageId: string;
      type: 'TEXT' | 'STICKER';
      text?: string;
      stickerPackCode?: string;
      sticker?: string;
    }
  ): Promise<Message> {
    return request<Message>(`/matches/${encodeURIComponent(matchId)}/messages`, {
      method: 'POST',
      body: payload,
    });
  },

  markMessagesRead(matchId: string, lastReadMessageId: string): Promise<ReadState> {
    return request<ReadState>(`/matches/${encodeURIComponent(matchId)}/read`, {
      method: 'POST',
      body: { lastReadMessageId },
    });
  },

  // --- RECEIVED LIKES ---
  getReceivedLikes(
    page = 0,
    limit = 20
  ): Promise<{ items: ReceivedLike[]; page: number; limit: number; totalElements: number; totalPages: number }> {
    return request<{ items: ReceivedLike[]; page: number; limit: number; totalElements: number; totalPages: number }>(
      `/me/likes/received?page=${page}&limit=${limit}`
    );
  },

  getReceivedLikesCount(): Promise<ReceivedLikesCount> {
    return request<ReceivedLikesCount>('/me/likes/received/count');
  },

  // --- BOOST ---
  getBoost(): Promise<BoostStatus> {
    return request<BoostStatus>('/me/boost');
  },

  activateBoost(idempotencyKey: string): Promise<BoostStatus> {
    return request<BoostStatus>('/me/boost', {
      method: 'POST',
      body: { idempotencyKey },
    });
  },

  // --- GACHA ---
  getGachaBanners(): Promise<Banner[]> {
    return request<Banner[]>('/gacha/banners');
  },

  pullGacha(code: string, count: number, idempotencyKey: string): Promise<GachaPullResponse> {
    return request<GachaPullResponse>(`/gacha/banners/${encodeURIComponent(code)}/pull`, {
      method: 'POST',
      body: { count, idempotencyKey },
    });
  },

  getGachaShards(): Promise<GachaShardsInfo> {
    return request<GachaShardsInfo>('/gacha/shards');
  },

  exchangeGachaShards(tickets: number, idempotencyKey: string): Promise<ShardExchangeResponse> {
    return request<ShardExchangeResponse>('/gacha/shards/exchange', {
      method: 'POST',
      body: { tickets, idempotencyKey },
    });
  },

  getGachaHistory(limit = 20, before?: number): Promise<{ items: GachaHistoryItem[]; nextBefore: number | null }> {
    const params = new URLSearchParams();
    params.set('limit', String(limit));
    if (before !== undefined) params.set('before', String(before));
    return request<{ items: GachaHistoryItem[]; nextBefore: number | null }>(`/gacha/history?${params.toString()}`);
  },

  // --- SELFIE VERIFICATION ---
  getVerification(): Promise<{ verified: boolean; verifiedAt: string | null; current: VerificationRequest | null }> {
    return request<{ verified: boolean; verifiedAt: string | null; current: VerificationRequest | null }>(
      '/me/verification'
    );
  },

  /** The server requires explicit consent to face comparison (400 CONSENT_REQUIRED otherwise). */
  requestVerification(): Promise<VerificationRequest> {
    return request<VerificationRequest>('/me/verification', { method: 'POST', body: { faceComparisonConsent: true } });
  },

  submitSelfie(id: string, file: File): Promise<VerificationRequest> {
    const form = new FormData();
    form.append('file', file);
    return request<VerificationRequest>(`/me/verification/${encodeURIComponent(id)}/selfie`, {
      method: 'POST',
      body: form,
    });
  },

  // --- REPORTS & BLOCKS ---
  blockProfile(profileId: string): Promise<void> {
    return request<void>(`/accounts/${encodeURIComponent(profileId)}/block`, { method: 'POST' });
  },

  reportProfile(payload: {
    profileId: string;
    messageId?: string;
    reason: string;
    comment?: string;
  }): Promise<ReportResponse> {
    return request<ReportResponse>('/reports', { method: 'POST', body: payload });
  },

  getRestrictions(): Promise<Restriction[]> {
    return request<Restriction[]>('/me/restrictions');
  },

  // --- STAFF (/admin/**; 403 ROLE_REQUIRED without the role) ---
  admin: {
    listReports(
      query: { status?: ModerationStatus; sort?: 'priority' | 'oldest'; page?: number; limit?: number },
      signal?: AbortSignal
    ): Promise<AdminPage<ModerationReport>> {
      const params = new URLSearchParams();
      if (query.status) params.set('status', query.status);
      params.set('sort', query.sort ?? 'priority');
      params.set('page', String(query.page ?? 0));
      params.set('limit', String(query.limit ?? 20));
      return request(`/admin/reports?${params.toString()}`, { signal });
    },
    getReport(reportId: string): Promise<ModerationReport> {
      return request(`/admin/reports/${id(reportId)}`);
    },
    decideReport(reportId: string, body: ReportDecisionRequest): Promise<ModerationReport> {
      return request(`/admin/reports/${id(reportId)}/decision`, { method: 'POST', body });
    },

    listVerifications(
      query: { status?: VerificationReview['status']; page?: number; limit?: number },
      signal?: AbortSignal
    ): Promise<AdminPage<VerificationReview>> {
      const params = new URLSearchParams();
      params.set('status', query.status ?? 'PENDING');
      params.set('page', String(query.page ?? 0));
      params.set('limit', String(query.limit ?? 20));
      return request(`/admin/verifications?${params.toString()}`, { signal });
    },
    getVerification(requestId: string): Promise<VerificationReview> {
      return request(`/admin/verifications/${id(requestId)}`);
    },
    getVerificationSelfie(requestId: string, signal?: AbortSignal): Promise<Blob> {
      return requestBlob(`/admin/verifications/${id(requestId)}/selfie`, signal);
    },
    decideVerification(requestId: string, approve: boolean, reason?: string): Promise<VerificationReview> {
      return request(`/admin/verifications/${id(requestId)}/decision`, { method: 'POST', body: { approve, reason } });
    },

    listPhotoReviews(query: { page?: number; limit?: number }, signal?: AbortSignal): Promise<AdminPage<PhotoReviewItem>> {
      const params = new URLSearchParams();
      params.set('page', String(query.page ?? 0));
      params.set('limit', String(query.limit ?? 20));
      return request(`/admin/photo-reviews?${params.toString()}`, { signal });
    },
    /** Approve: the photo becomes visible. Reject: the photo is deleted. */
    decidePhotoReview(photoId: string, approve: boolean): Promise<void> {
      return request(`/admin/photo-reviews/${id(photoId)}/decision`, { method: 'POST', body: { approve } });
    },

    /** SUPPORT, MODERATOR or ADMIN. OPEN is sorted oldest first (the longest waiting on top). */
    listSupportTickets(
      query: { status?: SupportStatus; page?: number; limit?: number },
      signal?: AbortSignal
    ): Promise<AdminPage<SupportQueueItem>> {
      const params = new URLSearchParams();
      params.set('status', query.status ?? 'OPEN');
      params.set('page', String(query.page ?? 0));
      params.set('limit', String(query.limit ?? 20));
      return request(`/admin/support/tickets?${params.toString()}`, { signal });
    },
    getSupportTicket(ticketId: string): Promise<SupportStaffTicket> {
      return request(`/admin/support/tickets/${id(ticketId)}`);
    },
    /** The ticket becomes ANSWERED and the user gets a push (without the text). */
    replySupportTicket(ticketId: string, text: string): Promise<SupportStaffTicket> {
      return request(`/admin/support/tickets/${id(ticketId)}/messages`, { method: 'POST', body: { text } });
    },
    closeSupportTicket(ticketId: string): Promise<SupportStaffTicket> {
      return request(`/admin/support/tickets/${id(ticketId)}/close`, { method: 'POST' });
    },

    /** The API takes the phone as a query parameter (ADMIN). */
    findAccountByPhone(phone: string): Promise<AdminAccount> {
      return request(`/admin/accounts/by-phone?${new URLSearchParams({ phone }).toString()}`);
    },
    getAccount(accountId: string): Promise<AdminAccount> {
      return request(`/admin/accounts/${id(accountId)}`);
    },
    grantRole(accountId: string, role: StaffRole): Promise<AdminAccount> {
      return request(`/admin/accounts/${id(accountId)}/roles/${id(role)}`, { method: 'PUT' });
    },
    revokeRole(accountId: string, role: StaffRole): Promise<AdminAccount> {
      return request(`/admin/accounts/${id(accountId)}/roles/${id(role)}`, { method: 'DELETE' });
    },
    getRoles(): Promise<AdminRoles> {
      return request('/admin/roles');
    },
    getRestrictions(accountId: string): Promise<AdminRestriction[]> {
      return request(`/admin/accounts/${id(accountId)}/restrictions`);
    },
    createRestriction(accountId: string, body: { kind: RestrictionKind; hours?: number; note?: string }): Promise<AdminRestriction> {
      return request(`/admin/accounts/${id(accountId)}/restrictions`, { method: 'POST', body });
    },
    liftRestriction(restrictionId: string, note?: string): Promise<AdminRestriction> {
      return request(`/admin/restrictions/${id(restrictionId)}/lift`, { method: 'POST', body: note ? { note } : {} });
    },
    getBehavior(accountId: string): Promise<Behavior> {
      return request(`/admin/accounts/${id(accountId)}/behavior`);
    },
    getRisk(accountId: string): Promise<Risk> {
      return request(`/admin/accounts/${id(accountId)}/risk`);
    },
    listRisk(query: { minScore?: number; page?: number; limit?: number }, signal?: AbortSignal): Promise<AdminPage<RiskQueueItem>> {
      const params = new URLSearchParams();
      params.set('minScore', String(query.minScore ?? 30));
      params.set('page', String(query.page ?? 0));
      params.set('limit', String(query.limit ?? 20));
      return request(`/admin/risk?${params.toString()}`, { signal });
    },
    getAudit(query: { entityType?: string; entityId?: string; limit?: number }, signal?: AbortSignal): Promise<AuditEntry[]> {
      const params = new URLSearchParams();
      if (query.entityType) params.set('entityType', query.entityType);
      if (query.entityId) params.set('entityId', query.entityId);
      params.set('limit', String(query.limit ?? 50));
      return request(`/admin/audit?${params.toString()}`, { signal });
    },

    // --- Content editors (CONTENT_EDITOR or ADMIN) ---
    listTags(signal?: AbortSignal): Promise<AdminTag[]> {
      return request('/admin/tags', { signal });
    },
    createTag(body: { code: string; category: string; label: string; description: string; sortOrder?: number; active?: boolean }): Promise<AdminTag> {
      return request('/admin/tags', { method: 'POST', body });
    },
    updateTag(code: string, body: { category: string; label: string; description: string; sortOrder?: number; active?: boolean }): Promise<AdminTag> {
      return request(`/admin/tags/${id(code)}`, { method: 'PUT', body });
    },
    deleteTag(code: string): Promise<DeleteResult> {
      return request(`/admin/tags/${id(code)}`, { method: 'DELETE' });
    },
    listTagCategories(signal?: AbortSignal): Promise<AdminTagCategory[]> {
      return request('/admin/tag-categories', { signal });
    },
    createTagCategory(body: { code: string; label: string; sortOrder?: number }): Promise<AdminTagCategory> {
      return request('/admin/tag-categories', { method: 'POST', body });
    },
    updateTagCategory(code: string, body: { label: string; sortOrder?: number }): Promise<AdminTagCategory> {
      return request(`/admin/tag-categories/${id(code)}`, { method: 'PUT', body });
    },
    /** 409 CATEGORY_IN_USE while it has tags (active or not). */
    deleteTagCategory(code: string): Promise<void> {
      return request(`/admin/tag-categories/${id(code)}`, { method: 'DELETE' });
    },
    /** Replaces the whole scale; unlisted cards lose their place; scale=false clears it. */
    setTagCategoryScale(
      code: string,
      body: { scale: boolean; scaleMinLabel?: string; scaleMaxLabel?: string; positions?: Record<string, number> }
    ): Promise<AdminTagCategory> {
      return request(`/admin/tag-categories/${id(code)}/scale`, { method: 'PUT', body });
    },
    listGoals(signal?: AbortSignal): Promise<AdminGoal[]> {
      return request('/admin/goals', { signal });
    },
    createGoal(body: { code: string; label: string; sortOrder?: number; active?: boolean }): Promise<AdminGoal> {
      return request('/admin/goals', { method: 'POST', body });
    },
    updateGoal(code: string, body: { label: string; sortOrder?: number; active?: boolean }): Promise<AdminGoal> {
      return request(`/admin/goals/${id(code)}`, { method: 'PUT', body });
    },
    deleteGoal(code: string): Promise<DeleteResult> {
      return request(`/admin/goals/${id(code)}`, { method: 'DELETE' });
    },

    listCatalogItems(query: { kind?: CatalogKind; category?: string }, signal?: AbortSignal): Promise<CatalogEntry[]> {
      const params = new URLSearchParams();
      if (query.kind) params.set('kind', query.kind);
      if (query.category) params.set('category', query.category);
      return request(`/admin/catalog/items?${params.toString()}`, { signal });
    },
    createCatalogItem(body: CatalogEntryCreate): Promise<CatalogEntry> {
      return request('/admin/catalog/items', { method: 'POST', body });
    },
    updateCatalogItem(code: string, body: CatalogEntryUpdate): Promise<CatalogEntry> {
      return request(`/admin/catalog/items/${id(code)}`, { method: 'PUT', body });
    },
    /** JPEG/PNG/WebP <= 5 MB; sets attributes.imageUrl. */
    uploadCatalogImage(code: string, file: File): Promise<CatalogEntry> {
      const form = new FormData();
      form.append('file', file);
      return request(`/admin/catalog/items/${id(code)}/image`, { method: 'POST', body: form });
    },
    listAuthors(signal?: AbortSignal): Promise<Author[]> {
      return request('/admin/authors', { signal });
    },
    /** ADMIN only. */
    createAuthor(body: AuthorInput): Promise<Author> {
      return request('/admin/authors', { method: 'POST', body });
    },
    /** ADMIN only; full replace (absent revenueSharePercent keeps the current one). */
    updateAuthor(authorId: string, body: AuthorInput): Promise<Author> {
      return request(`/admin/authors/${id(authorId)}`, { method: 'PUT', body });
    },
    /** ADMIN only; 409 AUTHOR_IN_USE while it has catalog items. */
    deleteAuthor(authorId: string): Promise<void> {
      return request(`/admin/authors/${id(authorId)}`, { method: 'DELETE' });
    },

    listAchievements(signal?: AbortSignal): Promise<AchievementDefinition[]> {
      return request('/admin/achievements', { signal });
    },
    updateAchievement(
      code: string,
      body: { title: string; description?: string; rewards: AchievementReward[]; active: boolean }
    ): Promise<AchievementDefinition> {
      return request(`/admin/achievements/${id(code)}`, { method: 'PUT', body });
    },

    /** ADMIN only. */
    getPremium(signal?: AbortSignal): Promise<PremiumView> {
      return request('/admin/premium', { signal });
    },
    /** ADMIN only; an absent list keeps the current one. */
    updatePremium(body: { features?: string[]; allowances?: PremiumAllowance[] }): Promise<PremiumView> {
      return request('/admin/premium', { method: 'PUT', body });
    },
    /** ADMIN only; back to the config defaults for both lists. */
    resetPremium(): Promise<PremiumView> {
      return request('/admin/premium', { method: 'DELETE' });
    },

    // --- FAQ (CONTENT_EDITOR, SUPPORT or ADMIN) ---
    listFaq(signal?: AbortSignal): Promise<FaqItem[]> {
      return request('/admin/faq', { signal });
    },
    createFaq(body: FaqInput): Promise<FaqItem> {
      return request('/admin/faq', { method: 'POST', body });
    },
    updateFaq(faqId: string, body: FaqInput): Promise<FaqItem> {
      return request(`/admin/faq/${id(faqId)}`, { method: 'PUT', body });
    },
    deleteFaq(faqId: string): Promise<void> {
      return request(`/admin/faq/${id(faqId)}`, { method: 'DELETE' });
    },
  },

  // --- REALTIME SSE TICKET ---
  getEventsTicket(): Promise<{ ticket: string; expiresAt: string }> {
    return request<{ ticket: string; expiresAt: string }>('/events/ticket', { method: 'POST' });
  },
};
