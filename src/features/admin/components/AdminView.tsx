import React, { useState } from 'react';
import { useAuth } from '../../../core/context/AuthContext';
import type { Account } from '../../../core/types';
import { ReportsPanel } from './ReportsPanel';
import { VerificationsPanel } from './VerificationsPanel';
import { PhotoReviewsPanel } from './PhotoReviewsPanel';
import { AccountsPanel } from './AccountsPanel';
import { AuditPanel } from './AuditPanel';
import { SupportPanel } from './SupportPanel';

/** Moderation endpoints accept MODERATOR or ADMIN. */
const isModerator = (account: Account | null) => !!account?.roles?.some((r) => r === 'ADMIN' || r === 'MODERATOR');

/** Who sees the cabinet: moderators, admins and support (support sees only tickets; content editors have no screens here). */
export const isStaff = (account: Account | null) => isModerator(account) || !!account?.roles?.includes('SUPPORT');

type Section = 'reports' | 'verifications' | 'photos' | 'accounts' | 'support' | 'audit';

export const AdminView: React.FC = () => {
  const { account } = useAuth();
  const isAdmin = !!account?.roles?.includes('ADMIN');
  const moderator = isModerator(account);
  const [section, setSection] = useState<Section>(moderator ? 'reports' : 'support');
  const [accountId, setAccountId] = useState<string | null>(null);

  if (!isStaff(account)) {
    return (
      <p className="py-16 text-center text-sm text-zinc-500">
        Кабинет доступен только модераторам, поддержке и администраторам.
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
    { id: 'support', label: 'Поддержка' },
    ...(isAdmin ? [{ id: 'audit' as const, label: 'Журнал' }] : []),
  ];

  return (
    <div className="max-w-3xl mx-auto space-y-4 pb-10 min-w-0">
      <h1 className="text-lg font-bold text-zinc-900">{moderator ? 'Кабинет модератора' : 'Кабинет поддержки'}</h1>
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
      {section === 'support' && <SupportPanel onOpenAccount={moderator ? openAccount : undefined} />}
      {section === 'reports' && <ReportsPanel onOpenAccount={openAccount} />}
      {section === 'verifications' && <VerificationsPanel onOpenAccount={openAccount} />}
      {section === 'photos' && <PhotoReviewsPanel onOpenAccount={openAccount} />}
      {section === 'accounts' && <AccountsPanel isAdmin={isAdmin} accountId={accountId} onOpenAccount={openAccount} />}
      {section === 'audit' && isAdmin && <AuditPanel />}
    </div>
  );
};
