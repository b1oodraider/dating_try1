import React, { useEffect, useState } from 'react';
import { api } from '../../../core/api/client';
import type { Restriction } from '../../../core/types';
import { AlertTriangle, ShieldAlert } from 'lucide-react';

export const RestrictionsBanner: React.FC = () => {
  const [restrictions, setRestrictions] = useState<Restriction[]>([]);

  useEffect(() => {
    let cancelled = false;
    api
      .getRestrictions()
      .then((data) => {
        if (!cancelled) setRestrictions(data);
      })
      .catch(() => {
        // Ignored if user not logged in or endpoint unavailable
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (restrictions.length === 0) return null;

  return (
    <div className="bg-amber-500 text-white px-4 py-2 text-xs">
      <div className="max-w-6xl mx-auto flex items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <ShieldAlert className="w-4 h-4 shrink-0" />
          <span>
            <strong>Ограничение аккаунта:</strong>{' '}
            {restrictions.map((r) => `${r.reason} (${r.kind})`).join('; ')}
          </span>
        </div>
      </div>
    </div>
  );
};
