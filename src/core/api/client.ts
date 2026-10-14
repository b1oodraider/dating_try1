import type {
  Account,
  Achievement,
  ApiErrorBody,
  AuthChallenge,
  AuthToken,
  DiscoveryPage,
  DiscoveryQuery,
  ExcludedGoals,
  ExclusionWords,
  Goal,
  MyProfile,
  Photo,
  ProfileUpdate,
  Tag,
  TestingState,
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
    if (e.status === 429 && e.retryAfterSeconds) {
      return `${e.message} Повторите через ${e.retryAfterSeconds} с.`;
    }
    return e.message || fallback;
  }
  if (e instanceof Error && e.message) return e.message;
  return fallback;
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

export function hasToken(): boolean {
  return getToken() !== null;
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
      handleUnauthorized();
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
    throw error;
  }

  if (res.status === 204) {
    return undefined as T;
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

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
  getAccount(): Promise<Account> {
    return request<Account>('/me');
  },

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
  listPhotos(): Promise<Photo[]> {
    return request<Photo[]>('/me/profile/photos');
  },

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
  },
};
