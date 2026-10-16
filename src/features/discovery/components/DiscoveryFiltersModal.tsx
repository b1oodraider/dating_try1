import React, { useState } from 'react';
import type { TagLogic } from '../../../core/types';
import { Modal } from '../../../shared/components/Modal';
import { Button } from '../../../shared/components/Button';
import { useTags } from '../../tags/hooks/useTags';
import { TagChip } from '../../tags/components/TagChip';
import { useGoals } from '../../goals/hooks/useGoals';
import { GoalToggleList } from '../../goals/components/GoalChips';
import { PremiumLockHint } from '../../premium/components/PremiumBadge';
import { ExcludedGoalsEditor } from './ExcludedGoalsEditor';
import { ExclusionWordsEditor } from './ExclusionWordsEditor';
import {
  AGE_MAX,
  AGE_MIN,
  EMPTY_DISCOVERY_FILTERS,
  HEIGHT_MAX,
  HEIGHT_MIN,
  type DiscoveryFilterState,
} from '../filterStorage';
import { AlertCircle, Cake, Crown, MapPin, Ruler, SlidersHorizontal, Target } from 'lucide-react';

const CITIES = ['Москва', 'Санкт-Петербург', 'Екатеринбург', 'Казань'];

const toText = (n: number | undefined) => (n === undefined ? '' : String(n));

/** "" -> undefined; otherwise an integer in [min, max], or an error. */
function parseRangeValue(raw: string, min: number, max: number): { value?: number; error?: boolean } {
  const text = raw.trim();
  if (text === '') return {};
  const n = Number(text);
  return Number.isInteger(n) && n >= min && n <= max ? { value: n } : { error: true };
}

interface RangeDraft {
  from: string;
  to: string;
}

/** Validates a from/to pair. Returns the values or a user-facing error. */
function parseRange(
  draft: RangeDraft,
  min: number,
  max: number,
  what: string
): { from?: number; to?: number; error?: string } {
  const from = parseRangeValue(draft.from, min, max);
  const to = parseRangeValue(draft.to, min, max);
  if (from.error || to.error) return { error: `${what} — целое число от ${min} до ${max}` };
  if (from.value !== undefined && to.value !== undefined && from.value > to.value) {
    return { error: `${what}: «от» больше, чем «до»` };
  }
  return { from: from.value, to: to.value };
}

interface DiscoveryFiltersModalProps {
  onClose: () => void;
  /** Applied filters; the modal edits a draft and applies it with "Показать анкеты". */
  filters: DiscoveryFilterState;
  onApply: (filters: DiscoveryFilterState) => void;
  /** Premium ADVANCED_FILTERS unavailable: height/goals and excluded goals are disabled. */
  advancedLocked: boolean;
  /** Premium EXCLUSION_WORDS unavailable: exclusion words are read-only. */
  wordsLocked: boolean;
  /** A saved list was loaded or saved: counts of entries that actually apply to the feed. */
  onSavedListsInfo: (info: { excludedGoals?: number; exclusionWords?: number }) => void;
  /** A saved list changed on the server: the feed should reload from page 0. */
  onSavedListsChanged: () => void;
  onPremiumRequired: (feature: string) => void;
}

/**
 * The single place for every discovery filter: free filters, premium advanced search and the saved
 * hard filters (excluded goals, exclusion words). Mount it only while open: the draft starts from the
 * applied filters and the saved lists are reloaded every time.
 */
