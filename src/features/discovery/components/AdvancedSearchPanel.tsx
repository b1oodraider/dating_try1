import React, { useEffect, useState } from 'react';
import { Button } from '../../../shared/components/Button';
import { useGoals } from '../../goals/hooks/useGoals';
import { GoalToggleList } from '../../goals/components/GoalChips';
import { PremiumLockHint } from '../../premium/components/PremiumBadge';
import { ExcludedGoalsEditor } from './ExcludedGoalsEditor';
import { ExclusionWordsEditor } from './ExclusionWordsEditor';
import { AlertCircle, ChevronDown, Crown, Ruler, Target } from 'lucide-react';

/** Same range as PUT /me/profile heightCm. */
export const HEIGHT_MIN = 120;
export const HEIGHT_MAX = 230;

export interface AdvancedFilters {
  minHeight?: number;
  maxHeight?: number;
  /** "Ищу цели": the profile has at least one of them. */
  goalCodes: string[];
}

export const EMPTY_ADVANCED_FILTERS: AdvancedFilters = { goalCodes: [] };

export function countAdvancedFilters(f: AdvancedFilters): number {
  return (f.minHeight !== undefined || f.maxHeight !== undefined ? 1 : 0) + (f.goalCodes.length > 0 ? 1 : 0);
}

/** "" -> undefined; otherwise an integer in the allowed range, or an error message. */
function parseHeight(raw: string): { value?: number; error?: string } {
  const text = raw.trim();
  if (text === '') return {};
  const n = Number(text);
  if (!Number.isInteger(n) || n < HEIGHT_MIN || n > HEIGHT_MAX) {
    return { error: `Рост — целое число от ${HEIGHT_MIN} до ${HEIGHT_MAX} см` };
  }
  return { value: n };
}

const toText = (n: number | undefined) => (n === undefined ? '' : String(n));

interface AdvancedSearchPanelProps {
  filters: AdvancedFilters;
  onFiltersChange: (filters: AdvancedFilters) => void;
  /** Premium ADVANCED_FILTERS unavailable: height/goals filters and excluded goals are disabled. */
  advancedLocked: boolean;
  /** Premium EXCLUSION_WORDS unavailable: exclusion words are read-only. */
  wordsLocked: boolean;
  /** Changing it remounts the saved-list editors, so they reload (e.g. after premium changed). */
  savedListsKey: string;
  /** A saved list was loaded or saved: counts of entries that are actually applied to the feed. */
  onSavedListsInfo: (info: { excludedGoals?: number; exclusionWords?: number }) => void;
  /** A saved list changed on the server: the feed should reload from page 0. */
  onSavedListsChanged: () => void;
  onPremiumRequired: (feature: string) => void;
}

