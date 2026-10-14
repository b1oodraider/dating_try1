import type {
  Account,
  ApiErrorBody,
  AuthChallenge,
  AuthToken,
  DiscoveryPage,
  DiscoveryQuery,
  MyProfile,
  Photo,
  ProfileUpdate,
  Tag,
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

  constructor(params: {
    code: string;
    message: string;
    status: number;
    traceId?: string;
    fieldErrors?: Record<string, string>;
    retryAfterSeconds?: number;
  }) {
    super(params.message);
    this.name = 'ApiError';
    this.code = params.code;
    this.status = params.status;
    this.traceId = params.traceId;
    this.fieldErrors = params.fieldErrors;
    this.retryAfterSeconds = params.retryAfterSeconds;
  }

  get isNetworkError(): boolean {
    return this.status === 0;
  }

  get isUnauthorized(): boolean {
    return this.status === 401;
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
    params.set('page', String(query.page ?? 0));
    params.set('limit', String(query.limit ?? 20));
    return request<DiscoveryPage>(`/discovery/profiles?${params.toString()}`, { signal });
  },
};
