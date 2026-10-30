import React, { useEffect, useState } from 'react';
import { Users } from 'lucide-react';
import type { Community } from '../../../core/types';
import { api } from '../../../core/api/client';

/** My interest groups as others see them in my profile; subscriptions are managed in «Интересы». */
export const MyCommunitiesCard: React.FC = () => {
  const [mine, setMine] = useState<Community[] | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    api.getMyCommunities(controller.signal).then(setMine).catch(() => {
      if (!controller.signal.aborted) setMine([]);
    });
    return () => controller.abort();
  }, []);

  if (mine === null) return null;
  return (
    <div className="p-4 bg-white border border-zinc-200/80 rounded-2xl shadow-xs space-y-2">
      <h3 className="text-xs font-bold text-zinc-900 flex items-center gap-1.5">
        <Users className="w-4 h-4 text-zinc-500" /> Мои группы
      </h3>
      {mine.length === 0 ? (
        <p className="text-[11px] text-zinc-500">
          Подпишитесь на группы во вкладке «Интересы» — они появятся в анкете, и люди увидят, что у вас общего.
        </p>
      ) : (
        <>
          <div className="flex flex-wrap gap-1.5">
            {mine.map((c) => (
              <span key={c.id} className="px-2.5 py-1 rounded-full text-xs bg-zinc-100 text-zinc-700">
                {c.emoji ? `${c.emoji} ${c.title}` : c.title}
              </span>
            ))}
          </div>
          <p className="text-[11px] text-zinc-400">Изменить подписки можно во вкладке «Интересы».</p>
        </>
      )}
    </div>
  );
};
