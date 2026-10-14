import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { Account, AuthChallenge, Profile, Session } from '../types';
import { api } from '../api/client';

interface AuthContextType {
  account: Account | null;
  session: Session | null;
  profile: Profile | null;
  isLoading: boolean;
  activeChallenge: AuthChallenge | null;
  requestOtp: (phone: string) => Promise<AuthChallenge>;
  verifyOtp: (code: string) => Promise<void>;
  cancelChallenge: () => void;
  logout: () => Promise<void>;
  deleteAccount: () => Promise<void>;
  updateProfile: (updates: Partial<Profile>) => Promise<Profile>;
  publishProfile: () => Promise<void>;
  hideProfile: () => Promise<void>;
  refreshProfile: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [account, setAccount] = useState<Account | null>(null);
  const [session, setSession] = useState<Session | null>(null);
  const [profile, setProfile] = useState<Profile | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [activeChallenge, setActiveChallenge] = useState<AuthChallenge | null>(null);

  // Load current session on init
  const loadSession = useCallback(async () => {
    setIsLoading(true);
    try {
      const data = await api.getSession();
      setSession(data.session);
      setAccount(data.account);
      setProfile(data.profile);
    } catch (e) {
      console.error('Session load failed', e);
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadSession();
  }, [loadSession]);

  const requestOtp = async (phone: string): Promise<AuthChallenge> => {
    const challenge = await api.requestOtp(phone);
    setActiveChallenge(challenge);
    return challenge;
  };

  const verifyOtp = async (code: string): Promise<void> => {
    if (!activeChallenge) throw new Error('Нет активного запроса авторизации');
    const result = await api.verifyOtp(activeChallenge.challengeId, code, activeChallenge.maskedPhone);
    setSession(result.session);
    setAccount(result.account);
    setProfile(result.profile);
    setActiveChallenge(null);
  };

  const cancelChallenge = () => {
    setActiveChallenge(null);
  };

  const logout = async () => {
    await api.logout();
    setSession(null);
    setAccount(null);
    setProfile(null);
  };

  const deleteAccount = async () => {
    await api.deleteAccount();
    setSession(null);
    setAccount(null);
    setProfile(null);
  };

  const updateProfile = async (updates: Partial<Profile>): Promise<Profile> => {
    if (!account) throw new Error('Не авторизован');
    const updated = await api.updateMyProfile(account.id, updates);
    setProfile(updated);
    return updated;
  };

  const publishProfile = async () => {
    if (!account) return;
    const updated = await api.publishProfile(account.id);
    setProfile(updated);
  };

  const hideProfile = async () => {
    if (!account) return;
    const updated = await api.hideProfile(account.id);
    setProfile(updated);
  };

  const refreshProfile = async () => {
    if (!account) return;
    const p = await api.getMyProfile(account.id);
    setProfile(p);
  };

  return (
    <AuthContext.Provider
      value={{
        account,
        session,
        profile,
        isLoading,
        activeChallenge,
        requestOtp,
        verifyOtp,
        cancelChallenge,
        logout,
        deleteAccount,
        updateProfile,
        publishProfile,
        hideProfile,
        refreshProfile,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = (): AuthContextType => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};