/** "Расширенный поиск · Премиум": height, goals, excluded goals and exclusion words. */
export const AdvancedSearchPanel: React.FC<AdvancedSearchPanelProps> = ({
  filters,
  onFiltersChange,
  advancedLocked,
  wordsLocked,
  savedListsKey,
  onSavedListsInfo,
  onSavedListsChanged,
  onPremiumRequired,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const { goals, isLoading: isGoalsLoading, error: goalsError, reload: reloadGoals } = useGoals();

  // Height inputs are applied explicitly (button / Enter) so the feed doesn't reload on every keystroke.
  const [minDraft, setMinDraft] = useState(toText(filters.minHeight));
  const [maxDraft, setMaxDraft] = useState(toText(filters.maxHeight));
  const [heightError, setHeightError] = useState<string | null>(null);
  useEffect(() => {
    setMinDraft(toText(filters.minHeight));
    setMaxDraft(toText(filters.maxHeight));
    setHeightError(null);
  }, [filters.minHeight, filters.maxHeight]);

  const heightDirty = minDraft.trim() !== toText(filters.minHeight) || maxDraft.trim() !== toText(filters.maxHeight);

  const applyHeight = () => {
    const min = parseHeight(minDraft);
    const max = parseHeight(maxDraft);
    const error = min.error ?? max.error;
    if (error) {
      setHeightError(error);
      return;
    }
    if (min.value !== undefined && max.value !== undefined && min.value > max.value) {
      setHeightError('Рост «от» больше, чем «до»');
      return;
    }
    setHeightError(null);
    onFiltersChange({ ...filters, minHeight: min.value, maxHeight: max.value });
  };

  const toggleGoal = (code: string) => {
    const next = filters.goalCodes.includes(code)
      ? filters.goalCodes.filter((c) => c !== code)
      : [...filters.goalCodes, code];
    onFiltersChange({ ...filters, goalCodes: next });
  };

  const activeCount = advancedLocked ? 0 : countAdvancedFilters(filters);
  const anyLocked = advancedLocked || wordsLocked;
  const heightInput =
    'w-20 px-2.5 py-1.5 text-xs font-mono bg-zinc-50 border border-zinc-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-zinc-900 disabled:opacity-60 disabled:cursor-not-allowed';

  return (
    <div className="bg-white rounded-2xl border border-amber-200/80 p-4 shadow-xs space-y-3">
      <button
        type="button"
        onClick={() => setIsOpen(!isOpen)}
        aria-expanded={isOpen}
        className="w-full flex items-center justify-between gap-2 text-left cursor-pointer"
      >
        <span className="flex items-center gap-2 text-xs font-semibold text-zinc-800">
          <Crown className="w-3.5 h-3.5 text-amber-500" />
          Расширенный поиск · Премиум
          {activeCount > 0 && (
            <span className="px-1.5 py-0.5 leading-none bg-amber-500 text-white rounded-full text-[10px] font-bold">
              {activeCount}
            </span>
          )}
        </span>
        <span className="flex items-center gap-2 text-[11px] text-zinc-400">
          {anyLocked ? (
            <PremiumLockHint />
          ) : (
            <span className="hidden sm:inline">Рост, цели, скрытые цели и слова</span>
          )}
          <ChevronDown className={`w-4 h-4 transition-transform ${isOpen ? 'rotate-180' : ''}`} />
        </span>
      </button>

      {/* Kept mounted while collapsed: the saved lists load right away (needed to explain an empty feed). */}
      <div hidden={!isOpen} className="pt-3 border-t border-zinc-100 space-y-4">
        {anyLocked && (
          <div className="p-2.5 bg-amber-50 border border-amber-200 text-amber-800 text-[11px] rounded-xl flex items-start gap-2">
            <Crown className="w-3.5 h-3.5 shrink-0 mt-0.5 text-amber-500" />
            <span>
              Доступно с премиумом: поиск по росту и целям, скрытие анкет по целям и словам. Сохранённые
              списки не удаляются — они снова заработают, когда премиум будет активен.
            </span>
          </div>
        )}

        {goalsError && (
          <div className="p-2.5 bg-rose-50 border border-rose-200 text-rose-700 text-xs rounded-xl flex items-center justify-between gap-2">
            <span className="flex items-center gap-2">
              <AlertCircle className="w-4 h-4 shrink-0" />
              {goalsError}
            </span>
            <Button type="button" variant="outline" size="sm" onClick={reloadGoals}>
              Повторить
            </Button>
          </div>
        )}

        {/* Height */}
        <section className="space-y-1.5" aria-label="Рост">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="flex items-center gap-2 text-xs font-semibold text-zinc-800">
              <Ruler className="w-3.5 h-3.5 text-zinc-500" /> Рост, см
            </span>
            {advancedLocked && <PremiumLockHint />}
          </div>
          <form
            className="flex flex-wrap items-center gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              applyHeight();
            }}
          >
            <label className="text-[11px] text-zinc-500 flex items-center gap-1.5">
              от
              <input
                type="number"
                inputMode="numeric"
                min={HEIGHT_MIN}
                max={HEIGHT_MAX}
                value={minDraft}
                onChange={(e) => setMinDraft(e.target.value)}
                disabled={advancedLocked}
                placeholder={String(HEIGHT_MIN)}
                aria-invalid={heightError !== null}
                className={heightInput}
              />
            </label>
            <label className="text-[11px] text-zinc-500 flex items-center gap-1.5">
              до
              <input
                type="number"
                inputMode="numeric"
                min={HEIGHT_MIN}
                max={HEIGHT_MAX}
                value={maxDraft}
                onChange={(e) => setMaxDraft(e.target.value)}
                disabled={advancedLocked}
                placeholder={String(HEIGHT_MAX)}
                aria-invalid={heightError !== null}
                className={heightInput}
              />
            </label>
            <Button type="submit" variant="outline" size="sm" disabled={advancedLocked || !heightDirty}>
              Применить
            </Button>
            {!advancedLocked && (filters.minHeight !== undefined || filters.maxHeight !== undefined) && (
              <button
                type="button"
                onClick={() => onFiltersChange({ ...filters, minHeight: undefined, maxHeight: undefined })}
                className="text-[11px] text-zinc-400 hover:text-rose-600 transition-colors"
              >
                Сбросить рост
              </button>
            )}
          </form>
          {heightError && (
            <p role="alert" className="text-[11px] text-rose-600 flex items-center gap-1">
              <AlertCircle className="w-3 h-3 shrink-0" /> {heightError}
            </p>
          )}
          <p className="text-[11px] text-zinc-400">Анкеты без указанного роста при этом фильтре не показываются.</p>
        </section>

        {/* Wanted goals */}
        <section className="space-y-1.5" aria-label="Ищу цели">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="flex items-center gap-2 text-xs font-semibold text-zinc-800">
              <Target className="w-3.5 h-3.5 text-zinc-500" /> Ищу цели
              <span className="text-[11px] font-normal text-zinc-400">— любая из отмеченных</span>
            </span>
            {advancedLocked && <PremiumLockHint />}
          </div>
          {isGoalsLoading && <p className="text-xs text-zinc-400">Загрузка целей...</p>}
          <GoalToggleList
            goals={goals}
            selectedCodes={filters.goalCodes}
            onToggle={toggleGoal}
            disabled={advancedLocked}
          />
        </section>

        <div className="border-t border-zinc-100" />

        <ExcludedGoalsEditor
          key={`goals:${savedListsKey}`}
          goals={goals}
          locked={advancedLocked}
          onLoaded={(codes, active) => onSavedListsInfo({ excludedGoals: active ? codes.length : 0 })}
          onSaved={(codes, active) => {
            onSavedListsInfo({ excludedGoals: active ? codes.length : 0 });
            onSavedListsChanged();
          }}
          onPremiumRequired={onPremiumRequired}
        />

        <div className="border-t border-zinc-100" />

        <ExclusionWordsEditor
          key={`words:${savedListsKey}`}
          locked={wordsLocked}
          onLoaded={(words, active) => onSavedListsInfo({ exclusionWords: active ? words.length : 0 })}
          onSaved={(words, active) => {
            onSavedListsInfo({ exclusionWords: active ? words.length : 0 });
            onSavedListsChanged();
          }}
          onPremiumRequired={onPremiumRequired}
        />
      </div>
    </div>
  );
};
