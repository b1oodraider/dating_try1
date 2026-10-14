import React from 'react';
import { Profile } from '../../../core/types';
import { CheckCircle2, CircleDashed } from 'lucide-react';

interface CompletenessHintProps {
  profile: Profile;
}

export const CompletenessHint: React.FC<CompletenessHintProps> = ({ profile }) => {
  const percent = profile.completenessPercent;

  const tips: string[] = [];
  if (!profile.displayName || profile.displayName.trim().length <= 1) {
    tips.push('Укажите имя или псевдоним');
  }
  if (!profile.city || profile.city.trim().length === 0) {
    tips.push('Укажите ваш город');
  }
  if (!profile.aboutText || profile.aboutText.trim().length < 10) {
    tips.push('Напишите пару предложений в «О себе»');
  }
  if (!profile.tags || profile.tags.length < 3) {
    tips.push('Выберите хотя бы 3 карточки комфортного общения');
  }
  if (!profile.photos || profile.photos.length === 0) {
    tips.push('Загрузите хотя бы одну фотографию');
  }

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

      {tips.length > 0 ? (
        <div className="text-[11px] text-stone-500 leading-relaxed pt-1">
          <span className="text-stone-700 font-medium">Подсказка для знакомств:</span>{' '}
          {tips[0]}
          {tips.length > 1 ? ` (ещё советов: ${tips.length - 1})` : ''}.
          <span className="block text-[10px] text-stone-400 mt-0.5">
            * Анкета может быть опубликована в любой момент без обязательного 100% заполнения.
          </span>
        </div>
      ) : (
        <p className="text-[11px] text-emerald-700">
          Анкета подробно заполнена. Собеседникам будет легко понять ваш стиль общения!
        </p>
      )}
    </div>
  );
};