export const DiscoveryFiltersModal: React.FC<DiscoveryFiltersModalProps> = ({
  onClose,
  filters,
  onApply,
  advancedLocked,
  wordsLocked,
  onSavedListsInfo,
  onSavedListsChanged,
  onPremiumRequired,
}) => {
  const { categories, isLoading: isTagsLoading, error: tagsError, reload: reloadTags } = useTags();
  const { goals, isLoading: isGoalsLoading, error: goalsError, reload: reloadGoals } = useGoals();

  const [city, setCity] = useState<string | null>(filters.city);
  const [age, setAge] = useState<RangeDraft>({ from: toText(filters.minAge), to: toText(filters.maxAge) });
  const [tagCodes, setTagCodes] = useState<string[]>(filters.tagCodes);
  const [tagLogic, setTagLogic] = useState<TagLogic>(filters.tagLogic);
  const [height, setHeight] = useState<RangeDraft>({
    from: toText(filters.minHeight),
    to: toText(filters.maxHeight),
  });
  const [goalCodes, setGoalCodes] = useState<string[]>(filters.goalCodes);
  const [ageError, setAgeError] = useState<string | null>(null);
  const [heightError, setHeightError] = useState<string | null>(null);

  const cityOptions = city && !CITIES.includes(city) ? [...CITIES, city] : CITIES;
  const toggle = (list: string[], code: string) =>
    list.includes(code) ? list.filter((c) => c !== code) : [...list, code];

  const handleApply = () => {
    const ageRange = parseRange(age, AGE_MIN, AGE_MAX, 'Возраст');
    const heightRange = advancedLocked ? {} : parseRange(height, HEIGHT_MIN, HEIGHT_MAX, 'Рост');
    setAgeError(ageRange.error ?? null);
    setHeightError(heightRange.error ?? null);
    if (ageRange.error || heightRange.error) return;
    onApply({
      city,
      minAge: ageRange.from,
      maxAge: ageRange.to,
      tagCodes,
      tagLogic,
      // While locked the premium values can't be edited: keep what was saved.
      minHeight: advancedLocked ? filters.minHeight : heightRange.from,
      maxHeight: advancedLocked ? filters.maxHeight : heightRange.to,
      goalCodes: advancedLocked ? filters.goalCodes : goalCodes,
    });
    onClose();
  };

  const handleReset = () => {
    const empty = EMPTY_DISCOVERY_FILTERS;
    setCity(empty.city);
    setAge({ from: '', to: '' });
    setTagCodes(empty.tagCodes);
    setTagLogic(empty.tagLogic);
    setAgeError(null);
    if (!advancedLocked) {
      setHeight({ from: '', to: '' });
      setGoalCodes(empty.goalCodes);
      setHeightError(null);
    }
  };

  const numberInput =
    'w-20 px-2.5 py-1.5 text-xs font-mono bg-zinc-50 border border-zinc-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-zinc-900 disabled:opacity-60 disabled:cursor-not-allowed';
  const sectionTitle = 'flex items-center gap-2 text-xs font-semibold text-zinc-800';
  const errorBox = (message: string, onRetry: () => void) => (
    <div className="p-2.5 bg-rose-50 border border-rose-200 text-rose-700 text-xs rounded-xl flex items-center justify-between gap-2">
      <span className="flex items-center gap-2">
        <AlertCircle className="w-4 h-4 shrink-0" />
        {message}
      </span>
      <Button type="button" variant="outline" size="sm" onClick={onRetry}>
        Повторить
      </Button>
    </div>
  );
  const fieldError = (message: string | null) =>
    message && (
      <p role="alert" className="text-[11px] text-rose-600 flex items-center gap-1">
        <AlertCircle className="w-3 h-3 shrink-0" /> {message}
      </p>
    );
  const range = (
    draft: RangeDraft,
    setDraft: (d: RangeDraft) => void,
    min: number,
    max: number,
    invalid: boolean,
    disabled = false
  ) => (
    <div className="flex flex-wrap items-center gap-2">
      {(['from', 'to'] as const).map((side) => (
        <label key={side} className="text-[11px] text-zinc-500 flex items-center gap-1.5">
          {side === 'from' ? 'от' : 'до'}
          <input
            type="number"
            inputMode="numeric"
            min={min}
            max={max}
            value={draft[side]}
            onChange={(e) => setDraft({ ...draft, [side]: e.target.value })}
            disabled={disabled}
            placeholder={String(side === 'from' ? min : max)}
            aria-invalid={invalid}
            className={numberInput}
          />
        </label>
      ))}
    </div>
  );

  return (
    <Modal isOpen onClose={onClose} title="Фильтры" description="Все настройки поиска анкет в одном месте" maxWidth="2xl">
      <div className="space-y-5">
        {/* ---------------- Free filters ---------------- */}
        <section className="space-y-4" aria-label="Основные фильтры">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div className="space-y-1.5">
              <span className={sectionTitle}>
                <MapPin className="w-3.5 h-3.5 text-zinc-500" /> Город
              </span>
              <select
                value={city ?? ''}
                onChange={(e) => setCity(e.target.value || null)}
                aria-label="Город"
                className="w-full text-xs bg-zinc-50 border border-zinc-200 rounded-xl px-3 py-1.5 focus:outline-none focus:ring-2 focus:ring-zinc-900"
              >
                <option value="">Все города</option>
                {cityOptions.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </div>
            <div className="space-y-1.5">
              <span className={sectionTitle}>
                <Cake className="w-3.5 h-3.5 text-zinc-500" /> Возраст
              </span>
              {range(age, setAge, AGE_MIN, AGE_MAX, ageError !== null)}
              {fieldError(ageError)}
            </div>
          </div>

          <div className="space-y-2">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className={sectionTitle}>
                <SlidersHorizontal className="w-3.5 h-3.5 text-zinc-500" /> Карточки общения
              </span>
              {tagCodes.length > 1 && (
                <div className="flex items-center gap-1.5 text-xs bg-zinc-100 p-1 rounded-xl">
                  <span className="text-[11px] text-zinc-500 pl-1.5">Совпадение:</span>
                  {(['OR', 'AND'] as const).map((logic) => (
                    <button
                      key={logic}
                      type="button"
                      onClick={() => setTagLogic(logic)}
                      aria-pressed={tagLogic === logic}
                      className={`px-2.5 py-0.5 rounded-lg font-medium transition-colors ${
                        tagLogic === logic ? 'bg-white text-zinc-900 shadow-xs' : 'text-zinc-500 hover:text-zinc-900'
                      }`}
                    >
                      {logic === 'OR' ? 'Любая (ИЛИ)' : 'Все сразу (И)'}
                    </button>
                  ))}
                </div>
              )}
            </div>
            {isTagsLoading && <p className="text-xs text-zinc-400">Загрузка карточек...</p>}
            {tagsError && errorBox(tagsError, reloadTags)}
            {categories.map((cat) => (
              <div key={cat.id}>
                <div className="text-[11px] font-medium text-zinc-400 uppercase tracking-wider mb-1">{cat.label}</div>
                <div className="flex flex-wrap gap-1.5">
                  {cat.tags.map((tag) => (
                    <TagChip
                      key={tag.code}
                      tag={tag}
                      isSelected={tagCodes.includes(tag.code)}
                      onToggle={() => setTagCodes(toggle(tagCodes, tag.code))}
                      size="sm"
                    />
                  ))}
                </div>
              </div>
            ))}
          </div>
        </section>

        {/* ---------------- Premium ---------------- */}
        <section className="space-y-4 p-4 rounded-2xl border border-amber-200/80 bg-amber-50/30" aria-label="Расширенный поиск">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="flex items-center gap-2 text-sm font-bold text-zinc-900">
              <Crown className="w-4 h-4 text-amber-500" /> Расширенный поиск · Премиум
            </span>
            {(advancedLocked || wordsLocked) && <PremiumLockHint />}
          </div>

          {(advancedLocked || wordsLocked) && (
            <p className="text-[11px] text-amber-800">
              Доступно с премиумом: поиск по росту и целям, скрытие анкет по целям и словам. Сохранённые значения не
              удаляются — они снова заработают, когда премиум будет активен.
            </p>
          )}

          {goalsError && errorBox(goalsError, reloadGoals)}

          <div className="space-y-1.5">
            <span className={sectionTitle}>
              <Ruler className="w-3.5 h-3.5 text-zinc-500" /> Рост, см
            </span>
            {range(height, setHeight, HEIGHT_MIN, HEIGHT_MAX, heightError !== null, advancedLocked)}
            {fieldError(heightError)}
            <p className="text-[11px] text-zinc-400">Анкеты без указанного роста при этом фильтре не показываются.</p>
          </div>

          <div className="space-y-1.5">
            <span className={sectionTitle}>
              <Target className="w-3.5 h-3.5 text-zinc-500" /> Ищу цели
              <span className="text-[11px] font-normal text-zinc-400">— любая из отмеченных</span>
            </span>
            {isGoalsLoading && <p className="text-xs text-zinc-400">Загрузка целей...</p>}
            <GoalToggleList
              goals={goals}
              selectedCodes={goalCodes}
              onToggle={(code) => setGoalCodes(toggle(goalCodes, code))}
              disabled={advancedLocked}
            />
          </div>

          <div className="border-t border-amber-200/60" />

          {/* Saved hard filters: stored on the server and saved by their own buttons. */}
          <ExcludedGoalsEditor
            goals={goals}
            locked={advancedLocked}
            onLoaded={(codes, active) => onSavedListsInfo({ excludedGoals: active ? codes.length : 0 })}
            onSaved={(codes, active) => {
              onSavedListsInfo({ excludedGoals: active ? codes.length : 0 });
              onSavedListsChanged();
            }}
            onPremiumRequired={onPremiumRequired}
          />

          <div className="border-t border-amber-200/60" />

          <ExclusionWordsEditor
            locked={wordsLocked}
            onLoaded={(words, active) => onSavedListsInfo({ exclusionWords: active ? words.length : 0 })}
            onSaved={(words, active) => {
              onSavedListsInfo({ exclusionWords: active ? words.length : 0 });
              onSavedListsChanged();
            }}
            onPremiumRequired={onPremiumRequired}
          />
        </section>

        {/* TODO(ux): "Доп. фильтры" section (more filters to come) — goes here, nothing implemented yet. */}

        <div className="sticky -bottom-5 -mx-5 -mb-5 px-5 py-3 bg-white border-t border-zinc-100 flex items-center justify-between gap-2">
          <Button type="button" variant="ghost" size="sm" onClick={handleReset}>
            Сбросить
          </Button>
          <Button type="button" variant="primary" size="sm" onClick={handleApply}>
            Показать анкеты
          </Button>
        </div>
      </div>
    </Modal>
  );
};
