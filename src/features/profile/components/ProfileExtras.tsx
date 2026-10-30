import React, { useEffect, useState } from 'react';
import { Check, Music, Search, X } from 'lucide-react';
import { api, getErrorMessage } from '../../../core/api/client';
import type { Artist, City, Facts, Interest, InterestGroup, ProfileOptions } from '../../../core/types';
import { useToast } from '../../../shared/components/Toast';

export const MAX_INTERESTS = 10;
export const MAX_ARTISTS = 5;
export const LIFESTYLE_FACTS = ['kidsHas', 'kidsPlans', 'smoking', 'alcohol', 'sport', 'pets'] as const;
export const WORK_FACTS = ['profession', 'educationLevel', 'university', 'languages'] as const;
type FactKey = keyof Facts;

const FACT_TITLES: Record<FactKey, string> = {
  kidsHas: 'Дети',
  kidsPlans: 'Планы на детей',
  smoking: 'Курение',
  alcohol: 'Алкоголь',
  sport: 'Спорт',
  pets: 'Животные',
  educationLevel: 'Образование',
  languages: 'Языки',
  profession: 'Профессия',
  university: 'Вуз',
};
const LIST_FACTS: FactKey[] = ['pets', 'languages'];
const TEXT_MAX: Partial<Record<FactKey, number>> = { profession: 80, university: 120 };

const EMPTY_FACTS: Facts = {
  kidsHas: null,
  kidsPlans: null,
  smoking: null,
  alcohol: null,
  sport: null,
  pets: [],
  profession: null,
  educationLevel: null,
  university: null,
  languages: [],
};

/** Server facts with lists never null. */
/** Interest code -> its comment, for the comment inputs. */
export const commentsOf = (interests?: Interest[] | null): Record<string, string> =>
  Object.fromEntries((interests ?? []).filter((i) => i.comment).map((i) => [i.code, i.comment!]));

export const toFacts = (f?: Facts | null): Facts => ({
  ...EMPTY_FACTS,
  ...f,
  pets: f?.pets ?? [],
  languages: f?.languages ?? [],
});

const inputClass =
  'w-full px-3.5 py-2 text-sm bg-zinc-50 border border-zinc-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-zinc-900 focus:bg-white transition-all';

export const Chip: React.FC<{ label: string; selected: boolean; onClick: () => void; disabled?: boolean; title?: string }> = ({
  label,
  selected,
  onClick,
  disabled,
  title,
}) => (
  <button
    type="button"
    onClick={onClick}
    disabled={disabled}
    title={title}
    aria-pressed={selected}
    className={`inline-flex items-center gap-1 text-xs px-2.5 py-1 rounded-full border font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${
      selected ? 'bg-zinc-900 text-white border-zinc-900' : 'bg-white text-zinc-700 border-zinc-200 hover:border-zinc-400'
    }`}
  >
    {selected && <Check className="w-3 h-3" aria-hidden="true" />}
    {label}
  </button>
);

/** Debounced search: results for `query` (≥ minLength chars), stale answers dropped. */
function useSearch<T>(query: string, search: (q: string, signal: AbortSignal) => Promise<T[]>, minLength: number) {
  const [found, setFound] = useState<T[]>([]);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const q = query.trim();
    if (q.length < minLength) {
      setFound([]);
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      search(q, controller.signal)
        .then((list) => {
          setFound(list);
          setError(null);
        })
        .catch((e: unknown) => {
          if (!controller.signal.aborted) setError(getErrorMessage(e, 'Поиск не удался'));
        });
    }, 350);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
    // search wraps a stable api method, so it is left out of the deps.
  }, [query, minLength]);
  return { found, error };
}

