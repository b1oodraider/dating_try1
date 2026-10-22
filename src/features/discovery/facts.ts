import type { Facts, FactFilters, ProfileOptions } from '../../core/types';

export type FactKey = keyof Facts;
export type FilterableFact = keyof FactFilters;

export const FACT_TITLES: Record<FactKey, string> = {
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

/** Sections of the full profile, in mobile's order. */
export const FACT_SECTIONS: [string, FactKey[]][] = [
  ['Образ жизни', ['kidsHas', 'kidsPlans', 'smoking', 'alcohol', 'sport', 'pets']],
  ['Работа и учёба', ['profession', 'educationLevel', 'university', 'languages']],
];

/** Facts the feed can be filtered on (PUT /me/fact-filters keys). */
export const FILTERABLE_FACTS: FilterableFact[] = ['kidsHas', 'kidsPlans', 'smoking', 'alcohol', 'sport', 'pets'];

/** Human text of one fact: labels joined, or the free text itself; null when not said. */
export function describeFact(facts: Facts, key: FactKey, options: ProfileOptions): string | null {
  const value = facts[key];
  if (key === 'profession' || key === 'university') return (value as string | null)?.trim() || null;
  const codes = Array.isArray(value) ? value : value ? [value] : [];
  if (codes.length === 0) return null;
  return codes.map((c) => options[key]?.find((o) => o.code === c)?.label ?? c).join(', ');
}
