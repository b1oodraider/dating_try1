import React from 'react';
import type { MyProfile, PublishRequirement } from '../../../core/types';
import { CheckCircle2, CircleDashed } from 'lucide-react';

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
  const missing = profile.missingForPublish ?? [];

  return (
    <div className="p-4 bg-stone-50 border border-stone-200/80 rounded-2xl space-y-2.5">
      <div className="flex items-center justify-between text-xs">
        <span className="font-semibold text-stone-800 flex items-center gap-1.5">
          {percent === 100 ? (
            <CheckCircle2 className="w-4 h-4 text-emerald-600" />
          ) : (
            <CircleDashed className="w-4 h-4 text-stone-500" />
          )}
          Заполненность анкеты
        </span>
        <span className="font-mono font-medium text-stone-600">{percent}%</span>
      </div>

      {/* Progress Bar */}
      <div className="w-full bg-stone-200 h-1.5 rounded-full overflow-hidden">
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

      {missing.length > 0 ? (
        <div className="text-[11px] text-stone-500 leading-relaxed pt-1">
          <span className="text-stone-700 font-medium">Для публикации осталось указать:</span>{' '}
          {missing.map(describePublishRequirement).join(', ')}.
          <span className="block text-[10px] text-stone-400 mt-0.5">
            * Для публикации достаточно имени, возраста, города и одной фотографии.
          </span>
        </div>
      ) : percent < 100 ? (
        <div className="text-[11px] text-stone-500 leading-relaxed pt-1">
          <span className="text-stone-700 font-medium">Подсказка для знакомств:</span>{' '}
          анкету уже можно опубликовать. Расскажите о себе и выберите карточки общения — так
          собеседникам будет проще понять ваш стиль.
        </div>
      ) : (
        <p className="text-[11px] text-emerald-700">
          Анкета подробно заполнена. Собеседникам будет легко понять ваш стиль общения!
        </p>
      )}
    </div>
  );
};
