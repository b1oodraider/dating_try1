import {
  Account,
  AuthChallenge,
  DiscoveryFilterParams,
  Profile,
  ProfilePhoto,
  Session,
  ProductEvent,
} from '../types';
import { INITIAL_PROFILES } from './mockData';

const STORAGE_KEY_TOKEN = 'dating_mvp_token_v1';
const STORAGE_KEY_SESSION = 'dating_mvp_session_v1';
const STORAGE_KEY_ACCOUNT = 'dating_mvp_account_v1';
const STORAGE_KEY_PROFILES = 'dating_mvp_profiles_v1';
const STORAGE_KEY_EVENTS = 'dating_mvp_events_v1';

function getToken(): string | null {
  return localStorage.getItem(STORAGE_KEY_TOKEN);
}

function setToken(token: string | null): void {
  if (token) {
    localStorage.setItem(STORAGE_KEY_TOKEN, token);
  } else {
    localStorage.removeItem(STORAGE_KEY_TOKEN);
  }
}

function getHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const token = getToken();
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...extra,
  };
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }
  return headers;
}

export function logProductEvent(eventType: string, attributes: Record<string, string | number | boolean> = {}): void {
  try {
    const existingRaw = localStorage.getItem(STORAGE_KEY_EVENTS);
    const events: ProductEvent[] = existingRaw ? JSON.parse(existingRaw) : [];
    const event: ProductEvent = {
      eventId: 'evt-' + Math.random().toString(36).substring(2, 11),
      eventType,
      schemaVersion: 1,
      occurredAt: new Date().toISOString(),
      attributes,
    };
    events.push(event);
    localStorage.setItem(STORAGE_KEY_EVENTS, JSON.stringify(events.slice(-100)));
  } catch (e) {
    console.warn('Could not persist product event', e);
  }
}