/** City from the dictionary (GET /cities); an alias that matched is shown next to the official name. */
export const CityPicker: React.FC<{ cityName: string; onPick: (city: City) => void; error?: string }> = ({
  cityName,
  onPick,
  error,
}) => {
  const [query, setQuery] = useState('');
  const { found, error: searchError } = useSearch(query, (q, s) => api.searchCities(q, s), 1);
  return (
    <div className="relative">
      <div className="relative">
        <Search className="w-4 h-4 absolute left-3 top-2.5 text-zinc-400" />
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={cityName || 'Выбрать город'}
          aria-label="Город"
          aria-invalid={!!error}
          className={`${inputClass} pl-9 ${cityName ? 'placeholder:text-zinc-900' : ''} ${error ? 'border-rose-300' : ''}`}
        />
      </div>
      {(found.length > 0 || searchError) && (
        <ul className="absolute z-10 mt-1 w-full max-h-60 overflow-y-auto bg-white border border-zinc-200 rounded-xl shadow-lg text-sm">
          {searchError && <li className="px-3 py-2 text-rose-600 text-xs">{searchError}</li>}
          {found.map((city) => (
            <li key={city.id}>
              <button
                type="button"
                className="w-full text-left px-3 py-2 hover:bg-zinc-50"
                onClick={() => {
                  onPick(city);
                  setQuery('');
                }}
              >
                <span className="font-medium text-zinc-900">{city.name}</span>
                {city.matchedName && <span className="text-zinc-500"> («{city.matchedName}»)</span>}
                {city.region && <span className="block text-[11px] text-zinc-400">{city.region}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
};

/** Max length of an interest comment (PUT /me/profile interestComments). */
export const INTEREST_COMMENT_MAX = 80;

/** Chosen interests and, under them, a short comment for each («латте, капучино»), shown to others on a tap. */
export const InterestsPicker: React.FC<{
  selected: string[];
  onChange: (codes: string[]) => void;
  comments: Record<string, string>;
  onCommentsChange: (comments: Record<string, string>) => void;
}> = ({ selected, onChange, comments, onCommentsChange }) => {
  const [groups, setGroups] = useState<InterestGroup[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    api
      .getInterests(controller.signal)
      .then(setGroups)
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(getErrorMessage(e, 'Не удалось загрузить увлечения'));
      });
    return () => controller.abort();
  }, []);

  if (error) return <p className="text-xs text-rose-600">{error}</p>;
  if (!groups) return <p className="text-xs text-zinc-400">Загрузка увлечений...</p>;
  const full = selected.length >= MAX_INTERESTS;
  return (
    <div className="space-y-2">
      {groups.map((group) => (
        <div key={group.code}>
          <p className="text-[11px] font-semibold text-zinc-500 mb-1">{group.label}</p>
          <div className="flex flex-wrap gap-1.5">
            {group.items.map((item) => {
              const on = selected.includes(item.code);
              return (
                <Chip
                  key={item.code}
                  label={item.emoji ? `${item.emoji} ${item.label}` : item.label}
                  selected={on}
                  disabled={!on && full}
                  onClick={() => onChange(on ? selected.filter((c) => c !== item.code) : [...selected, item.code])}
                />
              );
            })}
          </div>
        </div>
      ))}
      {selected.length > 0 && (
        <div className="pt-2 space-y-1.5">
          <p className="text-[11px] font-semibold text-zinc-500">Комментарии к увлечениям (видны при нажатии)</p>
          {selected.map((code) => {
            const item = groups.flatMap((g) => g.items).find((i) => i.code === code);
            if (!item) return null;
            return (
              <label key={code} className="flex items-center gap-2">
                <span className="w-36 shrink-0 text-xs text-zinc-700 truncate">
                  {item.emoji ? `${item.emoji} ${item.label}` : item.label}
                </span>
                <input
                  value={comments[code] ?? ''}
                  onChange={(e) => onCommentsChange({ ...comments, [code]: e.target.value })}
                  maxLength={INTEREST_COMMENT_MAX}
                  placeholder="Например: латте, капучино"
                  aria-label={`Комментарий: ${item.label}`}
                  className="flex-1 min-w-0 text-xs bg-zinc-50 border border-zinc-200 rounded-xl px-3 py-1.5 focus:outline-none focus:ring-2 focus:ring-zinc-900"
                />
              </label>
            );
          })}
        </div>
      )}
    </div>
  );
};

export const ArtistsPicker: React.FC<{ selected: Artist[]; onChange: (artists: Artist[]) => void }> = ({
  selected,
  onChange,
}) => {
  const { showToast } = useToast();
  const [query, setQuery] = useState('');
  const { found, error } = useSearch(query, (q, s) => api.searchArtists(q, s), 2);
  const toggle = (artist: Artist) => {
    if (selected.some((a) => a.id === artist.id)) {
      onChange(selected.filter((a) => a.id !== artist.id));
    } else if (selected.length < MAX_ARTISTS) {
      onChange([...selected, artist]);
    } else {
      showToast(`Можно выбрать до ${MAX_ARTISTS} исполнителей`, 'error');
    }
  };
  return (
    <div className="space-y-2">
      {selected.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {selected.map((a) => (
            <button
              key={a.id}
              type="button"
              onClick={() => toggle(a)}
              className="inline-flex items-center gap-1 text-xs px-2.5 py-1 rounded-full border font-medium bg-zinc-900 text-white border-zinc-900"
              aria-label={`Убрать ${a.name}`}
            >
              {a.name}
              <X className="w-3 h-3" aria-hidden="true" />
            </button>
          ))}
        </div>
      )}
      <div className="relative">
        <Search className="w-4 h-4 absolute left-3 top-2.5 text-zinc-400" />
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Найти исполнителя"
          aria-label="Найти исполнителя"
          className={`${inputClass} pl-9`}
        />
      </div>
      {error && <p className="text-xs text-rose-600">{error}</p>}
      {found.length > 0 && (
        <ul className="border border-zinc-200 rounded-xl divide-y divide-zinc-100 max-h-56 overflow-y-auto">
          {found.map((a) => {
            const on = selected.some((s) => s.id === a.id);
            return (
              <li key={a.id}>
                <button
                  type="button"
                  onClick={() => toggle(a)}
                  className="w-full flex items-center gap-2 px-3 py-2 text-sm text-left hover:bg-zinc-50"
                >
                  <Music className="w-4 h-4 text-zinc-400 shrink-0" />
                  <span className="flex-1">{a.name}</span>
                  {on && <Check className="w-4 h-4 text-emerald-600" />}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
};

/** Lifestyle or work facts; answers come from GET /profile-options. */
export const FactsFields: React.FC<{
  keys: readonly FactKey[];
  facts: Facts;
  onChange: (facts: Facts) => void;
  errors: Record<string, string>;
}> = ({ keys, facts, onChange, errors }) => {
  const [options, setOptions] = useState<ProfileOptions | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    api
      .getProfileOptions(controller.signal)
      .then(setOptions)
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(getErrorMessage(e, 'Не удалось загрузить варианты'));
      });
    return () => controller.abort();
  }, []);

  const toggleList = (key: 'pets' | 'languages', code: string) => {
    const list = facts[key];
    let next: string[];
    if (list.includes(code)) next = list.filter((c) => c !== code);
    // «Нет животных» and animals exclude each other.
    else if (key === 'pets' && (code === 'NONE') !== list.includes('NONE')) next = [code];
    else next = [...list, code];
    onChange({ ...facts, [key]: next });
  };

  return (
    <div className="space-y-3">
      {error && <p className="text-xs text-rose-600">{error}</p>}
      {keys.map((key) => {
        const max = TEXT_MAX[key];
        if (max) {
          return (
            <div key={key}>
              <label className="block text-[11px] font-semibold text-zinc-500 mb-1">{FACT_TITLES[key]}</label>
              <input
                type="text"
                maxLength={max}
                value={(facts[key] as string | null) ?? ''}
                onChange={(e) => onChange({ ...facts, [key]: e.target.value })}
                className={inputClass}
              />
              {errors[key] && <p className="text-[11px] text-rose-600 mt-1">{errors[key]}</p>}
            </div>
          );
        }
        if (!options) return null;
        const isList = LIST_FACTS.includes(key);
        return (
          <div key={key}>
            <p className="text-[11px] font-semibold text-zinc-500 mb-1">{FACT_TITLES[key]}</p>
            <div className="flex flex-wrap gap-1.5">
              {(options[key] ?? []).map((o) => {
                const value = facts[key];
                const on = isList ? (value as string[]).includes(o.code) : value === o.code;
                return (
                  <Chip
                    key={o.code}
                    label={o.label}
                    selected={on}
                    onClick={() =>
                      isList
                        ? toggleList(key as 'pets' | 'languages', o.code)
                        : onChange({ ...facts, [key]: on ? null : o.code })
                    }
                  />
                );
              })}
            </div>
            {errors[key] && <p className="text-[11px] text-rose-600 mt-1">{errors[key]}</p>}
          </div>
        );
      })}
    </div>
  );
};
