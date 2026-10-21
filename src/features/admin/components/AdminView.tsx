import React, { useState } from 'react';
import { useAuth } from '../../../core/context/AuthContext';
import type { Account } from '../../../core/types';
import { ReportsPanel } from './ReportsPanel';
import { VerificationsPanel } from './VerificationsPanel';
import { PhotoReviewsPanel } from './PhotoReviewsPanel';
import { AccountsPanel } from './AccountsPanel';
import { AuditPanel } from './AuditPanel';
import { SupportPanel } from './SupportPanel';
import { ContentPanel } from './ContentPanel';
import { CatalogPanel } from './CatalogPanel';
import { AchievementsPanel } from './AchievementsPanel';
import { PremiumPanel } from './PremiumPanel';
import { FaqPanel } from './FaqPanel';

/** Moderation endpoints accept MODERATOR or ADMIN. */
const isModerator = (account: Account | null) => !!account?.roles?.some((r) => r === 'ADMIN' || r === 'MODERATOR');

/** Content endpoints (tags, catalog, achievements) accept CONTENT_EDITOR or ADMIN. */
const isEditor = (account: Account | null) => !!account?.roles?.some((r) => r === 'ADMIN' || r === 'CONTENT_EDITOR');

/** Who sees the cabinet: any staff role (each sees only the sections its role allows). */
export const isStaff = (account: Account | null) =>
  isModerator(account) || isEditor(account) || !!account?.roles?.includes('SUPPORT');

type Section =
  | 'reports'
  | 'verifications'
  | 'photos'
  | 'accounts'
  | 'support'
  | 'content'
  | 'catalog'
  | 'achievements'
  | 'premium'
  | 'faq'
  | 'audit';

export const AdminView: React.FC = () => {
  const { account } = useAuth();
  const isAdmin = !!account?.roles?.includes('ADMIN');
  const moderator = isModerator(account);
  const editor = isEditor(account);
  const support = moderator || !!account?.roles?.includes('SUPPORT');
  const [section, setSection] = useState<Section>(moderator ? 'reports' : support ? 'support' : 'content');
  const [accountId, setAccountId] = useState<string | null>(null);

  if (!isStaff(account)) {
    return (
      <p className="py-16 text-center text-sm text-zinc-500">
        Кабинет доступен только сотрудникам.
      </p>
    );
  }

  const openAccount = (id: string | null) => {
    setAccountId(id);
    setSection('accounts');
  };

  const sections: Array<{ id: Section; label: string }> = [
    ...(moderator
      ? [
          { id: 'reports' as const, label: 'Жалобы' },
          { id: 'verifications' as const, label: 'Верификации' },
          { id: 'photos' as const, label: 'Фото' },
          { id: 'accounts' as const, label: 'Аккаунты' },
        ]
      : []),
    ...(support ? [{ id: 'support' as const, label: 'Поддержка' }] : []),
    ...(editor
      ? [
          { id: 'content' as const, label: 'Карточки и цели' },
          { id: 'catalog' as const, label: 'Каталог' },
          { id: 'achievements' as const, label: 'Достижения' },
        ]
      : []),
    ...(isAdmin ? [{ id: 'premium' as const, label: 'Премиум' }] : []),
    ...(editor || support ? [{ id: 'faq' as const, label: 'FAQ' }] : []),
    ...(isAdmin ? [{ id: 'audit' as const, label: 'Журнал' }] : []),
  ];

  return (
    <div className="max-w-3xl mx-auto space-y-4 pb-10 min-w-0">
      <h1 className="text-lg font-bold text-zinc-900">{moderator ? 'Кабинет модератора' : 'Кабинет'}</h1>
      <nav className="flex flex-wrap gap-1 bg-zinc-100/80 p-1 rounded-xl w-fit max-w-full">
        {sections.map((s) => (
          <button
            key={s.id}
            type="button"
            onClick={() => setSection(s.id)}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all cursor-pointer ${
              section === s.id ? 'bg-white text-zinc-900 shadow-xs' : 'text-zinc-600 hover:text-zinc-900'
            }`}
          >
            {s.label}
          </button>
        ))}
      </nav>
      {section === 'content' && editor && <ContentPanel />}
      {section === 'catalog' && editor && <CatalogPanel isAdmin={isAdmin} />}
      {section === 'achievements' && editor && <AchievementsPanel />}
      {section === 'premium' && isAdmin && <PremiumPanel />}
      {section === 'faq' && <FaqPanel />}
      {section === 'support' && support && <SupportPanel onOpenAccount={moderator ? openAccount : undefined} />}
      {section === 'reports' && <ReportsPanel onOpenAccount={openAccount} />}
      {section === 'verifications' && <VerificationsPanel onOpenAccount={openAccount} />}
      {section === 'photos' && <PhotoReviewsPanel onOpenAccount={openAccount} />}
      {section === 'accounts' && <AccountsPanel isAdmin={isAdmin} accountId={accountId} onOpenAccount={openAccount} />}
      {section === 'audit' && isAdmin && <AuditPanel />}
    </div>
  );
};
