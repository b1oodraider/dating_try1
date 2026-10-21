import React, { useEffect, useState } from 'react';
import { useAuth } from '../../core/context/AuthContext';
import { Button } from './Button';
import {
  HeartHandshake,
  User,
  Compass,
  Settings,
  ShieldCheck,
  LogIn,
  Gem,
  FlaskConical,
  Crown,
  MessageCircle,
  Gavel,
} from 'lucide-react';
import { isStaff } from '../../features/admin/components/AdminView';

export type AppTab = 'discovery' | 'profile' | 'collection' | 'chats' | 'about' | 'admin';

interface NavbarProps {
  currentTab: AppTab;
  onTabChange: (tab: AppTab) => void;
  onOpenAuth: () => void;
  onOpenSettings: () => void;
  /** Shown only for test accounts (Account.testAccount). */
  onOpenTestPanel: () => void;
  unreadChatsCount?: number;
}

export const Navbar: React.FC<NavbarProps> = ({
  currentTab,
  onTabChange,
  onOpenAuth,
  onOpenSettings,
  onOpenTestPanel,
  unreadChatsCount = 0,
}) => {
  const { account, profile } = useAuth();

  return (
    <header className="sticky top-0 z-40 bg-white/90 backdrop-blur-md border-b border-zinc-200/80">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between gap-4">
        {/* Brand */}
        <div
          onClick={() => onTabChange('discovery')}
          className="flex items-center gap-2.5 cursor-pointer select-none"
        >
          <div className="w-9 h-9 rounded-xl bg-zinc-900 text-white flex items-center justify-center shadow-xs">
            <HeartHandshake className="w-5 h-5 text-rose-400" />
          </div>
          {/* Hidden on phones: the extra staff / test buttons need the room. */}
          <div className="hidden sm:block">
            <div className="font-bold text-base tracking-tight text-zinc-900 flex items-center gap-1.5">
              Dating
              <span className="text-[10px] font-semibold uppercase tracking-wider px-1.5 py-0.5 rounded bg-zinc-100 text-zinc-500 border border-zinc-200">
                MVP
              </span>
            </div>
            <div className="text-[10px] text-zinc-400 -mt-0.5">комфортный темп общения</div>
          </div>
        </div>

        {/* Center Nav tabs */}
        <nav className="flex items-center gap-1 bg-zinc-100/80 p-1 rounded-xl">
          <button
            type="button"
            onClick={() => onTabChange('discovery')}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${
              currentTab === 'discovery'
                ? 'bg-white text-zinc-900 shadow-xs'
                : 'text-zinc-600 hover:text-zinc-900'
            }`}
          >
            <Compass className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">Поиск анкет</span>
          </button>

          <button
            type="button"
            onClick={() => {
              if (!account) {
                onOpenAuth();
              } else {
                onTabChange('chats');
              }
            }}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all relative ${
              currentTab === 'chats'
                ? 'bg-white text-zinc-900 shadow-xs'
                : 'text-zinc-600 hover:text-zinc-900'
            }`}
          >
            <MessageCircle className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">Чаты</span>
            {unreadChatsCount > 0 && (
              <span className="w-4 h-4 rounded-full bg-rose-500 text-white text-[9px] flex items-center justify-center font-bold">
                {unreadChatsCount}
              </span>
            )}
          </button>

          <button
            type="button"
            onClick={() => {
              if (!account) {
                onOpenAuth();
              } else {
                onTabChange('profile');
              }
            }}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${
              currentTab === 'profile'
                ? 'bg-white text-zinc-900 shadow-xs'
                : 'text-zinc-600 hover:text-zinc-900'
            }`}
          >
            <User className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">Моя анкета</span>
          </button>

          <button
            type="button"
            onClick={() => {
              if (!account) {
                onOpenAuth();
              } else {
                onTabChange('collection');
              }
            }}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${
              currentTab === 'collection'
                ? 'bg-white text-zinc-900 shadow-xs'
                : 'text-zinc-600 hover:text-zinc-900'
            }`}
          >
            <Gem className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">Коллекция</span>
          </button>

          <button
            type="button"
            onClick={() => onTabChange('about')}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${
              currentTab === 'about'
                ? 'bg-white text-zinc-900 shadow-xs'
                : 'text-zinc-600 hover:text-zinc-900'
            }`}
          >
            <ShieldCheck className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">О сервисе</span>
          </button>
        </nav>

        {/* Right User Actions */}
        <div className="flex items-center gap-2">
          {account ? (
            <div className="flex items-center gap-1.5">
              {profile?.premium && (
                <span
                  className="hidden xl:flex items-center gap-1 px-2 py-1.5 text-xs font-semibold text-amber-700 bg-amber-50 rounded-xl border border-amber-300"
                  title="Премиум активен"
                >
                  <Crown className="w-3.5 h-3.5 text-amber-500" />
                  Премиум
                </span>
              )}
              {isStaff(account) && (
                <button
                  type="button"
                  onClick={() => onTabChange('admin')}
                  className={`flex items-center gap-1.5 px-2.5 py-1.5 text-xs font-semibold rounded-xl border transition-colors ${
                    currentTab === 'admin'
                      ? 'text-white bg-zinc-900 border-zinc-900'
                      : 'text-zinc-700 bg-zinc-50 hover:bg-zinc-100 border-zinc-200'
                  }`}
                  title="Кабинет"
                >
                  <Gavel className="w-3.5 h-3.5" />
                  <span className="hidden md:inline">Кабинет</span>
                </button>
              )}
              {account.testAccount && (
                <button
                  type="button"
                  onClick={onOpenTestPanel}
                  className="flex items-center gap-1.5 px-2.5 py-1.5 text-xs font-semibold text-violet-700 bg-violet-50 hover:bg-violet-100 rounded-xl border border-violet-200 transition-colors"
                  title="Тест-панель (тестовый аккаунт)"
                >
                  <FlaskConical className="w-3.5 h-3.5" />
                  <span className="hidden md:inline">Тест-панель</span>
                </button>
              )}
              <button
                type="button"
                onClick={onOpenSettings}
                className="flex items-center gap-2 px-2.5 py-1.5 text-xs font-medium text-zinc-700 bg-zinc-50 hover:bg-zinc-100 rounded-xl border border-zinc-200 transition-colors"
                title="Настройки аккаунта"
              >
                {profile?.premium ? (
                  <Crown className="w-3.5 h-3.5 text-amber-500 xl:hidden" aria-label="Премиум активен" />
                ) : null}
                <div className={`w-2 h-2 rounded-full bg-emerald-500 ${profile?.premium ? 'hidden xl:block' : ''}`} />
                <span className="font-mono text-[11px] hidden md:inline">
                  {account.phoneMasked}
                </span>
                <Settings className="w-3.5 h-3.5 text-zinc-400" />
              </button>
            </div>
          ) : (
            <Button
              type="button"
              variant="primary"
              size="sm"
              onClick={onOpenAuth}
            >
              <LogIn className="w-3.5 h-3.5 mr-1" />
              Вход по SMS
            </Button>
          )}
        </div>
      </div>
    </header>
  );
};
