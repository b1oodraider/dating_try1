import React, { useState } from 'react';
import { MessageCircle } from 'lucide-react';
import type { CommunityRef, Interest } from '../../../core/types';

/** Interests with emoji; one with a comment opens it on a click, one without does nothing (as on mobile). */
export const InterestChips: React.FC<{ interests: Interest[] }> = ({ interests }) => {
  const [open, setOpen] = useState<string | null>(null);
  const opened = interests.find((i) => i.code === open && i.comment);
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-1.5">
        {interests.map((i) => {
          const text = i.emoji ? `${i.emoji} ${i.label}` : i.label;
          return i.comment ? (
            <button
              key={i.code}
              type="button"
              aria-pressed={open === i.code}
              onClick={() => setOpen(open === i.code ? null : i.code)}
              className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs border transition-colors ${
                open === i.code ? 'bg-zinc-900 text-white border-zinc-900' : 'bg-zinc-100 text-zinc-700 border-zinc-200 hover:border-zinc-400'
              }`}
            >
              {text}
              <MessageCircle className="w-3 h-3" aria-label="есть комментарий" />
            </button>
          ) : (
            <span key={i.code} className="inline-flex items-center px-2.5 py-1 rounded-full text-xs bg-zinc-100 text-zinc-700 border border-transparent">
              {text}
            </span>
          );
        })}
      </div>
      {opened && <p className="text-sm text-zinc-700 bg-zinc-50 border border-zinc-200 rounded-xl px-3 py-2">{opened.comment}</p>}
    </div>
  );
};

/** Interest groups of a profile; the ones the viewer is in too come first and are highlighted. */
export const CommunityChips: React.FC<{ communities: CommunityRef[] }> = ({ communities }) => (
  <div className="flex flex-wrap gap-1.5">
    {[...communities].sort((a, b) => Number(b.shared) - Number(a.shared)).map((c) => (
      <span
        key={c.id}
        className={`inline-flex items-center px-2.5 py-1 rounded-full text-xs ${
          c.shared ? 'bg-rose-50 text-rose-700 border border-rose-200 font-medium' : 'bg-zinc-100 text-zinc-700'
        }`}
        title={c.shared ? 'Вы тоже в этой группе' : undefined}
      >
        {c.emoji ? `${c.emoji} ${c.title}` : c.title}
      </span>
    ))}
  </div>
);
