import React from 'react';
import type { Tag } from '../../../core/types';
import { useTags } from '../hooks/useTags';
import { Button } from '../../../shared/components/Button';
import { AlertCircle } from 'lucide-react';

interface TagPickerProps {
  selectedTags: Tag[];
  onChange: (tags: Tag[]) => void;
  disabled?: boolean;
}

/** One card per category (backend: 400 ONE_TAG_PER_CATEGORY); clicking the chosen card again clears it. */
export const TagPicker: React.FC<TagPickerProps> = ({ selectedTags, onChange, disabled = false }) => {
  const { categories, isLoading, error, reload } = useTags();
  const selectedCodes = new Set(selectedTags.map((t) => t.code));

  const handleToggle = (tag: Tag) => {
    if (disabled) return;
    const others = selectedTags.filter((t) => t.category !== tag.category);
    onChange(selectedCodes.has(tag.code) ? others : [...others, tag]);
  };

  if (isLoading) {
    return (
      <div className="py-8 flex items-center justify-center gap-2 text-xs text-zinc-500">
        <div className="w-4 h-4 border-2 border-zinc-900 border-t-transparent rounded-full animate-spin" />
        Загрузка карточек...
      </div>
    );
  }

  if (error) {
    return (
      <div className="p-3 bg-rose-50 border border-rose-200 text-rose-700 text-xs rounded-xl flex items-center justify-between gap-2">
        <span className="flex items-center gap-2">
          <AlertCircle className="w-4 h-4 shrink-0" />
          {error}
        </span>
        <Button type="button" variant="outline" size="sm" onClick={reload}>
          Повторить
        </Button>
      </div>
    );
  }

  return (
    <div className="space-y-4 max-h-[60vh] overflow-y-auto pr-1">
      <p className="text-xs text-zinc-500">
        По одному ответу в каждой категории — можно пропустить. Нажмите ещё раз, чтобы снять выбор.
        Совпадения подсвечиваются в анкетах.
      </p>
      {categories.map((cat) => (
        <div key={cat.id}>
          <p className="text-[11px] font-semibold text-zinc-500 mb-1.5">{cat.label || 'Другое'}</p>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            {cat.tags.map((tag) => {
              const isSelected = selectedCodes.has(tag.code);
              return (
                <button
                  key={tag.code}
                  type="button"
                  onClick={() => handleToggle(tag)}
                  aria-pressed={isSelected}
                  className={`p-3 rounded-xl border text-left transition-all ${
                    disabled ? 'cursor-wait opacity-70' : 'cursor-pointer'
                  } ${
                    isSelected
                      ? 'bg-zinc-900 text-white border-zinc-900 shadow-sm'
                      : 'bg-white border-zinc-200 hover:border-zinc-300 hover:bg-zinc-50/50'
                  }`}
                >
                  <span className={`text-xs font-semibold ${isSelected ? 'text-zinc-100' : 'text-zinc-900'}`}>
                    {tag.label}
                  </span>
                  <p className={`text-xs mt-1 leading-relaxed ${isSelected ? 'text-zinc-300' : 'text-zinc-500'}`}>
                    {tag.description}
                  </p>
                </button>
              );
            })}
          </div>
        </div>
      ))}
    </div>
  );
};
