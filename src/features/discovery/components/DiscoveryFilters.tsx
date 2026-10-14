import React, { useState } from 'react';
import { COMMUNICATION_TAGS, TAG_CATEGORIES } from '../../tags/constants/communicationTags';
import { TagChip } from '../../tags/components/TagChip';
import { Button } from '../../../shared/components/Button';
import { Filter, RotateCcw, SlidersHorizontal } from 'lucide-react';

interface DiscoveryFiltersProps {
  selectedTagCodes: string[];
  tagFilterLogic: 'AND' | 'OR';
  selectedCity: string;
  onFilterChange: (filters: {
    selectedTagCodes: string[];
    tagFilterLogic: 'AND' | 'OR';
    selectedCity: string;
  }) => void;
  onReset: () => void;
}

export const DiscoveryFilters: React.FC<DiscoveryFiltersProps> = ({
  selectedTagCodes,
  tagFilterLogic,
  selectedCity,
  onFilterChange,
  onReset,
}) => {
  const [isOpen, setIsOpen] = useState(false);

  const cities = ['Все города', 'Москва', 'Санкт-Петербург', 'Екатеринбург', 'Казань'];

  const handleToggleTag = (code: string) => {
    let next: string[];
    if (selectedTagCodes.includes(code)) {
      next = selectedTagCodes.filter((c) => c !== code);
    } else {
      next = [...selectedTagCodes, code];
    }
    onFilterChange({
      selectedTagCodes: next,
      tagFilterLogic,
      selectedCity,
    });
  };

  const handleLogicChange = (logic: 'AND' | 'OR') => {
    onFilterChange({
      selectedTagCodes,
      tagFilterLogic: logic,
      selectedCity,
    });
  };

  const handleCityChange = (city: string) => {
    onFilterChange({
      selectedTagCodes,
      tagFilterLogic,
      selectedCity: city === 'Все города' ? 'ALL' : city,
    });
  };

  const activeFiltersCount =
    (selectedTagCodes.length > 0 ? 1 : 0) + (selectedCity !== 'ALL' && selectedCity !== 'Все города' ? 1 : 0);

  return (
    <div className="bg-white rounded-2xl border border-zinc-200/80 p-4 shadow-xs space-y-3">
      {/* Quick bar */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <Button
            type="button"
            variant={isOpen ? 'primary' : 'outline'}
            size="sm"
            onClick={() => setIsOpen(!isOpen)}
          >
            <SlidersHorizontal className="w-3.5 h-3.5 mr-1" />
            Фильтры
            {activeFiltersCount > 0 && (
              <span className="ml-1 px-1.5 py-0.2 bg-amber-500 text-white rounded-full text-[10px] font-bold">
                {selectedTagCodes.length}
              </span>
            )}
          </Button>

          {/* Quick city select */}
          <select
            value={selectedCity === 'ALL' ? 'Все города' : selectedCity}
            onChange={(e) => handleCityChange(e.target.value)}
            className="text-xs bg-zinc-50 border border-zinc-200 rounded-xl px-3 py-1.5 focus:outline-none focus:ring-2 focus:ring-zinc-900"
          >
            {cities.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </div>

        {/* Tag logic toggle (AND / OR) */}
        {selectedTagCodes.length > 1 && (
          <div className="flex items-center gap-1.5 text-xs bg-zinc-100 p-1 rounded-xl">
            <span className="text-[11px] text-zinc-500 pl-1.5">Совпадение:</span>
            <button
              type="button"
              onClick={() => handleLogicChange('OR')}
              className={`px-2.5 py-0.5 rounded-lg font-medium transition-colors ${
                tagFilterLogic === 'OR'
                  ? 'bg-white text-zinc-900 shadow-xs'
                  : 'text-zinc-500 hover:text-zinc-900'
              }`}
            >
              Любая (ИЛИ)
            </button>
            <button
              type="button"
              onClick={() => handleLogicChange('AND')}
              className={`px-2.5 py-0.5 rounded-lg font-medium transition-colors ${
                tagFilterLogic === 'AND'
                  ? 'bg-white text-zinc-900 shadow-xs'
                  : 'text-zinc-500 hover:text-zinc-900'
              }`}
            >
              Все сразу (И)
            </button>
          </div>
        )}

        {(selectedTagCodes.length > 0 || (selectedCity !== 'ALL' && selectedCity !== 'Все города')) && (
          <button
            type="button"
            onClick={onReset}
            className="text-xs text-zinc-400 hover:text-rose-600 flex items-center gap-1 transition-colors"
          >
            <RotateCcw className="w-3 h-3" /> Сбросить
          </button>
        )}
      </div>

      {/* Expanded filters panel */}
      {isOpen && (
        <div className="pt-3 border-t border-zinc-100 space-y-4 animate-in fade-in duration-150">
          <div>
            <div className="flex items-center justify-between mb-2">
              <span className="text-xs font-semibold text-zinc-700">
                Фильтрация по карточкам предпочтений
              </span>
              <span className="text-[11px] text-zinc-400">
                Нажмите на карточку, чтобы искать людей с таким стилем общения
              </span>
            </div>

            {TAG_CATEGORIES.map((cat) => {
              const categoryTags = COMMUNICATION_TAGS.filter((t) => t.category === cat.id);
              return (
                <div key={cat.id} className="mb-2.5">
                  <div className="text-[11px] font-medium text-zinc-400 uppercase tracking-wider mb-1">
                    {cat.label}
                  </div>
                  <div className="flex flex-wrap gap-1.5">
                    {categoryTags.map((tag) => {
                      const isSelected = selectedTagCodes.includes(tag.code);
                      return (
                        <TagChip
                          key={tag.code}
                          tag={tag}
                          isSelected={isSelected}
                          onToggle={() => handleToggleTag(tag.code)}
                          size="sm"
                        />
                      );
                    })}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
};
