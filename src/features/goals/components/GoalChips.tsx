import React from 'react';
import type { Goal } from '../../../core/types';
import { Check, Target } from 'lucide-react';

/** Read-only goal pills (feed card, profile details). */
export const GoalPills: React.FC<{ goals: Goal[] | undefined; size?: 'sm' | 'md'; max?: number }> = ({
  goals,
  size = 'sm',
  max,
}) => {
  const list = goals ?? [];
  if (list.length === 0) return null;
  const shown = max !== undefined ? list.slice(0, max) : list;
  const rest = list.length - shown.length;
  const pill =
    size === 'sm'
      ? 'text-[10px] px-1.5 py-0.5 gap-0.5'
      : 'text-xs px-2.5 py-1 gap-1';
  return (
    <div className="flex flex-wrap items-center gap-1">
      {shown.map((goal) => (
        <span
          key={goal.code}
          className={`inline-flex items-center rounded-full font-medium bg-rose-50 text-rose-700 border border-rose-200 ${pill}`}
        >
          <Target className={size === 'sm' ? 'w-2.5 h-2.5' : 'w-3 h-3'} aria-hidden="true" />
          {goal.label}
        </span>
      ))}
      {rest > 0 && <span className="text-[10px] text-zinc-400 font-medium">+{rest}</span>}
    </div>
  );
};

interface GoalToggleListProps {
  goals: Goal[];
  selectedCodes: string[];
  onToggle: (code: string) => void;
  /** When set, unselected goals are disabled once this many are selected. */
  maxSelected?: number;
  disabled?: boolean;
  /** Visual tone of selected chips: "include" (dark) or "exclude" (rose, crossed out meaning). */
  tone?: 'include' | 'exclude';
}

/** Multi-select of goals as toggle chips. */
export const GoalToggleList: React.FC<GoalToggleListProps> = ({
  goals,
  selectedCodes,
  onToggle,
  maxSelected,
  disabled = false,
  tone = 'include',
}) => {
  const isFull = maxSelected !== undefined && selectedCodes.length >= maxSelected;
  return (
    <div className="flex flex-wrap gap-1.5">
      {goals.map((goal) => {
        const selected = selectedCodes.includes(goal.code);
        const blocked = disabled || (!selected && isFull);
        const selectedClass =
          tone === 'exclude'
            ? 'bg-rose-600 text-white border-rose-600'
            : 'bg-zinc-900 text-white border-zinc-900';
        return (
          <button
            key={goal.code}
            type="button"
            onClick={() => onToggle(goal.code)}
            disabled={blocked}
            aria-pressed={selected}
            className={`inline-flex items-center gap-1 text-xs px-2.5 py-1 rounded-full border font-medium transition-colors disabled:cursor-not-allowed ${
              selected
                ? selectedClass
                : 'bg-white text-zinc-700 border-zinc-200 hover:border-zinc-400 disabled:opacity-50 disabled:hover:border-zinc-200'
            } ${selected && disabled ? 'opacity-60' : ''}`}
          >
            {selected && <Check className="w-3 h-3" aria-hidden="true" />}
            {goal.label}
          </button>
        );
      })}
    </div>
  );
};
