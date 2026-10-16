import type { TagLogic } from '../../core/types';
import { readAccountJson, writeAccountJson } from '../../core/storage/accountStorage';

// Limits from the API contract.
export const AGE_MIN = 18;
export const AGE_MAX = 100;
/** Same range as PUT /me/profile heightCm. */
export const HEIGHT_MIN = 120;
export const HEIGHT_MAX = 230;

/** Discovery filters chosen in the "Фильтры" modal (saved hard filters live on the server instead). */
export interface DiscoveryFilterState {
  /** null = all cities. */
  city: string | null;
  minAge?: number;
  maxAge?: number;
  tagCodes: string[];
  tagLogic: TagLogic;
  /** Premium ADVANCED_FILTERS: kept while locked, but not sent. */
  minHeight?: number;
  maxHeight?: number;
  /** Premium ADVANCED_FILTERS: "Ищу цели", the profile has at least one of them. */
  goalCodes: string[];
}

export const EMPTY_DISCOVERY_FILTERS: DiscoveryFilterState = {
  city: null,
  tagCodes: [],
  tagLogic: 'OR',
  goalCodes: [],
};

/** Number of active free filters (city, age, tags). */
export function countBasicFilters(f: DiscoveryFilterState): number {
  return (
    (f.city ? 1 : 0) +
    (f.minAge !== undefined || f.maxAge !== undefined ? 1 : 0) +
    (f.tagCodes.length > 0 ? 1 : 0)
  );
}

/** Number of active premium filters (height, wanted goals). */
export function countAdvancedFilters(f: DiscoveryFilterState): number {
  return (f.minHeight !== undefined || f.maxHeight !== undefined ? 1 : 0) + (f.goalCodes.length > 0 ? 1 : 0);
}

/** The same filters without premium-only values (what is sent while ADVANCED_FILTERS is locked). */
export function withoutAdvanced(f: DiscoveryFilterState): DiscoveryFilterState {
  return { ...f, minHeight: undefined, maxHeight: undefined, goalCodes: [] };
}

// ---------------------------------------------------------------------------
// Persistence: localStorage per account; anything missing or malformed falls back to "no filter".
// ---------------------------------------------------------------------------

const STORAGE_NAME = 'discovery_filters_v1';
const MAX_CODES = 50;

function intInRange(value: unknown, min: number, max: number): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max ? value : undefined;
}

function codeList(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  const codes = value.filter((c): c is string => typeof c === 'string' && c.trim() !== '' && c.length <= 64);
  return [...new Set(codes)].slice(0, MAX_CODES);
}

/** Validates untrusted stored data field by field. */
export function parseDiscoveryFilters(raw: unknown): DiscoveryFilterState {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return EMPTY_DISCOVERY_FILTERS;
  const r = raw as Record<string, unknown>;
  let minAge = intInRange(r.minAge, AGE_MIN, AGE_MAX);
  let maxAge = intInRange(r.maxAge, AGE_MIN, AGE_MAX);
  if (minAge !== undefined && maxAge !== undefined && minAge > maxAge) minAge = maxAge = undefined;
  let minHeight = intInRange(r.minHeight, HEIGHT_MIN, HEIGHT_MAX);
  let maxHeight = intInRange(r.maxHeight, HEIGHT_MIN, HEIGHT_MAX);
  if (minHeight !== undefined && maxHeight !== undefined && minHeight > maxHeight) minHeight = maxHeight = undefined;
  return {
    city: typeof r.city === 'string' && r.city.trim() !== '' && r.city.length <= 60 ? r.city.trim() : null,
    minAge,
    maxAge,
    tagCodes: codeList(r.tagCodes),
    tagLogic: r.tagLogic === 'AND' ? 'AND' : 'OR',
    minHeight,
    maxHeight,
    goalCodes: codeList(r.goalCodes),
  };
}

export function loadDiscoveryFilters(accountId: string | null): DiscoveryFilterState {
  if (!accountId) return EMPTY_DISCOVERY_FILTERS;
  return parseDiscoveryFilters(readAccountJson(STORAGE_NAME, accountId));
}

export function saveDiscoveryFilters(accountId: string, filters: DiscoveryFilterState): void {
  writeAccountJson(STORAGE_NAME, accountId, filters);
}
