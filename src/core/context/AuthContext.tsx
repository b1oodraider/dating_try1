import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { Account, AuthChallenge, MyProfile, ProfileUpdate } from '../types';
import { api, ApiError, onUnauthorized, type SessionState } from '../api/client';
import { clearAccountStorage } from '../storage/accountStorage';

interface AuthContextType {
  account: Account | null;
  profile: MyProfile | null;
  /** True while the stored session is being restored. */
  isLoading: boolean;
  /** Set when the session could not be restored for a reason other than 401 (network, 5xx). */
  loadError: ApiError | null;
  activeChallenge: AuthChallenge | null;
  reloadSession: () => Promise<void>;
  /** Drops the local token without contacting the server (used from the load-error screen). */
  forgetSession: () => void;
  requestOtp: (phone: string) => Promise<AuthChallenge>;
  verifyOtp: (code: string) => Promise<void>;
  cancelChallenge: () => void;
  logout: () => Promise<void>;
  deleteAccount: () => Promise<void>;
  updateProfile: (update: ProfileUpdate) => Promise<MyProfile>;
  publishProfile: () => Promise<MyProfile>;
  hideProfile: () => Promise<MyProfile>;
  refreshProfile: () => Promise<MyProfile>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [account, setAccount] = useState<Account | null>(null);
  const [profile, setProfile] = useState<MyProfile | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [activeChallenge, setActiveChallenge] = useState<AuthChallenge | null>(null);

  const resetSession = useCallback(() => {
    setAccount(null);
    setProfile(null);
    setLoadError(null);
  }, []);

  const applySession = useCallback((state: SessionState) => {
    if (state.status === 'authenticated') {
      setAccount(state.account);
      setProfile(state.profile);
      setLoadError(null);
    } else if (state.status === 'anonymous') {
      setAccount(null);
      setProfile(null);
      setLoadError(null);
    } else {
      setAccount(null);
      setProfile(null);
      setLoadError(state.error);
    }
  }, []);

  const reloadSession = useCallback(async () => {
    setIsLoading(true);
    try {
      applySession(await api.getSession());
    } finally {
      setIsLoading(false);
    }
  }, [applySession]);

  useEffect(() => {
    void reloadSession();
  }, [reloadSession]);

  // Any 401 from an authenticated call means the token is gone: drop the session in the UI.
  useEffect(() => onUnauthorized(resetSession), [resetSession]);

  const forgetSession = useCallback(() => {
    api.forgetLocalSession();
    resetSession();
  }, [resetSession]);

  const requestOtp = useCallback(async (phone: string): Promise<AuthChallenge> => {
    const challenge = await api.requestOtp(phone);
    setActiveChallenge(challenge);
    return challenge;
  }, []);

  const verifyOtp = useCallback(
    async (code: string): Promise<void> => {
      if (!activeChallenge) {
        throw new ApiError({
          code: 'INVALID_CHALLENGE',
          message: 'Запросите код подтверждения заново.',
          status: 400,
        });
      }
      await api.verifyOtp(activeChallenge.challengeId, code);
      setActiveChallenge(null);
      applySession(await api.getSession());
    },
    [activeChallenge, applySession]
  );

  const cancelChallenge = useCallback(() => {
    setActiveChallenge(null);
  }, []);

  const accountId = account?.id ?? null;

  // Per-account UI data (e.g. saved discovery filters) survives logout and lost sessions, so logging in
  // again restores the same filters; only account deletion forgets it.
  const logout = useCallback(async () => {
    try {
      await api.logout();
    } catch (e) {
      // The local token is already cleared by api.logout(); a failed server call must not keep the user logged in.
      console.warn('Logout request failed', e);
    } finally {
      resetSession();
    }
  }, [resetSession]);

  const deleteAccount = useCallback(async () => {
    await api.deleteAccount();
    if (accountId) clearAccountStorage(accountId);
    resetSession();
  }, [accountId, resetSession]);

  const updateProfile = useCallback(async (update: ProfileUpdate): Promise<MyProfile> => {
    const updated = await api.updateMyProfile(update);
    setProfile(updated);
    return updated;
  }, []);

  const publishProfile = useCallback(async (): Promise<MyProfile> => {
    const updated = await api.publishProfile();
    setProfile(updated);
    return updated;
  }, []);

  const hideProfile = useCallback(async (): Promise<MyProfile> => {
    const updated = await api.hideProfile();
    setProfile(updated);
    return updated;
  }, []);

  const refreshProfile = useCallback(async (): Promise<MyProfile> => {
    const updated = await api.getMyProfile();
    setProfile(updated);
    return updated;
  }, []);

  const value = useMemo<AuthContextType>(
    () => ({
      account,
      profile,
      isLoading,
      loadError,
      activeChallenge,
      reloadSession,
      forgetSession,
      requestOtp,
      verifyOtp,
      cancelChallenge,
      logout,
      deleteAccount,
      updateProfile,
      publishProfile,
      hideProfile,
      refreshProfile,
    }),
    [
      account,
      profile,
      isLoading,
      loadError,
      activeChallenge,
      reloadSession,
      forgetSession,
      requestOtp,
      verifyOtp,
      cancelChallenge,
      logout,
      deleteAccount,
      updateProfile,
      publishProfile,
      hideProfile,
      refreshProfile,
    ]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

export const useAuth = (): AuthContextType => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};