export const api = {
  // --- AUTH & CHALLENGE ---
  async requestOtp(phone: string): Promise<AuthChallenge> {
    try {
      const res = await fetch('/api/v1/auth/otp/request', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ phone }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || 'Ошибка запроса кода');
      }
      const data = await res.json();
      logProductEvent('auth_otp_requested', { hasMock: !!data.devMockCode });
      return data;
    } catch (e: unknown) {
      if (e instanceof Error && e.message !== 'Failed to fetch') {
        throw e;
      }
      // Local fallback
      const cleaned = phone.replace(/[^\d+]/g, '');
      const last2 = cleaned.slice(-2);
      const masked = `+7 (***) ***-**-${last2}`;
      return {
        challengeId: 'chal-local-' + Date.now().toString(36),
        expiresAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
        maskedPhone: masked,
        devMockCode: '123456',
      };
    }
  },

  async verifyOtp(
    challengeId: string,
    code: string,
    maskedPhone: string
  ): Promise<{ session: Session; account: Account; profile: Profile }> {
    try {
      const res = await fetch(`/api/v1/auth/otp/verify?phone=${encodeURIComponent(maskedPhone)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ challengeId, code }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || 'Неверный код подтверждения');
      }
      const data = await res.json();
      setToken(data.token);

      const session: Session = {
        id: 'sess-' + Math.random().toString(36).substring(2, 9),
        accountId: data.accountId,
        expiresAt: data.expiresAt,
      };
      const account: Account = {
        id: data.account?.id || data.accountId,
        phoneMasked: data.account?.phoneMasked || maskedPhone,
        status: 'ACTIVE',
        phoneVerifiedAt: new Date().toISOString(),
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };

      localStorage.setItem(STORAGE_KEY_SESSION, JSON.stringify(session));
      localStorage.setItem(STORAGE_KEY_ACCOUNT, JSON.stringify(account));

      // Fetch user profile
      const profRes = await fetch('/api/v1/me/profile', { headers: getHeaders() });
      let profile: Profile;
      if (profRes.ok) {
        profile = await profRes.json();
      } else {
        profile = {
          id: 'prof-' + account.id,
          accountId: account.id,
          displayName: 'Новая анкета',
          age: 24,
          city: 'Москва',
          aboutText: '',
          publicationStatus: 'DRAFT',
          visibilityStatus: 'PUBLIC',
          tags: [],
          photos: [],
          dailyStatus: null,
          completenessPercent: 50,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        };
      }

      logProductEvent('auth_otp_verified', { accountId: account.id });
      return { session, account, profile };
    } catch (e: unknown) {
      if (e instanceof Error && e.message !== 'Failed to fetch') {
        throw e;
      }
      // Local fallback
      if (code !== '123456') {
        throw new Error('Неверный код подтверждения. Для теста введите 123456');
      }
      const accId = 'acc-local-' + Math.random().toString(36).substring(2, 9);
      const session: Session = {
        id: 'sess-' + Math.random().toString(36).substring(2, 9),
        accountId: accId,
        expiresAt: new Date(Date.now() + 30 * 24 * 3600 * 1000).toISOString(),
      };
      const account: Account = {
        id: accId,
        phoneMasked: maskedPhone,
        status: 'ACTIVE',
        phoneVerifiedAt: new Date().toISOString(),
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      setToken('mock-local-token-' + accId);
      localStorage.setItem(STORAGE_KEY_SESSION, JSON.stringify(session));
      localStorage.setItem(STORAGE_KEY_ACCOUNT, JSON.stringify(account));

      const profile: Profile = {
        id: 'prof-' + accId,
        accountId: accId,
        displayName: 'Моя анкета',
        age: 25,
        city: 'Москва',
        aboutText: 'Привет! Заполняю анкету.',
        publicationStatus: 'DRAFT',
        visibilityStatus: 'PUBLIC',
        tags: [],
        photos: [],
        dailyStatus: null,
        completenessPercent: 60,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      return { session, account, profile };
    }
  },

  async getSession(): Promise<{ session: Session | null; account: Account | null; profile: Profile | null }> {
    const token = getToken();
    if (!token) {
      return { session: null, account: null, profile: null };
    }

    try {
      const [accRes, profRes] = await Promise.all([
        fetch('/api/v1/me', { headers: getHeaders() }),
        fetch('/api/v1/me/profile', { headers: getHeaders() }),
      ]);

      if (accRes.ok && profRes.ok) {
        const account = await accRes.json();
        const profile = await profRes.json();
        const session: Session = {
          id: 'sess-active',
          accountId: account.id,
          expiresAt: new Date(Date.now() + 30 * 24 * 3600 * 1000).toISOString(),
        };
        return { session, account, profile };
      }
    } catch {
      // Fallback to local storage if server offline
    }

    try {
      const rawSession = localStorage.getItem(STORAGE_KEY_SESSION);
      const rawAccount = localStorage.getItem(STORAGE_KEY_ACCOUNT);
      if (!rawSession || !rawAccount) {
        return { session: null, account: null, profile: null };
      }
      return {
        session: JSON.parse(rawSession),
        account: JSON.parse(rawAccount),
        profile: null,
      };
    } catch {
      return { session: null, account: null, profile: null };
    }
  },

  async logout(): Promise<void> {
    try {
      await fetch('/api/v1/auth/logout', { method: 'POST', headers: getHeaders() });
    } catch {
    }
    setToken(null);
    localStorage.removeItem(STORAGE_KEY_SESSION);
    localStorage.removeItem(STORAGE_KEY_ACCOUNT);
    logProductEvent('auth_logout');
  },

  async deleteAccount(): Promise<void> {
    try {
      await fetch('/api/v1/me', { method: 'DELETE', headers: getHeaders() });
    } catch {
    }
    setToken(null);
    localStorage.removeItem(STORAGE_KEY_SESSION);
    localStorage.removeItem(STORAGE_KEY_ACCOUNT);
    logProductEvent('account_deletion_completed');
  },

  // --- PROFILE API ---
  async getMyProfile(_accountId: string): Promise<Profile> {
    const res = await fetch('/api/v1/me/profile', { headers: getHeaders() });
    if (!res.ok) {
      throw new Error('Не удалось загрузить анкету');
    }
    return res.json();
  },

  async updateMyProfile(_accountId: string, updates: Partial<Profile>): Promise<Profile> {
    const payload = {
      displayName: updates.displayName,
      age: updates.age,
      city: updates.city,
      aboutText: updates.aboutText,
      tagCodes: updates.tags?.map((t) => t.code),
      dailyStatusText: updates.dailyStatus ? updates.dailyStatus.text : (updates.dailyStatus === null ? '' : undefined),
    };

    const res = await fetch('/api/v1/me/profile', {
      method: 'PUT',
      headers: getHeaders(),
      body: JSON.stringify(payload),
    });

    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.message || 'Ошибка обновления профиля');
    }
    const updated = await res.json();
    logProductEvent('profile_updated', { profileId: updated.id, completeness: updated.completenessPercent });
    return updated;
  },

  async publishProfile(_accountId: string): Promise<Profile> {
    const res = await fetch('/api/v1/me/profile/publish', {
      method: 'POST',
      headers: getHeaders(),
    });
    if (!res.ok) {
      throw new Error('Ошибка публикации профиля');
    }
    const updated = await res.json();
    logProductEvent('profile_published', { profileId: updated.id });
    return updated;
  },

  async hideProfile(_accountId: string): Promise<Profile> {
    const res = await fetch('/api/v1/me/profile/hide', {
      method: 'POST',
      headers: getHeaders(),
    });
    if (!res.ok) {
      throw new Error('Ошибка сокрытия профиля');
    }
    const updated = await res.json();
    logProductEvent('profile_hidden', { profileId: updated.id });
    return updated;
  },

  // --- PHOTOS API ---
  async addPhoto(_accountId: string, fileDataUrl: string, mimeType: string, sizeBytes: number): Promise<ProfilePhoto> {
    const res = await fetch('/api/v1/me/profile/photos', {
      method: 'POST',
      headers: getHeaders(),
      body: JSON.stringify({ dataUrl: fileDataUrl, mimeType, sizeBytes }),
    });

    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.message || 'Ошибка загрузки фотографии');
    }
    const newPhoto = await res.json();
    logProductEvent('profile_photo_added', { photoId: newPhoto.id });
    return newPhoto;
  },

  async deletePhoto(_accountId: string, photoId: string): Promise<void> {
    const res = await fetch(`/api/v1/me/profile/photos/${photoId}`, {
      method: 'DELETE',
      headers: getHeaders(),
    });
    if (!res.ok) {
      throw new Error('Ошибка удаления фотографии');
    }
    logProductEvent('profile_photo_removed', { photoId });
  },

  async setPrimaryPhoto(_accountId: string, photoId: string): Promise<void> {
    const res = await fetch(`/api/v1/me/profile/photos/${photoId}`, {
      method: 'PATCH',
      headers: getHeaders(),
    });
    if (!res.ok) {
      throw new Error('Ошибка назначения главного фото');
    }
  },

  // --- DISCOVERY API ---
  async getDiscoveryProfiles(
    _myAccountId: string | null,
    params: DiscoveryFilterParams = {}
  ): Promise<{ profiles: Profile[]; total: number }> {
    const query = new URLSearchParams();
    if (params.city && params.city !== 'ALL') query.set('city', params.city);
    if (params.minAge) query.set('minAge', String(params.minAge));
    if (params.maxAge) query.set('maxAge', String(params.maxAge));
    if (params.selectedTagCodes && params.selectedTagCodes.length > 0) {
      query.set('tagCodes', params.selectedTagCodes.join(','));
      query.set('tagLogic', params.tagFilterLogic || 'OR');
    }
    if (params.page !== undefined) query.set('page', String(params.page));
    if (params.limit !== undefined) query.set('limit', String(params.limit));

    try {
      const res = await fetch(`/api/v1/discovery/profiles?${query.toString()}`, {
        headers: getHeaders(),
      });
      if (res.ok) {
        const data = await res.json();
        logProductEvent('discovery_query_executed', { resultsCount: data.items?.length || 0 });
        return {
          profiles: data.items || [],
          total: data.totalElements || data.items?.length || 0,
        };
      }
    } catch {
      // Fallback to initial seed profiles if server is booting
    }

    return {
      profiles: INITIAL_PROFILES,
      total: INITIAL_PROFILES.length,
    };
  },
};
