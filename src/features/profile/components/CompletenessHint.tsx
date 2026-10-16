import React, { useState } from 'react';
import type { MyProfile, ProfileTip, PublishRequirement } from '../../../core/types';
import { CheckCircle2, CircleDashed, ChevronDown, ChevronUp, Sparkles, AlertCircle } from 'lucide-react';

export const PUBLISH_REQUIREMENT_LABELS: Record<PublishRequirement, string> = {
  displayName: 'имя или псевдоним',
  age: 'возраст',
  city: 'город',
  photos: 'хотя бы одна фотография',
};

export function describePublishRequirement(field: string): string {
  return PUBLISH_REQUIREMENT_LABELS[field as PublishRequirement] ?? field;
}

interface CompletenessHintProps {
  profile: MyProfile;
}

export const CompletenessHint: React.FC<CompletenessHintProps> = ({ profile }) => {
  const percent = Math.max(0, Math.min(100, Math.round(profile.completenessPercent ?? 0)));
  const tips: ProfileTip[] = profile.tips ?? [];
  const missing = profile.missingForPublish ?? [];
  const [showAllTips, setShowAllTips] = useState(false);

  const pendingTips = tips.filter((t) => !t.done);
  const completedTips = tips.filter((t) => t.done);

  return (
    <div className="p-4 bg-stone-50 border border-stone-200/80 rounded-2xl space-y-3">
      <div className="flex items-center justify-between text-xs">
        <span className="font-semibold text-stone-800 flex items-center gap-1.5">
          {percent === 100 ? (
            <CheckCircle2 className="w-4 h-4 text-emerald-600" />
          ) : (
            <Sparkles className="w-4 h-4 text-amber-500" />
          )}
          Заполненность анкеты
        </span>
        <span className="font-mono font-bold text-stone-700">{percent}%</span>
      </div>

      {/* Progress Bar */}
      <div className="w-full bg-stone-200 h-2 rounded-full overflow-hidden">
        <div
          className={`h-full transition-all duration-300 rounded-full ${
            percent === 100
              ? 'bg-emerald-600'
              : percent > 60
              ? 'bg-amber-600'
              : 'bg-stone-500'
          }`}
          style={{ width: `${percent}%` }}
        />
      </div>

      {/* Weighted Tips List (from backend ProfileTips) */}
      {tips.length > 0 ? (
        <div className="space-y-2 pt-1">
          <div className="space-y-1.5">
            {pendingTips.slice(0, showAllTips ? undefined : 3).map((tip) => (
              <div
                key={tip.code}
                className={`p-2.5 rounded-xl border text-xs flex items-center justify-between gap-2 ${
                  tip.requiredToPublish
                    ? 'bg-rose-50/60 border-rose-200 text-rose-900'
                    : 'bg-white border-stone-200 text-stone-700'
                }`}
              >
                <div className="flex items-center gap-2 min-w-0">
                  <CircleDashed className={`w-3.5 h-3.5 shrink-0 ${tip.requiredToPublish ? 'text-rose-500' : 'text-stone-400'}`} />
                  <span className="truncate">{tip.text}</span>
                  {tip.requiredToPublish && (
                    <span className="text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.2 rounded bg-rose-100 text-rose-700 shrink-0">
                      Для публикации
                    </span>
                  )}
                </div>
                <span className="text-[10px] font-mono font-bold text-stone-500 shrink-0">
                  +{tip.weight}%
                </span>
              </div>
            ))}
          </div>

          {tips.length > 3 && (
            <button
              type="button"
              onClick={() => setShowAllTips(!showAllTips)}
              className="text-[11px] font-semibold text-stone-600 hover:text-stone-900 flex items-center gap-1 transition-colors pt-0.5"
            >
              {showAllTips ? (
                <>
                  <ChevronUp className="w-3.5 h-3.5" /> Свернуть подсказки
                </>
              ) : (
                <>
                  <ChevronDown className="w-3.5 h-3.5" /> Все рекомендации ({tips.length})
                </>
              )}
            </button>
          )}

          {showAllTips && completedTips.length > 0 && (
            <div className="space-y-1 pt-2 border-t border-stone-200/60">
              <span className="text-[10px] font-bold uppercase tracking-wider text-stone-400">
                Уже заполнено
              </span>
              {completedTips.map((tip) => (
                <div
                  key={tip.code}
                  className="p-2 rounded-lg bg-stone-100/50 text-stone-500 text-[11px] flex items-center justify-between line-through opacity-70"
                >
                  <div className="flex items-center gap-2 truncate">
                    <CheckCircle2 className="w-3 h-3 text-emerald-600 shrink-0" />
                    <span className="truncate">{tip.text}</span>
                  </div>
                  <span className="font-mono text-[10px]">+{tip.weight}%</span>
                </div>
              ))}
            </div>
          )}
        </div>
      ) : (
        /* Fallback if tips array is absent */
        missing.length > 0 ? (
          <div className="text-[11px] text-stone-500 leading-relaxed pt-1">
            <span className="text-stone-700 font-medium">Для публикации осталось указать:</span>{' '}
            {missing.map(describePublishRequirement).join(', ')}.
          </div>
        ) : percent < 100 ? (
          <div className="text-[11px] text-stone-500 leading-relaxed pt-1">
            <span className="text-stone-700 font-medium">Подсказка для знакомств:</span> анкету уже можно опубликовать.
            Расскажите о себе и выберите карточки общения — так собеседникам будет проще понять ваш стиль.
          </div>
        ) : (
          <p className="text-[11px] text-emerald-700">
            Анкета подробно заполнена. Собеседникам будет легко понять ваш стиль общения!
          </p>
        )
      )}
    </div>
  );
};
