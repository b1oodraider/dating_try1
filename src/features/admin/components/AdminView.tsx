import React, { useState } from 'react';
import { useAuth } from '../../../core/context/AuthContext';
import type { Account } from '../../../core/types';
import { ReportsPanel } from './ReportsPanel';
import { VerificationsPanel } from './VerificationsPanel';
import { AccountsPanel } from './AccountsPanel';
import { AuditPanel } from './AuditPanel';

/** Moderation endpoints accept MODERATOR or ADMIN; content editors have no screens here (editors are skipped). */
export const isStaff = (account: Account | null) =>
  !!account?.roles?.some((r) => r === 'ADMIN' || r === 'MODERATOR');

type Section = 'reports' | 'verifications' | 'accounts' | 'audit';

export const AdminView: React.FC = () => {
  const { account } = useAuth();
  const isAdmin = !!account?.roles?.includes('ADMIN');
  const [section, setSection] = useState<Section>('reports');
  const [accountId, setAccountId] = useState<string | null>(null);

  if (!isStaff(account)) {
    return <p className="py-16 text-center text-sm text-zinc-500">Кабинет доступен только модераторам и администраторам.</p>;
  }

  const openAccount = (id: string | null) => {
    setAccountId(id);
    setSection('accounts');
  };

  const sections: Array<{ id: Section; label: string }> = [
    { id: 'reports', label: 'Жалобы' },
    { id: 'verifications', label: 'Верификации' },
    { id: 'accounts', label: 'Аккаунты' },
    ...(isAdmin ? [{ id: 'audit' as const, label: 'Журнал' }] : []),
  ];

  return (
    <div className="max-w-3xl mx-auto space-y-4 pb-10 min-w-0">
      <h1 className="text-lg font-bold text-zinc-900">Кабинет модератора</h1>
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
      {section === 'reports' && <ReportsPanel onOpenAccount={openAccount} />}
      {section === 'verifications' && <VerificationsPanel onOpenAccount={openAccount} />}
      {section === 'accounts' && <AccountsPanel isAdmin={isAdmin} accountId={accountId} onOpenAccount={openAccount} />}
      {section === 'audit' && isAdmin && <AuditPanel />}
    </div>
  );
};
