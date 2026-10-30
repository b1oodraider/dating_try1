import React, { useCallback, useEffect, useState } from 'react';
import { AuthProvider, useAuth } from './core/context/AuthContext';
import { Navbar, type AppTab } from './shared/components/Navbar';
import { DiscoveryFeed } from './features/discovery/components/DiscoveryFeed';
import { ProfileEditor } from './features/profile/components/ProfileEditor';
import { AboutView } from './features/about/AboutView';
import { AuthModal } from './features/auth/components/AuthModal';
import { AccountSettingsModal } from './features/account/components/AccountSettingsModal';
import { CollectionView } from './features/cosmetics/components/CollectionView';
import { SupportModal } from './features/support/components/SupportModal';
import { TestPanelModal } from './features/testing/components/TestPanelModal';
import { ChatView } from './features/chat/components/ChatView';
import { RestrictionsBanner } from './features/safety/components/RestrictionsBanner';
import { Button } from './shared/components/Button';
import { WifiOff, MessageCircle } from 'lucide-react';
import { api } from './core/api/client';
import type { Match } from './core/types';
import { RealtimeProvider, useRealtime } from './core/realtime/RealtimeContext';
import { ToastProvider } from './shared/components/Toast';
import { AdminView } from './features/admin/components/AdminView';
import { CommunitiesView } from './features/communities/CommunitiesView';
import { searchInCommunity } from './features/discovery/filterStorage';

