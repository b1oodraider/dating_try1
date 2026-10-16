import React, { useState } from 'react';
import type { Tag } from '../../../core/types';
import { useTags } from '../hooks/useTags';
import { Button } from '../../../shared/components/Button';
import { Search, AlertCircle } from 'lucide-react';

/** Backend limit for tagCodes in PUT /me/profile. */
export const MAX_PROFILE_TAGS = 10;

interface TagPickerProps {
  selectedTags: Tag[];
  onChange: (tags: Tag[]) => void;
  maxTags?: number;
  disabled?: boolean;
}

export const TagPicker: React.FC<TagPickerProps> = ({
  selectedTags,
  onChange,
  maxTags = MAX_PROFILE_TAGS,
  disabled = false,
}) => {
  const { tags: allTags, categories, isLoading, error, reload } = useTags();
  const [search, setSearch] = useState('');
  const [activeCategory, setActiveCategory] = useState<string>('all');

  const selectedCodes = new Set(selectedTags.map((t) => t.code));

  const handleToggle = (tag: Tag) => {
    if (disabled) return;
    if (selectedCodes.has(tag.code)) {
      onChange(selectedTags.filter((t) => t.code !== tag.code));
    } else {
      if (selectedTags.length >= maxTags) {
        return; // Max reached
      }
      onChange([...selectedTags, tag]);
    }
  };

  const query = search.trim().toLowerCase();
  const filteredTags = allTags.filter((tag) => {
    const matchesSearch =
      tag.label.toLowerCase().includes(query) ||
      tag.description.toLowerCase().includes(query);
    const matchesCategory =
      activeCategory === 'all' || tag.category === activeCategory;
    return matchesSearch && matchesCategory;
  });

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
    <div className="space-y-4">
      {/* Category filter tabs */}
      <div className="flex items-center gap-1.5 overflow-x-auto pb-1 text-xs no-scrollbar">
        <button
          type="button"
          onClick={() => setActiveCategory('all')}
          className={`px-3 py-1.5 rounded-lg font-medium transition-colors ${
            activeCategory === 'all'
              ? 'bg-zinc-900 text-white'
              : 'bg-zinc-100 text-zinc-600 hover:bg-zinc-200'
          }`}
        >
          Все ({allTags.length})
        </button>
        {categories.map((cat) => {
          const count = cat.tags.length;
          return (
            <button
              key={cat.id}
              type="button"
              onClick={() => setActiveCategory(cat.id)}
              className={`px-3 py-1.5 rounded-lg font-medium transition-colors whitespace-nowrap ${
                activeCategory === cat.id
                  ? 'bg-zinc-900 text-white'
                  : 'bg-zinc-100 text-zinc-600 hover:bg-zinc-200'
              }`}
            >
              {cat.label} ({count})
            </button>
          );
        })}
      </div>

      {/* Search inside tags */}
      <div className="relative">
        <Search className="w-4 h-4 absolute left-3 top-2.5 text-zinc-400" />
        <input
          type="text"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Поиск по карточкам и описаниям..."
          className="w-full pl-9 pr-3 py-2 text-sm bg-zinc-50 border border-zinc-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-zinc-900 focus:bg-white transition-all"
        />
      </div>

      {/* Tag selection indicator */}
      <div className="flex items-center justify-between text-xs text-zinc-500">
        <span>
          Выбрано: <strong className="text-zinc-900">{selectedTags.length}</strong> из {maxTags}
        </span>
        {selectedTags.length >= maxTags && (
          <span className="text-amber-600 font-medium">Достигнут лимит карточек</span>
        )}
      </div>

      {/* Tags grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5 max-h-72 overflow-y-auto pr-1">
        {filteredTags.map((tag) => {
          const isSelected = selectedCodes.has(tag.code);
          return (
            <div
              key={tag.code}
              onClick={() => handleToggle(tag)}
              className={`p-3 rounded-xl border text-left transition-all flex flex-col justify-between ${
                disabled ? 'cursor-wait opacity-70' : 'cursor-pointer'
              } ${
                isSelected
                  ? 'bg-zinc-900 text-white border-zinc-900 shadow-sm'
                  : 'bg-white border-zinc-200 hover:border-zinc-300 hover:bg-zinc-50/50'
              }`}
            >
              <div className="flex items-start justify-between gap-2">
                <span className={`text-xs font-semibold ${isSelected ? 'text-zinc-100' : 'text-zinc-900'}`}>
                  {tag.label}
                </span>
                <span
                  className={`text-[10px] px-1.5 py-0.5 rounded ${
                    isSelected
                      ? 'bg-zinc-800 text-zinc-300'
                      : 'bg-zinc-100 text-zinc-500'
                  }`}
                >
                  {tag.categoryLabel}
                </span>
              </div>
              <p
                className={`text-xs mt-1 leading-relaxed ${
                  isSelected ? 'text-zinc-300' : 'text-zinc-500'
                }`}
              >
                {tag.description}
              </p>
            </div>
          );
        })}
      </div>
    </div>
  );
};
