import React from 'react';
import type { Tag } from '../../../core/types';
import { X, Check } from 'lucide-react';

interface TagChipProps {
  tag: Tag;
  isSelected?: boolean;
  onToggle?: (tag: Tag) => void;
  onRemove?: (tag: Tag) => void;
  showDescriptionTooltip?: boolean;
  size?: 'sm' | 'md' | 'lg';
}

export const TagChip: React.FC<TagChipProps> = ({
  tag,
  isSelected = false,
  onToggle,
  onRemove,
  size = 'md',
}) => {
  // Category-specific subtle tint
  const getCategoryStyles = (category: Tag['category'], selected: boolean) => {
    if (selected) {
      switch (category) {
        case 'pace':
          return 'bg-amber-100 text-amber-900 border-amber-300 ring-1 ring-amber-400';
        case 'format':
          return 'bg-sky-100 text-sky-900 border-sky-300 ring-1 ring-sky-400';
        case 'calls':
          return 'bg-violet-100 text-violet-900 border-violet-300 ring-1 ring-violet-400';
        case 'boundaries':
          return 'bg-emerald-100 text-emerald-900 border-emerald-300 ring-1 ring-emerald-400';
        case 'social':
          return 'bg-rose-100 text-rose-900 border-rose-300 ring-1 ring-rose-400';
        default:
          return 'bg-zinc-800 text-white border-zinc-900';
      }
    }

    switch (category) {
      case 'pace':
        return 'bg-amber-50/70 text-amber-800 border-amber-200/80 hover:bg-amber-100/60';
      case 'format':
        return 'bg-sky-50/70 text-sky-800 border-sky-200/80 hover:bg-sky-100/60';
      case 'calls':
        return 'bg-violet-50/70 text-violet-800 border-violet-200/80 hover:bg-violet-100/60';
      case 'boundaries':
        return 'bg-emerald-50/70 text-emerald-800 border-emerald-200/80 hover:bg-emerald-100/60';
      case 'social':
        return 'bg-rose-50/70 text-rose-800 border-rose-200/80 hover:bg-rose-100/60';
      default:
        return 'bg-zinc-50 text-zinc-700 border-zinc-200 hover:bg-zinc-100';
    }
  };

  const sizeClasses = {
    sm: 'text-xs px-2.5 py-1 gap-1',
    md: 'text-xs px-3 py-1.5 gap-1.5',
    lg: 'text-sm px-3.5 py-2 gap-2',
  };

  const isClickable = !!onToggle;

  return (
    <div
      onClick={isClickable ? () => onToggle(tag) : undefined}
      title={tag.description}
      className={`inline-flex items-center rounded-xl border transition-all select-none ${
        sizeClasses[size]
      } ${getCategoryStyles(tag.category, isSelected)} ${
        isClickable ? 'cursor-pointer active:scale-95' : ''
      }`}
    >
      {isSelected && <Check className="w-3.5 h-3.5 stroke-[2.5]" />}
      <span className="font-medium whitespace-nowrap">{tag.label}</span>

      {onRemove && (
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            onRemove(tag);
          }}
          className="ml-0.5 hover:text-rose-600 focus:outline-none"
          aria-label={`Удалить ${tag.label}`}
        >
          <X className="w-3.5 h-3.5" />
        </button>
      )}
    </div>
  );
};
