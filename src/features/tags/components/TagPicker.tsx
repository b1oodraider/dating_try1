import React, { useState } from 'react';
import { CommunicationTag } from '../../../core/types';
import { COMMUNICATION_TAGS, TAG_CATEGORIES } from '../constants/communicationTags';
import { TagChip } from './TagChip';
import { Search } from 'lucide-react';

interface TagPickerProps {
  selectedTags: CommunicationTag[];
  onChange: (tags: CommunicationTag[]) => void;
  maxTags?: number;
}

export const TagPicker: React.FC<TagPickerProps> = ({
  selectedTags,
  onChange,
  maxTags = 8,
}) => {
  const [search, setSearch] = useState('');
  const [activeCategory, setActiveCategory] = useState<string>('all');

  const selectedCodes = new Set(selectedTags.map((t) => t.code));

  const handleToggle = (tag: CommunicationTag) => {
    if (selectedCodes.has(tag.code)) {
      onChange(selectedTags.filter((t) => t.code !== tag.code));
    } else {
      if (selectedTags.length >= maxTags) {
        return; // Max reached
      }
      onChange([...selectedTags, tag]);
    }
  };

  const filteredTags = COMMUNICATION_TAGS.filter((tag) => {
    const matchesSearch =
      tag.label.toLowerCase().includes(search.toLowerCase()) ||
      tag.description.toLowerCase().includes(search.toLowerCase());
    const matchesCategory =
      activeCategory === 'all' || tag.category === activeCategory;
    return matchesSearch && matchesCategory;
  });

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
          Все ({COMMUNICATION_TAGS.length})
        </button>
        {TAG_CATEGORIES.map((cat) => {
          const count = COMMUNICATION_TAGS.filter((t) => t.category === cat.id).length;
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
              className={`p-3 rounded-xl border text-left cursor-pointer transition-all flex flex-col justify-between ${
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