const AppContent: React.FC = () => {
  const { account, isLoading, loadError, reloadSession, forgetSession, refreshProfile } = useAuth();
  const [currentTab, setCurrentTab] = useState<AppTab>('discovery');
  const [isAuthModalOpen, setIsAuthModalOpen] = useState(false);
  const [isSettingsModalOpen, setIsSettingsModalOpen] = useState(false);
  const [isSupportOpen, setIsSupportOpen] = useState(false);
  /** Account the test panel was opened for: it never reopens by itself after switching accounts. */
  const [testPanelAccountId, setTestPanelAccountId] = useState<string | null>(null);
  /** Bumped after test-panel actions so the collection reloads the wallet. */
  const [economyRevision, setEconomyRevision] = useState(0);

  // Chat & Unread state
  const [activeChatMatchId, setActiveChatMatchId] = useState<string | null>(null);
  const [unreadChatsCount, setUnreadChatsCount] = useState(0);

  const accountId = account?.id ?? null;
  const countUnread = useCallback((matches: Match[]) => {
    setUnreadChatsCount(matches.filter((m) => m.unreadCount > 0).length);
  }, []);

  const loadUnreadMatches = useCallback(() => {
    if (!accountId) {
      setUnreadChatsCount(0);
      return;
    }
    api.listMatches().then(countUnread).catch(() => {
      // the badge is best effort
    });
  }, [accountId, countUnread]);

  useEffect(loadUnreadMatches, [loadUnreadMatches]);

  // The open chat screen reports its own list (onMatchesChanged); elsewhere refresh the badge on chat events.
  useRealtime((event) => {
    if (currentTab !== 'chats' && event.name !== 'like_received') loadUnreadMatches();
  });

  const handleEconomyChanged = useCallback(() => {
    setEconomyRevision((r) => r + 1);
    refreshProfile().catch((e: unknown) => console.warn('Profile refresh failed', e));
  }, [refreshProfile]);

  const handleOpenChat = useCallback((matchId: string) => {
    setActiveChatMatchId(matchId);
    setCurrentTab('chats');
  }, []);

  if (isLoading) {
    return (
      <div className="min-h-screen bg-stone-50 flex items-center justify-center">
        <div className="flex flex-col items-center gap-3">
          <div className="w-8 h-8 border-2 border-zinc-900 border-t-transparent rounded-full animate-spin" />
          <p className="text-xs text-zinc-500 font-medium">Загрузка сервиса Dating...</p>
        </div>
      </div>
    );
  }

  if (loadError) {
    return (
      <div className="min-h-screen bg-stone-50 flex items-center justify-center px-4">
        <div className="max-w-sm w-full bg-white border border-zinc-200/80 rounded-2xl p-6 text-center space-y-4 shadow-xs">
          <div className="w-12 h-12 bg-rose-50 rounded-2xl flex items-center justify-center mx-auto text-rose-600">
            <WifiOff className="w-6 h-6" />
          </div>
          <div className="space-y-1">
            <h2 className="text-base font-bold text-zinc-900">Не удалось загрузить сессию</h2>
            <p className="text-xs text-zinc-500">{loadError.message}</p>
          </div>
          <div className="flex flex-col gap-2">
            <Button type="button" variant="primary" onClick={() => void reloadSession()}>
              Повторить
            </Button>
            <Button type="button" variant="ghost" size="sm" onClick={forgetSession}>
              Выйти из аккаунта на этом устройстве
            </Button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-stone-50 text-zinc-900 flex flex-col font-sans selection:bg-rose-100 selection:text-rose-900">
      {/* Account Restrictions Banner (if any) */}
      <RestrictionsBanner />

      {/* Top Navigation */}
      <Navbar
        currentTab={currentTab}
        onTabChange={(tab) => setCurrentTab(tab)}
        onOpenAuth={() => setIsAuthModalOpen(true)}
        onOpenSettings={() => setIsSettingsModalOpen(true)}
        onOpenTestPanel={() => setTestPanelAccountId(account?.id ?? null)}
        onOpenSupport={() => setIsSupportOpen(true)}
        unreadChatsCount={unreadChatsCount}
      />

      {/* Main Content View */}
      <main className="flex-1 max-w-6xl w-full mx-auto px-4 sm:px-6 pt-6">
        {currentTab === 'discovery' && (
          <DiscoveryFeed
            onRequestAuth={() => setIsAuthModalOpen(true)}
            revision={economyRevision}
            onOpenChat={handleOpenChat}
          />
        )}

        {currentTab === 'chats' && (
          account ? (
            <ChatView
              initialMatchId={activeChatMatchId}
              onSelectMatchId={(id) => setActiveChatMatchId(id)}
              onMatchesChanged={countUnread}
            />
          ) : (
            <div className="max-w-md mx-auto text-center py-16 space-y-4">
              <div className="w-12 h-12 bg-zinc-200/80 rounded-2xl flex items-center justify-center mx-auto text-zinc-600 font-bold text-lg">
                <MessageCircle className="w-6 h-6" />
              </div>
              <h2 className="text-lg font-bold text-zinc-900">Чаты доступны после входа</h2>
              <p className="text-xs text-zinc-500">
                Войдите по номеру телефона, чтобы видеть ваши взаимные симпатии и переписываться.
              </p>
              <button
                type="button"
                onClick={() => setIsAuthModalOpen(true)}
                className="px-5 py-2.5 bg-zinc-900 text-white rounded-xl text-xs font-semibold hover:bg-zinc-800 transition-colors shadow-xs cursor-pointer"
              >
                Войти по номеру телефона
              </button>
            </div>
          )
        )}

        {(currentTab === 'profile' || currentTab === 'collection') && (
          account ? (
            currentTab === 'profile' ? <ProfileEditor /> : <CollectionView revision={economyRevision} />
          ) : (
            <div className="max-w-md mx-auto text-center py-16 space-y-4">
              <div className="w-12 h-12 bg-zinc-200/80 rounded-2xl flex items-center justify-center mx-auto text-zinc-600 font-bold text-lg">
                !
              </div>
              <h2 className="text-lg font-bold text-zinc-900">Требуется авторизация</h2>
              <p className="text-xs text-zinc-500">
                Чтобы создать анкету, настроить карточки общения и загрузить фотографии, выполните вход по номеру телефона.
              </p>
              <button
                type="button"
                onClick={() => setIsAuthModalOpen(true)}
                className="px-5 py-2.5 bg-zinc-900 text-white rounded-xl text-xs font-semibold hover:bg-zinc-800 transition-colors shadow-xs cursor-pointer"
              >
                Войти по номеру телефона
              </button>
            </div>
          )
        )}

        {currentTab === 'communities' && account && (
          <CommunitiesView
            onSearch={(c) => {
              searchInCommunity(account.id, { id: c.id, title: c.emoji ? `${c.emoji} ${c.title}` : c.title });
              setCurrentTab('discovery');
            }}
          />
        )}

        {currentTab === 'about' && <AboutView />}

        {currentTab === 'admin' && <AdminView />}
      </main>

      {/* Footer */}
      <footer className="border-t border-zinc-200/80 bg-white py-6 mt-auto">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 flex flex-col sm:flex-row items-center justify-between gap-3 text-xs text-zinc-400">
          <div>
            Dating MVP — модульный монолит. Закрытое тестирование.
          </div>
          <div className="flex items-center gap-4 text-[11px]">
            <span>PostgreSQL & Liquibase ready</span>
            <span>REST API v1</span>
            <span>Без рекламы и ML</span>
          </div>
        </div>
      </footer>

      {/* Modals */}
      <AuthModal
        isOpen={isAuthModalOpen}
        onClose={() => setIsAuthModalOpen(false)}
      />

      <AccountSettingsModal
        isOpen={isSettingsModalOpen}
        onClose={() => setIsSettingsModalOpen(false)}
      />

      <SupportModal isOpen={isSupportOpen} onClose={() => setIsSupportOpen(false)} />

      {account?.testAccount && (
        <TestPanelModal
          isOpen={testPanelAccountId === account.id}
          onClose={() => setTestPanelAccountId(null)}
          onChanged={handleEconomyChanged}
        />
      )}
    </div>
  );
};

export default function App() {
  return (
    <ToastProvider>
      <AuthProvider>
        <RealtimeProvider>
          <AppContent />
        </RealtimeProvider>
      </AuthProvider>
    </ToastProvider>
  );
}
