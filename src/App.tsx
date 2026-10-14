import React, { useState } from 'react';
import { AuthProvider, useAuth } from './core/context/AuthContext';
import { Navbar } from './shared/components/Navbar';
import { DiscoveryFeed } from './features/discovery/components/DiscoveryFeed';
import { ProfileEditor } from './features/profile/components/ProfileEditor';
import { AboutView } from './features/about/AboutView';
import { AuthModal } from './features/auth/components/AuthModal';
import { AccountSettingsModal } from './features/account/components/AccountSettingsModal';

const AppContent: React.FC = () => {
  const { account, isLoading } = useAuth();
  const [currentTab, setCurrentTab] = useState<'discovery' | 'profile' | 'about'>('discovery');
  const [isAuthModalOpen, setIsAuthModalOpen] = useState(false);
  const [isSettingsModalOpen, setIsSettingsModalOpen] = useState(false);

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

  return (
    <div className="min-h-screen bg-stone-100/60 text-zinc-900 flex flex-col font-sans selection:bg-rose-100 selection:text-rose-900">
      {/* Top Navigation */}
      <Navbar
        currentTab={currentTab}
        onTabChange={(tab) => setCurrentTab(tab)}
        onOpenAuth={() => setIsAuthModalOpen(true)}
        onOpenSettings={() => setIsSettingsModalOpen(true)}
      />

      {/* Main Content View */}
      <main className="flex-1 max-w-6xl w-full mx-auto px-4 sm:px-6 pt-6">
        {currentTab === 'discovery' && <DiscoveryFeed />}

        {currentTab === 'profile' && (
          account ? (
            <ProfileEditor />
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
                className="px-5 py-2.5 bg-zinc-900 text-white rounded-xl text-xs font-semibold hover:bg-zinc-800 transition-colors shadow-xs"
              >
                Войти по номеру телефона
              </button>
            </div>
          )
        )}

        {currentTab === 'about' && <AboutView />}
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
    </div>
  );
};

export default function App() {
  return (
    <AuthProvider>
      <AppContent />
    </AuthProvider>
  );
}
