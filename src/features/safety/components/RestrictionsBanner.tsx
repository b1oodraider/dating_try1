import React, { useEffect, useState } from 'react';
import { api, formatDateTime, RESTRICTIONS_CHANGED } from '../../../core/api/client';
import { useAuth } from '../../../core/context/AuthContext';
import type { Restriction } from '../../../core/types';
import { ShieldAlert } from 'lucide-react';

const KIND_TEXT: Record<Restriction['kind'], string> = {
  LIKES: 'лайки и суперлайки временно недоступны',
  MESSAGES: 'отправка сообщений временно недоступна',
  HIDDEN: 'анкета скрыта из ленты',
  SUSPENDED: 'аккаунт заблокирован',
  VERIFICATION_REQUIRED: 'анкета скрыта до проверки селфи',
};

const REASON_TEXT: Record<string, string> = {
  LIKE_SPAM: 'слишком много лайков подряд',
  REVIEW_HOLD: 'на анкету поступили жалобы, она на проверке',
};

/** My active restrictions (GET /me/restrictions): reloads on login and whenever an action was refused. */
export const RestrictionsBanner: React.FC = () => {
  const { account } = useAuth();
  const accountId = account?.id ?? null;
  const [restrictions, setRestrictions] = useState<Restriction[]>([]);

  useEffect(() => {
    if (!accountId) {
      setRestrictions([]);
      return;
    }
    let cancelled = false;
    const load = () => {
      api
        .getRestrictions()
        .then((data) => {
          if (!cancelled) setRestrictions(data);
        })
        .catch(() => {
          // the banner is best effort
        });
    };
    load();
    window.addEventListener(RESTRICTIONS_CHANGED, load);
    return () => {
      cancelled = true;
      window.removeEventListener(RESTRICTIONS_CHANGED, load);
    };
  }, [accountId]);

  if (restrictions.length === 0) return null;

  return (
    <div className="bg-amber-500 text-white px-4 py-2 text-xs" role="status">
      <div className="max-w-6xl mx-auto flex items-start gap-2">
        <ShieldAlert className="w-4 h-4 shrink-0 mt-0.5" />
        <ul className="space-y-0.5">
          {restrictions.map((r) => (
            <li key={`${r.kind}-${r.startsAt}`}>
              <strong>{KIND_TEXT[r.kind] ?? r.kind}</strong>
              {REASON_TEXT[r.reason] ? ` — ${REASON_TEXT[r.reason]}` : ''}
              {r.endsAt ? `, до ${formatDateTime(r.endsAt)}` : ', до решения модератора'}
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
};
